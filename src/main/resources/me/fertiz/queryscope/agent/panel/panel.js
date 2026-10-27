const MAX_REPORTS = 200;
const KPI_WINDOW_MS = 60_000;
const SPARK_BUCKETS = 30;
const FEED_SIZE = 10;
const TOP_ENDPOINTS = 10;
const STREAM_TOP = 28;
const LANE_HEIGHT = 22;
const BAR_HEIGHT = 14;
const WINDOWS = [
    {label: '30 s', ms: 30_000},
    {label: '1 min', ms: 60_000},
    {label: '5 min', ms: 300_000},
];
const SQL_KEYWORDS = new Set([
    'select', 'from', 'where', 'and', 'or', 'not', 'in', 'is', 'null', 'join', 'left', 'right', 'inner', 'outer',
    'full', 'cross', 'on', 'group', 'by', 'order', 'having', 'limit', 'offset', 'insert', 'into', 'values', 'update',
    'set', 'delete', 'as', 'distinct', 'case', 'when', 'then', 'else', 'end', 'desc', 'asc', 'like', 'exists',
    'between', 'union', 'all', 'with', 'returning', 'fetch', 'first', 'next', 'rows', 'only', 'true', 'false', 'default',
]);

const FINDING_TYPES = {
    nPlusOne: {area: 'sql', css: 'n-plus-one', name: 'N+1', label: finding => `N+1 ×${finding.executions}`},
    slowQuery: {area: 'sql', css: 'slow', name: 'Slow', label: finding => `Slow ${formatMs(finding.durationMs)}`},
    repeatedWrite: {area: 'sql', css: 'repeated-write', name: 'Writes', label: finding => `Writes ×${finding.executions}`},
    duplicateQuery: {area: 'sql', css: 'duplicate', name: 'Duplicate', label: finding => `Duplicate ×${finding.executions}`},
    largeResult: {area: 'sql', css: 'large-result', name: 'Rows', label: finding => `${formatCount(finding.rows)} rows`},
    tooManyQueries: {area: 'sql', css: 'too-many', name: 'Queries', label: finding => `${finding.queries} queries`},
    slowMethod: {area: 'java', css: 'slow-method', name: 'Code', label: finding => `Slow code ${formatMs(finding.ownMs)}`},
};
const VIEWS = {'sql/live': 'view-live', 'sql/requests': 'view-requests', 'java/calls': 'view-calls'};
const LEGACY_VIEWS = {live: 'sql/live', requests: 'sql/requests'};
const HOT_METHODS = 15;
const QUERY_ROLE_PRIORITY = ['n-plus-one', 'slow', 'repeated-write', 'duplicate', 'large-result'];
const CALL_INDENT = 18;
const CALL_EXPAND_SHARE = 0.05;
const ROLE_ORDER = ['duplicateQuery', 'repeatedWrite', 'largeResult', 'nPlusOne', 'slowQuery'];

const state = {
    reports: [],
    sqlReports: [],
    javaReports: [],
    pending: [],
    selectedId: null,
    follow: true,
    onlyFindings: false,
    callSelectedId: null,
    callFollow: true,
    onlySlowCode: false,
    callRenderedId: undefined,
    connected: false,
    paused: false,
    pausedAt: 0,
    view: 'sql/live',
    lastViews: {sql: 'sql/live', java: 'java/calls'},
    windowMs: WINDOWS[0].ms,
    renderedId: undefined,
    laneEnds: [],
    hits: [],
    hoverId: null,
    stats: null,
    feedTimes: [],
    announced: new Set(),
    snackTimer: 0,
    callTrees: new Map(),
    callToggles: new Map(),
};

const $ = id => document.getElementById(id);
const css = getComputedStyle(document.documentElement);
const color = name => css.getPropertyValue(name).trim();
const COLORS = {
    blue: color('--blue'),
    red: color('--red'),
    yellow: color('--yellow'),
    orange: color('--orange'),
    muted: color('--muted'),
    border: color('--border'),
    text: color('--text'),
    mono: color('--mono'),
};
const SEVERITY_COLORS = {'ok': COLORS.blue, 'warn': COLORS.orange, 'slow': COLORS.yellow, 'n-plus-one': COLORS.red};

const streamCanvas = $('stream-canvas');
const tooltip = $('stream-tooltip');

for (const button of document.querySelectorAll('.tab')) {
    button.addEventListener('click', () => selectView(state.lastViews[button.dataset.area]));
}
for (const button of document.querySelectorAll('.subtab')) {
    button.addEventListener('click', () => selectView(button.dataset.view));
}
window.addEventListener('hashchange', () => selectView(viewFromHash()));
$('pause').addEventListener('click', togglePause);
$('clear').addEventListener('click', clearAll);
$('only-findings').addEventListener('change', event => {
    state.onlyFindings = event.target.checked;
    render();
});
$('only-slow-code').addEventListener('change', event => {
    state.onlySlowCode = event.target.checked;
    render();
});
streamCanvas.addEventListener('mousemove', event => hover(event));
streamCanvas.addEventListener('mouseleave', () => {
    state.hoverId = null;
    tooltip.hidden = true;
});
streamCanvas.addEventListener('click', () => {
    if (state.hoverId !== null) {
        openRequest(state.hoverId);
    }
});

renderWindowButtons();
selectView(viewFromHash());
render();
connect();
setInterval(tick, 1000);
requestAnimationFrame(frame);

function connect() {
    const events = new EventSource('api/events');
    let buffered = null;

    events.addEventListener('open', async () => {
        buffered = [];
        try {
            const response = await fetch('api/requests');
            const stored = (await response.json()).map(enrich);
            setReports([...buffered.reverse(), ...stored]);
            state.reports.forEach(report => report.findings.forEach(finding => state.announced.add(findingKey(report, finding))));
            relayoutLanes();
            render();
            setConnection(true);
        } finally {
            buffered = null;
        }
    });

    events.addEventListener('request', event => {
        const report = enrich(JSON.parse(event.data));
        if (buffered) {
            buffered.push(report);
        } else if (state.paused) {
            state.pending.push(report);
            updatePauseButton();
        } else {
            add(report);
        }
    });

    events.addEventListener('error', () => setConnection(false));
}

function add(report) {
    setReports([report, ...state.reports]);
    if (report.view.hasSql) {
        assignLane(report);
    }
    announce(report);
    render();
}

function enrich(report) {
    const start = parseInstant(report.startedAt);
    const sqlFindings = report.findings.filter(finding => findingArea(finding) === 'sql');
    const javaFindings = report.findings.filter(finding => findingArea(finding) === 'java');
    const types = new Set(sqlFindings.map(finding => finding.type));
    report.view = {
        hasSql: report.queries.length > 0,
        sqlFindings,
        javaFindings,
        start,
        end: start + report.durationMs,
        dbMs: report.queries.reduce((sum, query) => sum + query.durationMs, 0),
        endpointPath: endpointPath(report.path),
        severity: types.has('nPlusOne') ? 'n-plus-one' : types.has('slowQuery') ? 'slow' : types.size > 0 ? 'warn' : 'ok',
        types,
        lane: 0,
    };
    return report;
}

