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
    nPlusOne: {css: 'n-plus-one', name: 'N+1', label: finding => `N+1 ×${finding.executions}`},
    slowQuery: {css: 'slow', name: 'Slow', label: finding => `Slow ${formatMs(finding.durationMs)}`},
    repeatedWrite: {css: 'repeated-write', name: 'Writes', label: finding => `Writes ×${finding.executions}`},
    duplicateQuery: {css: 'duplicate', name: 'Duplicate', label: finding => `Duplicate ×${finding.executions}`},
    largeResult: {css: 'large-result', name: 'Rows', label: finding => `${formatCount(finding.rows)} rows`},
    tooManyQueries: {css: 'too-many', name: 'Queries', label: finding => `${finding.queries} queries`},
};
const ROLE_ORDER = ['duplicateQuery', 'repeatedWrite', 'largeResult', 'nPlusOne', 'slowQuery'];

const state = {
    reports: [],
    pending: [],
    selectedId: null,
    follow: true,
    onlyFindings: false,
    connected: false,
    paused: false,
    pausedAt: 0,
    tab: 'live',
    windowMs: WINDOWS[0].ms,
    renderedId: undefined,
    laneEnds: [],
    hits: [],
    hoverId: null,
    stats: null,
    feedTimes: [],
    announced: new Set(),
    snackTimer: 0,
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
    button.addEventListener('click', () => selectTab(button.dataset.tab));
}
window.addEventListener('hashchange', () => selectTab(tabFromHash()));
$('pause').addEventListener('click', togglePause);
$('clear').addEventListener('click', clearAll);
$('only-findings').addEventListener('change', event => {
    state.onlyFindings = event.target.checked;
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
selectTab(tabFromHash());
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
            state.reports = newestUnique([...buffered.reverse(), ...stored]);
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
    state.reports = newestUnique([report, ...state.reports]);
    assignLane(report);
    announce(report);
    render();
}

function enrich(report) {
    const start = parseInstant(report.startedAt);
    const types = new Set(report.findings.map(finding => finding.type));
    report.view = {
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

function newestUnique(reports) {
    const seen = new Set();
    return reports
        .filter(report => !seen.has(report.id) && seen.add(report.id))
        .slice(0, MAX_REPORTS);
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
    state.reports = [];
    state.pending = [];
    state.selectedId = null;
    state.follow = true;
    state.laneEnds = [];
    updatePauseButton();
    render();
}

function tabFromHash() {
    return location.hash === '#requests' ? 'requests' : 'live';
}

function selectTab(tab) {
    state.tab = tab;
    for (const button of document.querySelectorAll('.tab')) {
        button.setAttribute('aria-selected', String(button.dataset.tab === tab));
    }
    $('view-live').hidden = tab !== 'live';
    $('view-requests').hidden = tab !== 'requests';
    if (location.hash !== `#${tab}`) {
        history.replaceState(null, '', `#${tab}`);
    }
}

function openRequest(id) {
    state.selectedId = id;
    state.follow = false;
    selectTab('requests');
    render();
}

function render() {
    $('request-count').textContent = String(state.reports.length);
    $('stream-empty').hidden = state.reports.length > 0;
    renderStats();
    renderEndpoints();
    renderFeed();
    renderRequests();
}

function tick() {
    renderStats();
    const current = now();
    for (const {node, end} of state.feedTimes) {
        node.textContent = ago(current - end);
    }
}

function frame() {
    if (state.tab === 'live') {
        drawStream();
    }
    requestAnimationFrame(frame);
}

function renderStats() {
    const end = now();
    const from = end - KPI_WINDOW_MS;
    const recent = state.reports.filter(report => report.view.end > from && report.view.end <= end);
    const queries = recent.reduce((sum, report) => sum + report.queries.length, 0);
    const duration = recent.reduce((sum, report) => sum + report.durationMs, 0);
    const db = recent.reduce((sum, report) => sum + report.view.dbMs, 0);
    const withNPlusOne = state.reports.filter(report => report.view.types.has('nPlusOne'));
    const withSlow = state.reports.filter(report => report.view.types.has('slowQuery'));
    const dbSeries = series(end, report => report.view.dbMs);
    const durationSeries = series(end, report => report.durationMs);
    const stats = [
        {key: 'requests', label: 'Requests per minute', value: recent.length,
            sub: `${plural(state.reports.length, 'request', 'requests')} kept`, series: series(end, () => 1)},
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
    for (const report of state.reports) {
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
    [...state.reports].sort((a, b) => a.view.start - b.view.start).forEach(assignLane);
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
    for (const report of [...state.reports].reverse()) {
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
        report.findings.length > 0 ? el('div', {class: 'tooltip-meta'}, report.findings.map(findingLabel)) : null,
    ].filter(Boolean));
    tooltip.hidden = false;
    const left = Math.min(mouseX + 14, bounds.width - tooltip.offsetWidth - 8);
    const top = mouseY + 16 + tooltip.offsetHeight > bounds.height ? mouseY - tooltip.offsetHeight - 10 : mouseY + 16;
    tooltip.style.left = `${Math.max(8, left)}px`;
    tooltip.style.top = `${Math.max(8, top)}px`;
}

function renderEndpoints() {
    const groups = new Map();
    for (const report of state.reports) {
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
    for (const report of state.reports) {
        for (const finding of report.findings) {
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
                    el('div', {class: 'path', title: finding.callSite ?? ''},
                        el('span', {class: 'muted'},
                            finding.callSite ? shortCallSite(finding.callSite) : 'Location unknown'))));
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
        openRequest(report.id);
    };
    snackbar.hidden = false;
    clearTimeout(state.snackTimer);
    state.snackTimer = setTimeout(() => snackbar.hidden = true, 6000);
}

function renderRequests() {
    const visible = state.reports.filter(report => !state.onlyFindings || report.findings.length > 0);
    if (state.follow || !visible.some(report => report.id === state.selectedId)) {
        state.selectedId = visible.length > 0 ? visible[0].id : null;
    }
    $('request-list').replaceChildren(...visible.map((report, index) => requestItem(report, index)));
    $('no-requests').hidden = visible.length > 0;
    $('no-requests').textContent = state.reports.length > 0
        ? 'No requests with findings yet.'
        : 'Waiting for requests or tasks that run SQL.';
    const selected = state.reports.find(report => report.id === state.selectedId) ?? null;
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
            report.findings.map(findingLabel)));
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
            el('h1', {}, el('span', {class: 'method'}, report.method), report.path),
            el('dl', {class: 'facts'},
                isTask(report) ? fact('Kind', 'Task outside HTTP') : fact('Status', String(report.status)),
                fact('Duration', formatMs(report.durationMs)),
                fact('Queries', String(report.queries.length)),
                fact('Time in database', formatMs(report.view.dbMs)),
                fact('Started', new Date(report.view.start).toLocaleTimeString()))),
        el('h3', {class: 'section-title'}, 'Findings'),
        ...findingCards(report),
        el('h3', {class: 'section-title'}, 'Timeline'),
        timeline(report, roles),
        el('h3', {class: 'section-title'}, 'Queries'),
        queryTable(report, roles));
}

function fact(term, value) {
    return el('div', {}, el('dt', {}, term), el('dd', {}, value));
}

function findingCards(report) {
    if (report.findings.length === 0) {
        return [el('div', {class: 'finding clean'}, 'No findings in this request.')];
    }
    const cards = {
        nPlusOne: nPlusOneCard,
        slowQuery: slowQueryCard,
        repeatedWrite: repeatedWriteCard,
        duplicateQuery: duplicateQueryCard,
        largeResult: largeResultCard,
        tooManyQueries: tooManyQueriesCard,
    };
    return report.findings.filter(finding => cards[finding.type]).map(finding => cards[finding.type](finding));
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
    const row = $(`query-${index}`);
    document.querySelectorAll('tr.highlight').forEach(other => other.classList.remove('highlight'));
    row.classList.add('highlight');
    row.scrollIntoView({block: 'center', behavior: 'smooth'});
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
    return `${finding.type}|${finding.sql ?? ''}|${finding.callSite ?? `${report.method} ${report.view.endpointPath}`}`;
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