function setReports(reports) {
    state.reports = newestUnique(reports);
    state.sqlReports = state.reports.filter(report => report.view.hasSql);
    state.javaReports = state.reports.filter(report => report.methods > 0);
}

function newestUnique(reports) {
    const seen = new Set();
    const kept = {sql: 0, java: 0};
    return reports.filter(report => {
        if (seen.has(report.id)) {
            return false;
        }
        seen.add(report.id);
        const pool = report.view.hasSql ? 'sql' : 'java';
        kept[pool]++;
        return kept[pool] <= MAX_REPORTS;
    });
}

function now() {
    return state.paused ? state.pausedAt : Date.now();
}

function setConnection(connected) {
    state.connected = connected;
    updateStatus();
}

function updateStatus() {
    document.body.classList.toggle('live', state.connected && !state.paused);
    document.body.classList.toggle('paused', state.paused);
    $('connection').textContent = state.paused ? 'Paused' : state.connected ? 'Live' : 'Reconnecting';
}

function togglePause() {
    state.paused = !state.paused;
    if (state.paused) {
        state.pausedAt = Date.now();
    } else {
        const pending = state.pending;
        state.pending = [];
        pending.forEach(add);
    }
    updatePauseButton();
    updateStatus();
    render();
}

function updatePauseButton() {
    $('pause').textContent = state.paused
        ? `Resume${state.pending.length > 0 ? ` (${state.pending.length} new)` : ''}`
        : 'Pause';
}

async function clearAll() {
    await fetch('api/requests', {method: 'DELETE'});
    setReports([]);
    state.pending = [];
    state.selectedId = null;
    state.follow = true;
    state.callSelectedId = null;
    state.callFollow = true;
    state.laneEnds = [];
    updatePauseButton();
    render();
}

function viewFromHash() {
    const hash = location.hash.slice(1);
    return VIEWS[hash] ? hash : LEGACY_VIEWS[hash] ?? 'sql/live';
}

function selectView(view) {
    const area = view.split('/')[0];
    state.view = view;
    state.lastViews[area] = view;
    for (const button of document.querySelectorAll('.tab')) {
        button.setAttribute('aria-selected', String(button.dataset.area === area));
    }
    for (const group of document.querySelectorAll('.subtab-group')) {
        group.hidden = group.dataset.area !== area;
    }
    for (const button of document.querySelectorAll('.subtab')) {
        button.setAttribute('aria-selected', String(button.dataset.view === view));
    }
    for (const [key, id] of Object.entries(VIEWS)) {
        $(id).hidden = key !== view;
    }
    if (location.hash !== `#${view}`) {
        history.replaceState(null, '', `#${view}`);
    }
}

function openRequest(id) {
    state.selectedId = id;
    state.follow = false;
    selectView('sql/requests');
    render();
}

function openCalls(id) {
    state.callSelectedId = id;
    state.callFollow = false;
    selectView('java/calls');
    render();
}

function openFinding(report, finding) {
    if (findingArea(finding) === 'java') {
        openCalls(report.id);
    } else {
        openRequest(report.id);
    }
}

function render() {
    $('request-count').textContent = String(state.sqlReports.length);
    $('call-count').textContent = String(state.javaReports.length);
    $('stream-empty').hidden = state.sqlReports.length > 0;
    renderStats();
    renderEndpoints();
    renderFeed();
    renderRequests();
    renderCalls();
}

function tick() {
    renderStats();
    const current = now();
    for (const {node, end} of state.feedTimes) {
        node.textContent = ago(current - end);
    }
}

function frame() {
    if (state.view === 'sql/live') {
        drawStream();
    }
    requestAnimationFrame(frame);
}

function renderStats() {
    const end = now();
    const from = end - KPI_WINDOW_MS;
    const recent = state.sqlReports.filter(report => report.view.end > from && report.view.end <= end);
    const queries = recent.reduce((sum, report) => sum + report.queries.length, 0);
    const duration = recent.reduce((sum, report) => sum + report.durationMs, 0);
    const db = recent.reduce((sum, report) => sum + report.view.dbMs, 0);
    const withNPlusOne = state.sqlReports.filter(report => report.view.types.has('nPlusOne'));
    const withSlow = state.sqlReports.filter(report => report.view.types.has('slowQuery'));
    const dbSeries = series(end, report => report.view.dbMs);
    const durationSeries = series(end, report => report.durationMs);
    const stats = [
        {key: 'requests', label: 'Requests per minute', value: recent.length,
            sub: `${plural(state.sqlReports.length, 'request', 'requests')} kept`, series: series(end, () => 1)},
        {key: 'queries', label: 'Queries per minute', value: queries,
            sub: recent.length > 0 ? `${(queries / recent.length).toFixed(1)} per request` : 'No requests in the last minute',
            series: series(end, report => report.queries.length)},
        {key: 'db', label: 'Time in database', value: duration > 0 ? Math.round(db / duration * 100) : 0, unit: '%',
            sub: `${formatMs(db)} of ${formatMs(duration)} in the last minute`,
            series: dbSeries.map((value, index) => durationSeries[index] > 0 ? value / durationSeries[index] : 0)},
        {key: 'nPlusOne', label: 'Requests with N+1', value: withNPlusOne.length,
            alert: withNPlusOne.length > 0 ? 'alert-red' : '',
            sub: withNPlusOne.length > 0 ? `Latest: ${withNPlusOne[0].method} ${withNPlusOne[0].view.endpointPath}` : 'None so far'},
        {key: 'slow', label: 'Requests with slow queries', value: withSlow.length,
            alert: withSlow.length > 0 ? 'alert-yellow' : '',
            sub: withSlow.length > 0 ? `Latest: ${withSlow[0].method} ${withSlow[0].view.endpointPath}` : 'None so far'},
    ];
    if (!state.stats) {
        state.stats = new Map();
        $('kpis').replaceChildren(...stats.map(stat => {
            const value = el('div', {class: 'stat-value'});
            const sub = el('div', {class: 'stat-sub'});
            const canvas = stat.series ? el('canvas', {}) : null;
            const node = el('div', {class: 'stat'}, el('div', {class: 'stat-label'}, stat.label), value, sub, canvas);
            state.stats.set(stat.key, {node, value, sub, canvas});
            return node;
        }));
    }
    for (const stat of stats) {
        const view = state.stats.get(stat.key);
        view.node.className = `stat ${stat.alert ?? ''}`.trim();
        view.value.replaceChildren(...[String(stat.value), stat.unit ? el('small', {}, stat.unit) : null].filter(Boolean));
        view.sub.textContent = stat.sub;
        if (view.canvas) {
            drawSparkline(view.canvas, stat.series);
        }
    }
}

function series(end, valueOf) {
    const size = KPI_WINDOW_MS / SPARK_BUCKETS;
    const from = end - KPI_WINDOW_MS;
    const values = new Array(SPARK_BUCKETS).fill(0);
    for (const report of state.sqlReports) {
        const index = Math.floor((report.view.end - from) / size);
        if (index >= 0 && index < SPARK_BUCKETS) {
            values[index] += valueOf(report);
        }
    }
    return values;
}

function drawSparkline(canvas, values) {
    const context = prepare(canvas);
    if (!context) {
        return;
    }
    const {width, height} = context.size;
    const max = Math.max(...values, 0) || 1;
    context.beginPath();
    values.forEach((value, index) => {
        const x = index / (values.length - 1) * width;
        const y = height - 2 - value / max * (height - 6);
        if (index === 0) {
            context.moveTo(x, y);
        } else {
            context.lineTo(x, y);
        }
    });
    context.lineWidth = 1.5;
    context.lineJoin = 'round';
    context.strokeStyle = COLORS.blue;
    context.stroke();
}

function renderWindowButtons() {
    $('window-chips').replaceChildren(...WINDOWS.map(option => el('button', {
        type: 'button',
        class: 'segment',
        'aria-pressed': String(option.ms === state.windowMs),
        onclick: () => {
            state.windowMs = option.ms;
            relayoutLanes();
            renderWindowButtons();
        },
    }, option.label)));
}

function laneCount() {
    const height = streamCanvas.clientHeight || 260;
    return Math.max(1, Math.floor((height - STREAM_TOP - 6) / LANE_HEIGHT));
}

function barWidth(report, width) {
    const durationWidth = report.durationMs / state.windowMs * width;
    return Math.max(durationWidth, 8 + Math.min(80, report.queries.length * 1.6));
}

function relayoutLanes() {
    state.laneEnds = [];
    [...state.sqlReports].sort((a, b) => a.view.start - b.view.start).forEach(assignLane);
}

function assignLane(report) {
    const width = streamCanvas.clientWidth || 1000;
    const visualMs = (barWidth(report, width) + 6) / width * state.windowMs;
    let lane = state.laneEnds.findIndex(laneEnd => laneEnd <= report.view.start);
    if (lane < 0 && state.laneEnds.length < laneCount()) {
        lane = state.laneEnds.length;
        state.laneEnds.push(0);
    }
    if (lane < 0) {
        lane = state.laneEnds.indexOf(Math.min(...state.laneEnds));
    }
    state.laneEnds[lane] = Math.max(state.laneEnds[lane], report.view.start + visualMs);
    report.view.lane = lane;
}

function drawStream() {
    const context = prepare(streamCanvas);
    if (!context) {
        return;
    }
    const {width, height} = context.size;
    const end = now();
    const from = end - state.windowMs;
    const x = time => (time - from) / state.windowMs * width;

    context.font = `11px ${COLORS.mono}`;
    context.textAlign = 'right';
    for (let step = 0; step <= 6; step++) {
        const gridX = Math.round(width - step * width / 6) - 0.5;
        context.fillStyle = COLORS.border;
        context.fillRect(gridX, STREAM_TOP - 6, 1, height);
        context.fillStyle = COLORS.muted;
        const label = step === 0 ? 'now' : `-${formatSeconds(step * state.windowMs / 6)}`;
        context.fillText(label, Math.max(gridX - 6, 34), 16);
    }

    const hits = [];
    for (const report of [...state.sqlReports].reverse()) {
        if (report.view.start > end) {
            continue;
        }
        const left = x(report.view.start);
        const bar = barWidth(report, width);
        if (left + bar < 0) {
            continue;
        }
        const top = STREAM_TOP + report.view.lane * LANE_HEIGHT;
        const hovered = report.id === state.hoverId;
        context.globalAlpha = state.hoverId === null || hovered ? 1 : 0.55;
        context.fillStyle = SEVERITY_COLORS[report.view.severity];
        roundedRect(context, left, top, bar, BAR_HEIGHT, 3);
        context.fill();
        if (bar > 14 && report.durationMs > 0) {
            context.fillStyle = 'rgba(32, 33, 36, 0.55)';
            for (const query of report.queries) {
                const tickX = left + 4 + query.offsetMs / report.durationMs * (bar - 8);
                context.fillRect(tickX, top + 3, 1.2, BAR_HEIGHT - 6);
            }
        }
        if (hovered) {
            context.strokeStyle = COLORS.text;
            context.lineWidth = 1;
            roundedRect(context, left - 1.5, top - 1.5, bar + 3, BAR_HEIGHT + 3, 4);
            context.stroke();
        }
        context.globalAlpha = 1;
        hits.push({id: report.id, report, left, top, right: left + bar, bottom: top + BAR_HEIGHT});
    }
    state.hits = hits;
}

function hover(event) {
    const bounds = streamCanvas.getBoundingClientRect();
    const mouseX = event.clientX - bounds.left;
    const mouseY = event.clientY - bounds.top;
    const hit = [...state.hits].reverse().find(box =>
        mouseX >= box.left - 3 && mouseX <= box.right + 3 && mouseY >= box.top - 4 && mouseY <= box.bottom + 4);
    state.hoverId = hit ? hit.id : null;
    streamCanvas.classList.toggle('pointing', Boolean(hit));
    if (!hit) {
        tooltip.hidden = true;
        return;
    }
    const report = hit.report;
    tooltip.replaceChildren(...[
        el('div', {class: 'tooltip-path'}, `${report.method} ${report.path}`),
        el('div', {class: 'tooltip-meta'}, [
            isTask(report) ? 'Task' : `HTTP ${report.status}`,
            formatMs(report.durationMs),
            plural(report.queries.length, 'query', 'queries'),
            `${formatMs(report.view.dbMs)} in database`,
        ].join(' · ')),
        report.view.sqlFindings.length > 0
            ? el('div', {class: 'tooltip-meta'}, report.view.sqlFindings.map(findingLabel))
            : null,
    ].filter(Boolean));
    tooltip.hidden = false;
    const left = Math.min(mouseX + 14, bounds.width - tooltip.offsetWidth - 8);
    const top = mouseY + 16 + tooltip.offsetHeight > bounds.height ? mouseY - tooltip.offsetHeight - 10 : mouseY + 16;
    tooltip.style.left = `${Math.max(8, left)}px`;
    tooltip.style.top = `${Math.max(8, top)}px`;
}

function renderEndpoints() {
    const groups = new Map();
    for (const report of state.sqlReports) {
        const key = `${report.method} ${report.view.endpointPath}`;
        const group = groups.get(key) ?? {
            method: report.method, path: report.view.endpointPath, latestId: report.id,
            calls: 0, queries: 0, duration: 0, db: 0, types: new Set(),
        };
        group.calls++;
        group.queries += report.queries.length;
        group.duration += report.durationMs;
        group.db += report.view.dbMs;
        report.view.types.forEach(type => group.types.add(type));
        groups.set(key, group);
    }
    const top = [...groups.values()].sort((a, b) => b.db - a.db).slice(0, TOP_ENDPOINTS);
    if (top.length === 0) {
        $('endpoints').replaceChildren(el('p', {class: 'empty'}, 'Endpoints appear here after their first request.'));
        return;
    }
    $('endpoints').replaceChildren(table(
        [
            {title: 'Endpoint or task', width: 'auto'},
            {title: 'Calls', width: '70px', number: true},
            {title: 'Queries per call', width: '120px', number: true},
            {title: 'Avg time', width: '90px', number: true},
            {title: 'Database time', width: '120px', number: true},
            {title: 'Findings', width: '190px'},
        ],
        top.map(group => el('tr', {class: 'clickable', onclick: () => openRequest(group.latestId)},
            el('td', {class: 'mono ellipsis', title: group.path}, el('span', {class: 'method'}, group.method), group.path),
            el('td', {class: 'number'}, String(group.calls)),
            el('td', {class: 'number'}, (group.queries / group.calls).toFixed(1)),
            el('td', {class: 'number'}, formatMs(group.duration / group.calls)),
            el('td', {class: 'number'}, formatMs(group.db)),
            el('td', {}, group.types.size === 0 ? '—' : Object.keys(FINDING_TYPES)
                .filter(type => group.types.has(type))
                .map(type => el('span', {class: `label ${FINDING_TYPES[type].css}`}, FINDING_TYPES[type].name)))))));
}

function renderFeed() {
    const items = [];
    for (const report of state.sqlReports) {
        for (const finding of report.view.sqlFindings) {
            items.push({report, finding});
        }
        if (items.length >= FEED_SIZE) {
            break;
        }
    }
    state.feedTimes = [];
    if (items.length === 0) {
        $('findings-feed').replaceChildren(el('p', {class: 'empty'}, 'No findings so far.'));
        return;
    }
    const current = now();
    $('findings-feed').replaceChildren(table(
        [
            {title: 'When', width: '80px'},
            {title: 'Finding', width: '130px'},
            {title: 'Request and location', width: 'auto'},
        ],
        items.slice(0, FEED_SIZE).map(({report, finding}) => {
            const time = el('td', {}, ago(current - report.view.end));
            state.feedTimes.push({node: time, end: report.view.end});
            return el('tr', {class: 'clickable', onclick: () => openRequest(report.id)},
                time,
                el('td', {}, findingLabel(finding)),
                el('td', {class: 'ellipsis'},
                    el('div', {class: 'path', title: report.path}, `${report.method} ${report.path}`),
                    el('div', {class: 'path', title: finding.callSite ?? finding.method ?? ''},
                        el('span', {class: 'muted'}, findingLocation(finding)))));
        })));
}

function table(columns, rows) {
    return el('table', {},
        el('colgroup', {}, columns.map(column => el('col', {style: {width: column.width}}))),
        el('thead', {}, el('tr', {}, columns.map(column =>
            el('th', {class: column.number ? 'number' : ''}, column.title)))),
        el('tbody', {}, rows));
}

function announce(report) {
    const finding = report.findings.find(candidate => !state.announced.has(findingKey(report, candidate)));
    if (!finding) {
        return;
    }
    report.findings.forEach(candidate => state.announced.add(findingKey(report, candidate)));
    const snackbar = $('snackbar');
    $('snackbar-text').textContent =
        `New finding: ${findingText(finding)} in ${report.method} ${report.view.endpointPath}`;
    $('snackbar-action').onclick = () => {
        snackbar.hidden = true;
        openFinding(report, finding);
    };
    snackbar.hidden = false;
    clearTimeout(state.snackTimer);
    state.snackTimer = setTimeout(() => snackbar.hidden = true, 6000);
}

function renderRequests() {
    const visible = state.sqlReports.filter(report => !state.onlyFindings || report.view.sqlFindings.length > 0);
    if (state.follow || !visible.some(report => report.id === state.selectedId)) {
        state.selectedId = visible.length > 0 ? visible[0].id : null;
    }
    $('request-list').replaceChildren(...visible.map((report, index) => requestItem(report, index)));
    $('no-requests').hidden = visible.length > 0;
    $('no-requests').textContent = state.sqlReports.length > 0
        ? 'No requests with findings yet.'
        : 'Waiting for requests or tasks that run SQL.';
    const selected = state.sqlReports.find(report => report.id === state.selectedId) ?? null;
    if ((selected?.id ?? null) !== state.renderedId) {
        renderDetails(selected);
        state.renderedId = selected?.id ?? null;
    }
}

function requestItem(report, index) {
    return el('li', {
            class: `request${report.id === state.selectedId ? ' selected' : ''}`,
            onclick: () => {
                state.selectedId = report.id;
                state.follow = index === 0;
                render();
            },
        },
        el('div', {class: 'request-line'},
            el('span', {class: 'method'}, report.method),
            el('span', {class: 'path', title: report.path}, report.path)),
        el('div', {class: 'request-meta'},
            isTask(report)
                ? el('span', {}, 'Task')
                : el('span', {class: report.status >= 400 ? 'status-error' : ''}, String(report.status)),
            el('span', {}, plural(report.queries.length, 'query', 'queries')),
            el('span', {}, formatMs(report.durationMs)),
            report.view.sqlFindings.map(findingLabel)));
}

function renderDetails(report) {
    if (!report) {
        $('details').replaceChildren(el('div', {class: 'panel'},
            el('p', {class: 'empty', style: {paddingTop: '20px'}}, 'Select a request to see its queries.')));
        return;
    }
    const roles = queryRoles(report);
    $('details').replaceChildren(
        el('div', {class: 'panel details-header'},
            el('div', {class: 'details-title'},
                el('h1', {}, el('span', {class: 'method'}, report.method), report.path),
                report.methods > 0
                    ? el('button', {type: 'button', class: 'text-button', onclick: () => openCalls(report.id)},
                        'Call tree')
                    : null),
            el('dl', {class: 'facts'},
                isTask(report) ? fact('Kind', 'Task outside HTTP') : fact('Status', String(report.status)),
                fact('Duration', formatMs(report.durationMs)),
                fact('Queries', String(report.queries.length)),
                fact('Time in database', formatMs(report.view.dbMs)),
                fact('Started', new Date(report.view.start).toLocaleTimeString()))),
        el('h3', {class: 'section-title'}, 'Findings'),
        ...findingCards(report.view.sqlFindings, 'No SQL findings in this request.'),
        el('h3', {class: 'section-title'}, 'Timeline'),
        timeline(report, roles),
        el('h3', {class: 'section-title'}, 'Queries'),
        queryTable(report, roles));
}

function renderCalls() {
    const visible = state.javaReports.filter(report => !state.onlySlowCode || report.view.javaFindings.length > 0);
    if (state.callFollow || !visible.some(report => report.id === state.callSelectedId)) {
        state.callSelectedId = visible.length > 0 ? visible[0].id : null;
    }
    $('call-list').replaceChildren(...visible.map((report, index) => callItem(report, index)));
    $('no-calls').hidden = visible.length > 0;
    $('no-calls').textContent = state.javaReports.length > 0
        ? 'No requests with slow code yet.'
        : 'Waiting for requests or tasks that run your code.';
    const selected = state.javaReports.find(report => report.id === state.callSelectedId) ?? null;
    if ((selected?.id ?? null) !== state.callRenderedId) {
        renderCallDetails(selected);
        state.callRenderedId = selected?.id ?? null;
    }
}

function callItem(report, index) {
    return el('li', {
            class: `request${report.id === state.callSelectedId ? ' selected' : ''}`,
            onclick: () => {
                state.callSelectedId = report.id;
                state.callFollow = index === 0;
                render();
            },
        },
        el('div', {class: 'request-line'},
            el('span', {class: 'method'}, report.method),
            el('span', {class: 'path', title: report.path}, report.path)),
        el('div', {class: 'request-meta'},
            isTask(report)
                ? el('span', {}, 'Task')
                : el('span', {class: report.status >= 400 ? 'status-error' : ''}, String(report.status)),
            el('span', {}, formatMs(report.durationMs)),
            el('span', {}, plural(report.methods, 'method', 'methods')),
            report.view.javaFindings.map(findingLabel)));
}

function renderCallDetails(report) {
    if (!report) {
        $('call-details').replaceChildren(el('div', {class: 'panel'},
            el('p', {class: 'empty', style: {paddingTop: '20px'}}, 'Select a request to see its call tree.')));
        return;
    }
    $('call-details').replaceChildren(
        el('div', {class: 'panel details-header'},
            el('div', {class: 'details-title'},
                el('h1', {}, el('span', {class: 'method'}, report.method), report.path),
                report.view.hasSql
                    ? el('button', {type: 'button', class: 'text-button', onclick: () => openRequest(report.id)},
                        'SQL queries')
                    : null),
            el('dl', {class: 'facts', id: 'call-facts'}, callFacts(report, null))),
        el('h3', {class: 'section-title'}, 'Findings'),
        ...findingCards(report.view.javaFindings, 'No slow code in this request.'),
        el('h3', {class: 'section-title'}, 'Call tree'),
        el('div', {class: 'panel call-tree', id: 'call-tree', 'data-id': report.id},
            el('p', {class: 'empty', style: {paddingTop: '20px'}}, 'Loading the call tree…')),
        el('h3', {class: 'section-title', id: 'method-queries-title'}, 'Queries of a method'),
        el('div', {class: 'panel', id: 'method-queries'},
            el('p', {class: 'empty', style: {paddingTop: '20px'}}, report.view.hasSql
                ? 'Select a method with queries in the call tree to see its SQL here.'
                : 'This request ran no SQL.')),
        el('h3', {class: 'section-title'}, 'Methods by self time'),
        el('div', {class: 'panel', id: 'hot-methods'},
            el('p', {class: 'empty', style: {paddingTop: '20px'}}, 'Loading…')));
    state.callToggles = new Map();
    loadCallTree(report);
}

function callFacts(report, tree) {
    const ownMs = tree && tree.methods ? tree.methods.reduce((sum, node) => sum + node.totalMs, 0) : null;
    return [
        isTask(report) ? fact('Kind', 'Task outside HTTP') : fact('Status', String(report.status)),
        fact('Duration', formatMs(report.durationMs)),
        fact('In your code', ownMs === null ? '…' : formatMs(Math.min(ownMs, report.durationMs))),
        fact('In SQL', formatMs(report.view.dbMs)),
        fact('Methods', String(report.methods)),
        fact('Started', new Date(report.view.start).toLocaleTimeString()),
    ];
}

async function loadCallTree(report) {
    let tree = state.callTrees.get(report.id);
    if (!tree) {
        try {
            const response = await fetch(`/api/requests/${report.id}/calls`);
            tree = response.ok ? await response.json() : {missing: true};
            if (state.callTrees.size >= MAX_REPORTS) {
                state.callTrees.clear();
            }
            state.callTrees.set(report.id, tree);
        } catch (error) {
            tree = {failed: true};
        }
    }
    const container = $('call-tree');
    if (!container || container.dataset.id !== String(report.id)) {
        return;
    }
    const roles = queryRoles(report);
    renderCallTree(report, tree, roles);
    if (tree.methods) {
        $('call-facts').replaceChildren(...callFacts(report, tree));
        renderHotMethods(report, tree);
    } else {
        $('hot-methods').replaceChildren(el('p', {class: 'empty', style: {paddingTop: '20px'}}, '—'));
    }
}

function renderCallTree(report, tree, roles) {
    const container = $('call-tree');
    if (!container || container.dataset.id !== String(report.id)) {
        return;
    }
    const unit = isTask(report) ? 'task' : 'request';
    const message = text => [el('p', {class: 'empty', style: {paddingTop: '20px'}}, text)];
    if (tree.missing || tree.failed) {
        container.replaceChildren(...message(tree.missing
            ? `The agent no longer keeps this ${unit}.`
            : 'Could not load the call tree from the agent.'));
        return;
    }
    if (tree.packages.length === 0) {
        container.replaceChildren(...message('No call tree: QueryScope could not tell which package is your code, or it was '
            + 'turned off. Start the agent with package=com.acme (or --package=com.acme with the launcher).'));
        return;
    }
    if (tree.methods.length === 0) {
        container.replaceChildren(...message(`None of the methods in ${tree.packages.join(', ')} ran in this ${unit}.`));
        return;
    }
    const outsideMs = Math.max(0, report.durationMs - tree.methods.reduce((sum, node) => sum + node.totalMs, 0));
    const root = {
        method: null,
        calls: 1,
        totalMs: report.durationMs,
        selfMs: outsideMs,
        startedBeforeUnit: false,
        queryIndexes: tree.outsideQueryIndexes,
        children: tree.methods,
    };
    const context = {
        report,
        roles,
        duration: Math.max(report.durationMs, 0.001),
        slowMethods: new Set(report.findings.filter(finding => finding.type === 'slowMethod').map(finding => finding.method)),
        rerender: () => renderCallTree(report, tree, roles),
    };
    const rows = [];
    callRows(root, 0, 'root', context, rows);
    const calls = table(
        [
            {title: 'Method', width: 'auto'},
            {title: 'Calls', width: '80px', number: true},
            {title: 'Total', width: '90px', number: true},
            {title: 'Self', width: '90px', number: true},
            {title: 'Queries', width: '80px', number: true},
            {title: 'Share of time', width: '18%'},
        ],
        rows);
    calls.classList.add('call-table');
    container.replaceChildren(...[
        tree.warmUp
            ? el('p', {class: 'call-warm-up'}, `The JVM loaded ${formatCount(tree.classesLoaded)} classes while this `
                + `${unit} ran, so its times include warm-up and slow methods are not reported for it.`)
            : null,
        calls,
        tree.truncated
            ? el('p', {class: 'empty'}, 'The tree stops at 1,000 methods or 100 levels, the rest is counted in the method above.')
            : null,
    ].filter(node => node !== null));
}

function callRows(node, depth, path, context, rows) {
    rows.push(callRow(node, depth, path, context));
    if (isExpanded(node, depth, path, context)) {
        node.children.forEach((child, index) => callRows(child, depth + 1, `${path}/${index}`, context, rows));
    }
}

function callRow(node, depth, path, context) {
    const hasChildren = node.children.length > 0;
    const expanded = hasChildren && isExpanded(node, depth, path, context);
    const queries = node.queryIndexes.length;
    const role = QUERY_ROLE_PRIORITY.find(candidate => node.queryIndexes.some(index => context.roles[index] === candidate));
    const slow = node.method !== null && !node.startedBeforeUnit && context.slowMethods.has(node.method);
    const name = node.method === null
        ? el('span', {class: 'call-unit'}, isTask(context.report)
            ? `Task ${context.report.path}`
            : `${context.report.method} ${context.report.path}`)
        : el('code', {title: node.method}, shortMethod(node.method));
    const properties = {class: ['call-row', queries > 0 ? 'clickable' : '', slow ? 'slow-method' : ''].join(' ')};
    if (queries > 0) {
        properties.onclick = () => showMethodQueries(context.report, node, context.roles);
    }
    return el('tr', properties,
        el('td', {style: {paddingLeft: `${20 + depth * CALL_INDENT}px`}}, el('div', {class: 'call-name'},
            hasChildren
                ? el('button', {
                    class: 'call-toggle',
                    'aria-expanded': String(expanded),
                    'aria-label': expanded ? 'Collapse' : 'Expand',
                    onclick: event => {
                        event.stopPropagation();
                        state.callToggles.set(path, !expanded);
                        context.rerender();
                    },
                }, expanded ? '▾' : '▸')
                : el('span', {class: 'call-toggle'}),
            name,
            node.startedBeforeUnit
                ? el('span', {class: 'call-note', title: 'This method was already running when the task started, '
                        + 'so only the time inside the task is counted.'}, 'already running')
                : null,
            slow ? el('span', {class: 'label slow-method'}, 'Slow code') : null)),
        el('td', {class: 'number'}, formatCount(node.calls)),
        el('td', {class: 'number'}, formatMs(node.totalMs)),
        el('td', {
            class: 'number',
            title: node.method === null ? 'Time outside your methods: the framework, the server and the database driver' : '',
        }, formatMs(node.selfMs)),
        el('td', {class: 'number', title: queries > 0 ? 'Click the row to see these queries below' : ''},
            queries > 0 ? el('span', {class: `call-queries ${role ?? ''}`}, String(queries)) : '—'),
        el('td', {},
            el('div', {class: 'call-share', title: `${wholePercent(node.totalMs / context.duration)} in total, `
                    + `${wholePercent(node.selfMs / context.duration)} in its own code`},
                el('div', {class: 'call-share-total', style: {width: percent(node.totalMs / context.duration)}}),
                el('div', {class: 'call-share-self', style: {width: percent(node.selfMs / context.duration)}}))));
}

function showMethodQueries(report, node, roles) {
    const title = node.method === null ? 'outside your methods' : shortMethod(node.method);
    $('method-queries-title').textContent = `Queries of ${title}`;
    const rows = node.queryIndexes.map(index => {
        const query = report.queries[index];
        return el('tr', {class: roles[index]},
            el('td', {class: 'number'}, String(index + 1)),
            el('td', {class: 'number'}, formatMs(query.durationMs)),
            el('td', {class: 'number'}, query.rows === null || query.rows === undefined ? '—' : formatCount(query.rows)),
            el('td', {class: 'sql', title: oneLine(query.sql)},
                query.failed ? el('span', {class: 'status-error'}, 'Failed ') : null, highlightSql(oneLine(query.sql))),
            el('td', {class: 'params', title: query.parameters || ''}, query.parameters || '—'));
    });
    const queries = table(
        [
            {title: '#', width: '52px', number: true},
            {title: 'Duration', width: '90px', number: true},
            {title: 'Rows', width: '76px', number: true},
            {title: 'SQL', width: 'auto'},
            {title: 'Parameters', width: '18%'},
        ],
        rows);
    queries.classList.add('query-table');
    $('method-queries').replaceChildren(queries);
    $('method-queries-title').scrollIntoView({block: 'start', behavior: 'smooth'});
}

function renderHotMethods(report, tree) {
    const methods = new Map();
    const collect = nodes => nodes.forEach(node => {
        const entry = methods.get(node.method) ?? {method: node.method, calls: 0, selfMs: 0, queries: 0};
        entry.calls += node.calls;
        entry.selfMs += node.startedBeforeUnit ? 0 : node.selfMs;
        entry.queries += node.queryIndexes.length;
        methods.set(node.method, entry);
        collect(node.children);
    });
    collect(tree.methods);
    const duration = Math.max(report.durationMs, 0.001);
    const slow = new Set(report.view.javaFindings.map(finding => finding.method));
    const top = [...methods.values()].sort((a, b) => b.selfMs - a.selfMs).slice(0, HOT_METHODS);
    const hot = table(
        [
            {title: 'Method', width: 'auto'},
            {title: 'Calls', width: '80px', number: true},
            {title: 'Self', width: '90px', number: true},
            {title: 'Queries', width: '80px', number: true},
            {title: 'Share of time', width: '18%'},
        ],
        top.map(entry => el('tr', {class: slow.has(entry.method) ? 'slow-method' : ''},
            el('td', {}, el('div', {class: 'call-name'},
                el('code', {title: entry.method}, shortMethod(entry.method)),
                slow.has(entry.method) ? el('span', {class: 'label slow-method'}, 'Slow code') : null)),
            el('td', {class: 'number'}, formatCount(entry.calls)),
            el('td', {class: 'number'}, formatMs(entry.selfMs)),
            el('td', {class: 'number'}, entry.queries > 0 ? String(entry.queries) : '—'),
            el('td', {},
                el('div', {class: 'call-share', title: `${wholePercent(entry.selfMs / duration)} of the time`},
                    el('div', {class: 'call-share-self', style: {width: percent(entry.selfMs / duration)}}))))));
    hot.classList.add('call-table');
    $('hot-methods').replaceChildren(hot);
}

function isExpanded(node, depth, path, context) {
    if (node.children.length === 0) {
        return false;
    }
    if (state.callToggles.has(path)) {
        return state.callToggles.get(path);
    }
    return depth === 0 || node.totalMs / context.duration >= CALL_EXPAND_SHARE || hasQueries(node);
}

function hasQueries(node) {
    return node.queryIndexes.length > 0 || node.children.some(hasQueries);
}

function highlightQueries(indexes) {
    document.querySelectorAll('tr.highlight').forEach(other => other.classList.remove('highlight'));
    const rows = indexes.map(index => $(`query-${index}`)).filter(row => row !== null);
    rows.forEach(row => row.classList.add('highlight'));
    if (rows.length > 0) {
        rows[0].scrollIntoView({block: 'center', behavior: 'smooth'});
    }
}

function fact(term, value) {
    return el('div', {}, el('dt', {}, term), el('dd', {}, value));
}

function findingCards(findings, emptyText) {
    if (findings.length === 0) {
        return [el('div', {class: 'finding clean'}, emptyText)];
    }
    const cards = {
        nPlusOne: nPlusOneCard,
        slowQuery: slowQueryCard,
        repeatedWrite: repeatedWriteCard,
        duplicateQuery: duplicateQueryCard,
        largeResult: largeResultCard,
        tooManyQueries: tooManyQueriesCard,
        slowMethod: slowMethodCard,
    };
    return findings.filter(finding => cards[finding.type]).map(finding => cards[finding.type](finding));
}

function nPlusOneCard(finding) {
    return el('div', {class: 'finding n-plus-one'},
        el('div', {class: 'finding-title'}, `N+1 query: the same SELECT ran ${finding.executions} times`),
        el('code', {class: 'sql-block'}, highlightSql(finding.sql)),
        el('div', {class: 'finding-meta'}, callSiteLine(finding.callSite), ` · ${formatMs(finding.totalMs)} in total`),
        el('div', {class: 'finding-hint'},
            'Load the rows in one query (join fetch, @EntityGraph or batch fetching) instead of one query per parent row.'));
}

function slowQueryCard(finding) {
    return el('div', {class: 'finding slow'},
        el('div', {class: 'finding-title'}, `Slow query: ${formatMs(finding.durationMs)}`),
        el('code', {class: 'sql-block'}, highlightSql(finding.sql)),
        el('div', {class: 'finding-meta'}, callSiteLine(finding.callSite), ` · query #${finding.queryIndex + 1}`),
        el('div', {class: 'finding-hint'},
            'Check the query plan (EXPLAIN) for full table scans, a missing index or a join on an unindexed column.'));
}

function repeatedWriteCard(finding) {
    return el('div', {class: 'finding repeated-write'},
        el('div', {class: 'finding-title'}, `Unbatched writes: the same statement ran ${finding.executions} times`),
        el('code', {class: 'sql-block'}, highlightSql(finding.sql)),
        el('div', {class: 'finding-meta'}, callSiteLine(finding.callSite), ` · ${formatMs(finding.totalMs)} in total`),
        el('div', {class: 'finding-hint'},
            'Send them as one batch: addBatch and executeBatch, or hibernate.jdbc.batch_size with sequence-generated ids.'));
}

function duplicateQueryCard(finding) {
    return el('div', {class: 'finding duplicate'},
        el('div', {class: 'finding-title'},
            `Duplicate query: the same SELECT with the same parameters ran ${finding.executions} times`),
        el('code', {class: 'sql-block'}, highlightSql(finding.sql)),
        el('div', {class: 'finding-meta'}, `Parameters ${finding.parameters || 'none'} · `, callSiteLine(finding.callSite)),
        el('div', {class: 'finding-hint'}, 'Reuse the first result, pass it along, or cache it for the request.'));
}

function largeResultCard(finding) {
    return el('div', {class: 'finding large-result'},
        el('div', {class: 'finding-title'}, `Large result: ${formatCount(finding.rows)} rows`),
        el('code', {class: 'sql-block'}, highlightSql(finding.sql)),
        el('div', {class: 'finding-meta'}, callSiteLine(finding.callSite), ` · query #${finding.queryIndex + 1}`),
        el('div', {class: 'finding-hint'},
            'Fetch only what the response needs: paginate (LIMIT, Pageable), filter in SQL or select fewer columns.'));
}

function tooManyQueriesCard(finding) {
    const share = finding.durationMs > 0 ? Math.min(100, Math.round(finding.databaseMs / finding.durationMs * 100)) : 0;
    return el('div', {class: 'finding too-many'},
        el('div', {class: 'finding-title'}, `Too many queries: ${finding.queries} in one unit of work`),
        el('div', {class: 'finding-meta'},
            `${share}% of the time in the database (${formatMs(finding.databaseMs)} of ${formatMs(finding.durationMs)})`
            + ` · the limit is ${finding.limit}`),
        el('div', {class: 'finding-hint'},
            'Look for N+1 patterns and repeated lookups, and load related data with joins instead of separate queries.'));
}

function slowMethodCard(finding) {
    const share = finding.durationMs > 0 ? Math.min(100, Math.round(finding.ownMs / finding.durationMs * 100)) : 0;
    return el('div', {class: 'finding slow-method'},
        el('div', {class: 'finding-title'}, `Slow method: ${formatMs(finding.ownMs)} in its own code, outside SQL`),
        el('div', {class: 'finding-meta'},
            el('code', {title: finding.method}, shortMethod(finding.method)),
            ` · ${plural(finding.calls, 'call', 'calls')} · ${share}% of the time`),
        el('div', {class: 'finding-hint'},
            'The time goes to Java, not to the database: this method and the library code it calls. Look for loops that '
            + 'build strings, heavy mapping or serialization, and blocking calls. The call tree below shows where.'));
}

function findingArea(finding) {
    return FINDING_TYPES[finding.type]?.area ?? 'sql';
}

function callSiteLine(callSite) {
    if (!callSite) {
        return 'Location unknown';
    }
    return ['Called from ', el('code', {title: callSite}, shortCallSite(callSite))];
}

function timeline(report, roles) {
    const end = Math.max(report.durationMs, ...report.queries.map(query => query.offsetMs + query.durationMs), 0.001);
    const rows = report.queries.map((query, index) => el('div', {
            class: 'bar-row',
            title: `#${index + 1} · ${formatMs(query.durationMs)} at +${formatMs(query.offsetMs)}\n${oneLine(query.sql)}`,
            onclick: () => highlightQuery(index),
        },
        el('span', {class: 'bar-index'}, String(index + 1)),
        el('div', {class: 'bar-track'},
            el('div', {
                class: ['bar', roles[index], query.failed ? 'failed' : ''].join(' '),
                style: {left: percent(query.offsetMs / end), width: percent(query.durationMs / end)},
            }))));
    return el('div', {class: 'panel timeline'},
        el('div', {class: 'axis'}, [0, 0.25, 0.5, 0.75, 1].map(fraction => el('span', {}, formatMs(end * fraction)))),
        rows);
}

function queryTable(report, roles) {
    const queryRows = report.queries.map((query, index) => el('tr', {id: `query-${index}`, class: roles[index]},
        el('td', {class: 'number'}, String(index + 1)),
        el('td', {class: 'number'}, `+${formatMs(query.offsetMs)}`),
        el('td', {class: 'number'}, formatMs(query.durationMs)),
        el('td', {class: 'number'}, query.rows === null || query.rows === undefined ? '—' : formatCount(query.rows)),
        el('td', {class: 'sql', title: oneLine(query.sql)},
            query.failed ? el('span', {class: 'status-error'}, 'Failed ') : null, highlightSql(oneLine(query.sql))),
        el('td', {class: 'params', title: query.parameters || ''}, query.parameters || '—'),
        el('td', {class: 'call-site', title: query.callSite ?? ''}, query.callSite ? shortCallSite(query.callSite) : '—')));
    const queries = table(
        [
            {title: '#', width: '52px', number: true},
            {title: 'Start', width: '90px', number: true},
            {title: 'Duration', width: '90px', number: true},
            {title: 'Rows', width: '76px', number: true},
            {title: 'SQL', width: 'auto'},
            {title: 'Parameters', width: '15%'},
            {title: 'Called from', width: '22%'},
        ],
        queryRows);
    queries.classList.add('query-table');
    return el('div', {class: 'panel'}, queries);
}

function queryRoles(report) {
    const roles = report.queries.map(() => '');
    for (const type of ROLE_ORDER) {
        for (const finding of report.findings.filter(candidate => candidate.type === type)) {
            const indexes = finding.queryIndexes ?? [finding.queryIndex];
            indexes.forEach(index => roles[index] = FINDING_TYPES[type].css);
        }
    }
    return roles;
}

function highlightQuery(index) {
    highlightQueries([index]);
}

function highlightSql(sql) {
    const tokens = sql.match(/'(?:[^']|'')*'?|"[^"]*"?|`[^`]*`?|\d+(?:\.\d+)?|\?|[A-Za-z_][\w$]*|\s+|./g) ?? [];
    return tokens.map((token, index) => {
        if (token.startsWith("'")) {
            return el('span', {class: 'sql-string'}, token);
        }
        if (/^\d/.test(token)) {
            return el('span', {class: 'sql-number'}, token);
        }
        if (token === '?') {
            return el('span', {class: 'sql-param'}, token);
        }
        if (/^[A-Za-z_]/.test(token)) {
            if (SQL_KEYWORDS.has(token.toLowerCase())) {
                return el('span', {class: 'sql-keyword'}, token);
            }
            if (tokens[index + 1] === '(') {
                return el('span', {class: 'sql-function'}, token);
            }
        }
        return token;
    });
}

function findingLabel(finding) {
    const type = FINDING_TYPES[finding.type];
    return el('span', {class: `label ${type ? type.css : ''}`}, findingText(finding));
}

function findingText(finding) {
    const type = FINDING_TYPES[finding.type];
    return type ? type.label(finding) : finding.type;
}

function isTask(report) {
    return report.kind === 'task';
}

function findingKey(report, finding) {
    return `${finding.type}|${finding.sql ?? finding.method ?? ''}|`
        + `${finding.callSite ?? `${report.method} ${report.view.endpointPath}`}`;
}

function findingLocation(finding) {
    if (finding.callSite) {
        return shortCallSite(finding.callSite);
    }
    return finding.method ? shortMethod(finding.method) : 'Location unknown';
}

function parseInstant(text) {
    return Date.parse(text.replace(/(\.\d{3})\d+/, '$1'));
}

function endpointPath(path) {
    return path.split('?')[0]
        .replace(/\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?=\/|$)/gi, '/{uuid}')
        .replace(/\/\d+(?=\/|$)/g, '/{id}');
}

function shortCallSite(callSite) {
    const open = callSite.indexOf('(');
    const qualified = open < 0 ? callSite : callSite.slice(0, open);
    return qualified.split('.').slice(-2).join('.') + (open < 0 ? '' : callSite.slice(open));
}

function shortMethod(method) {
    return method.split('.').slice(-2).join('.');
}

function formatCount(count) {
    return count.toLocaleString('en-US');
}

function oneLine(sql) {
    return sql.replace(/\s+/g, ' ').trim();
}

function formatMs(ms) {
    if (ms >= 10_000) {
        return `${(ms / 1000).toFixed(1)} s`;
    }
    if (ms >= 1000) {
        return `${(ms / 1000).toFixed(2)} s`;
    }
    if (ms >= 100) {
        return `${ms.toFixed(0)} ms`;
    }
    return `${ms.toFixed(ms >= 10 ? 1 : 2)} ms`;
}

function formatSeconds(ms) {
    return ms >= 60_000 ? `${Math.round(ms / 60_000 * 10) / 10} min` : `${Math.round(ms / 1000)} s`;
}

function ago(ms) {
    const seconds = Math.max(0, Math.round(ms / 1000));
    if (seconds < 60) {
        return `${seconds} s ago`;
    }
    const minutes = Math.round(seconds / 60);
    return minutes < 60 ? `${minutes} min ago` : `${Math.round(minutes / 60)} h ago`;
}

function percent(fraction) {
    return `${Math.min(100, Math.max(0, fraction * 100)).toFixed(3)}%`;
}

function wholePercent(fraction) {
    return `${Math.round(Math.min(1, Math.max(0, fraction)) * 100)}%`;
}

function plural(count, one, many) {
    return `${count} ${count === 1 ? one : many}`;
}

function prepare(canvas) {
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    if (width === 0 || height === 0) {
        return null;
    }
    const ratio = window.devicePixelRatio || 1;
    if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) {
        canvas.width = Math.round(width * ratio);
        canvas.height = Math.round(height * ratio);
    }
    const context = canvas.getContext('2d');
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    context.clearRect(0, 0, width, height);
    context.size = {width, height};
    return context;
}

function roundedRect(context, x, y, width, height, radius) {
    const r = Math.min(radius, width / 2, height / 2);
    context.beginPath();
    context.moveTo(x + r, y);
    context.arcTo(x + width, y, x + width, y + height, r);
    context.arcTo(x + width, y + height, x, y + height, r);
    context.arcTo(x, y + height, x, y, r);
    context.arcTo(x, y, x + width, y, r);
    context.closePath();
}

function el(tag, properties, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(properties)) {
        if (key === 'class') {
            node.className = value;
        } else if (key === 'style') {
            Object.assign(node.style, value);
        } else if (key.startsWith('on')) {
            node.addEventListener(key.slice(2), value);
        } else {
            node.setAttribute(key, value);
        }
    }
    for (const child of children.flat(Infinity)) {
        if (child !== null && child !== undefined) {
            node.append(child);
        }
    }
    return node;
}
