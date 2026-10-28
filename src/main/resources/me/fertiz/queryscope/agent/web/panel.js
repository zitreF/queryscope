const MAX_REPORTS = 200;
const KPI_WINDOW_MS = 60_000;
const SPARK_BUCKETS = 30;
const FEED_SIZE = 10;
const SNACK_MS = 6000;
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
    nPlusOne: {
        area: 'sql', css: 'n-plus-one', name: 'N+1', label: finding => `N+1 ×${finding.executions}`,
        title: finding => `N+1 query: the same SELECT ran ${finding.executions} times`,
        meta: finding => [callSiteLine(finding.callSite), ` · ${formatMs(finding.totalMs)} in total`],
        hint: 'Load the rows in one query (join fetch, @EntityGraph or batch fetching) instead of one query per parent row.',
    },
    slowQuery: {
        area: 'sql', css: 'slow', name: 'Slow', label: finding => `Slow ${formatMs(finding.durationMs)}`,
        title: finding => `Slow query: ${formatMs(finding.durationMs)}`,
        meta: finding => [callSiteLine(finding.callSite), ` · query #${finding.queryIndex + 1}`],
        hint: 'Check the query plan (EXPLAIN) for full table scans, a missing index or a join on an unindexed column.',
    },
    repeatedWrite: {
        area: 'sql', css: 'repeated-write', name: 'Writes', label: finding => `Writes ×${finding.executions}`,
        title: finding => `Unbatched writes: the same statement ran ${finding.executions} times`,
        meta: finding => [callSiteLine(finding.callSite), ` · ${formatMs(finding.totalMs)} in total`],
        hint: 'Send them as one batch: addBatch and executeBatch, or hibernate.jdbc.batch_size with sequence-generated ids.',
    },
    duplicateQuery: {
        area: 'sql', css: 'duplicate', name: 'Duplicate', label: finding => `Duplicate ×${finding.executions}`,
        title: finding => `Duplicate query: the same SELECT with the same parameters ran ${finding.executions} times`,
        meta: finding => [`Parameters ${finding.parameters || 'none'} · `, callSiteLine(finding.callSite)],
        hint: 'Reuse the first result, pass it along, or cache it for the request.',
    },
    largeResult: {
        area: 'sql', css: 'large-result', name: 'Rows', label: finding => `${formatCount(finding.rows)} rows`,
        title: finding => `Large result: ${formatCount(finding.rows)} rows`,
        meta: finding => [callSiteLine(finding.callSite), ` · query #${finding.queryIndex + 1}`],
        hint: 'Fetch only what the response needs: paginate (LIMIT, Pageable), filter in SQL or select fewer columns.',
    },
    tooManyQueries: {
        area: 'sql', css: 'too-many', name: 'Queries', label: finding => `${finding.queries} queries`,
        title: finding => `Too many queries: ${finding.queries} in one unit of work`,
        meta: finding => `${share(finding.databaseMs, finding.durationMs)} of the time in the database`
            + ` (${formatMs(finding.databaseMs)} of ${formatMs(finding.durationMs)}) · the limit is ${finding.limit}`,
        hint: 'Look for N+1 patterns and repeated lookups, and load related data with joins instead of separate queries.',
    },
    slowMethod: {
        area: 'java', css: 'slow-method', name: 'Code', label: finding => `Slow code ${formatMs(finding.ownMs)}`,
        title: finding => `Slow method: ${formatMs(finding.ownMs)} in its own code, outside SQL`,
        meta: finding => [el('code', {title: finding.method}, shortMethod(finding.method)),
            ` · ${plural(finding.calls, 'call', 'calls')} · ${share(finding.ownMs, finding.durationMs)} of the time`],
        hint: 'The time goes to Java, not to the database: this method and the library code it calls. Look for loops that '
            + 'build strings, heavy mapping or serialization, and blocking calls. The call tree below shows where.',
    },
};
const VIEWS = {
    'sql/live': 'view-live',
    'sql/requests': 'view-requests',
    'java/profile': 'view-profile',
    'java/calls': 'view-calls',
};
const PROFILE_WINDOWS = [
    {label: '1 min', value: 60},
    {label: '5 min', value: 300},
    {label: '15 min', value: 900},
];
const PROFILE_REFRESH_MS = 5000;
const FLAME_ROW = 22;
const FLAME_MAX_NODES = 8000;
const FLAME_MIN_PX = 3;
const FLAME_LABEL_PX = 40;
const SPECIAL_FRAMES = new Set(['all', '(outside your code)', '(truncated stack)']);
const JDK_PREFIXES = ['java.', 'javax.', 'jdk.', 'sun.', 'com.sun.'];
const HOT_ROWS = 30;
const LEGACY_VIEWS = {live: 'sql/live', requests: 'sql/requests'};
const HOT_METHODS = 15;
const CALL_INDENT = 18;
const CALL_EXPAND_SHARE = 0.05;
const ROLE_PRIORITY = ['nPlusOne', 'slowQuery', 'repeatedWrite', 'largeResult', 'duplicateQuery'];

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
    lastViews: {sql: 'sql/live', java: 'java/profile'},
    profile: {
        seconds: 300,
        thread: 'all',
        frames: 'app',
        framesChosen: false,
        request: null,
        samples: 'cpu',
        hotMode: 'all',
        scope: null,
        focus: [],
        search: '',
        data: null,
        layout: [],
        loadedAt: 0,
    },
    windowMs: WINDOWS[0].ms,
    renderedId: undefined,
    laneEnds: [],
    hits: [],
    hoverId: null,
    stats: null,
    feedTimes: [],
    announced: new Set(),
    callTrees: new Map(),
    callToggles: new Map(),
};

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
$('profile-search').addEventListener('input', event => {
    state.profile.search = event.target.value.trim();
    renderFlame();
});
$('profile-thread').addEventListener('change', event => {
    state.profile.thread = event.target.value;
    state.profile.focus = [];
    loadProfile();
});
$('profile-reset').addEventListener('click', () => {
    state.profile.focus = [];
    renderFlame();
});
$('flame').addEventListener('mousemove', hoverFlame);
$('flame').addEventListener('mouseleave', () => $('flame-tooltip').hidden = true);
$('flame').addEventListener('click', zoomFlame);
window.addEventListener('resize', () => {
    if (state.view === 'java/profile') {
        renderFlame();
    }
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
renderProfileControls();
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
        } catch (error) {
            setConnection(false);
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
    state.announced.clear();
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
    if (view === 'java/profile') {
        loadProfile();
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

function openProfile(report) {
    state.profile.scope = {
        id: report.id,
        kind: 'request',
        label: `${report.method} ${report.path}`,
        endpoint: isTask(report) ? `Task ${report.path}` : `${report.method} ${report.view.endpointPath}`,
    };
    state.profile.focus = [];
    state.profile.data = null;
    renderProfileControls();
    selectView('java/profile');
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
    if (state.view === 'java/profile' && !state.paused && state.profile.scope === null
        && state.profile.request === null && Date.now() - state.profile.loadedAt >= PROFILE_REFRESH_MS) {
        loadProfile();
    }
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

function renderProfileControls() {
    const profile = state.profile;
    segmented($('profile-window'), PROFILE_WINDOWS, profile.seconds, value => {
        profile.seconds = value;
        loadProfile();
    });
    segmented($('profile-frames'), [{label: 'All frames', value: 'all'}, {label: 'Your code', value: 'app'}],
        profile.frames, value => {
            profile.frames = value;
            profile.framesChosen = true;
            profile.focus = [];
            loadProfile();
        });
    segmented($('profile-samples'), [{label: 'CPU', value: 'cpu'}, {label: 'CPU and native', value: 'all'}],
        profile.samples, value => {
            profile.samples = value;
            profile.focus = [];
            loadProfile();
        });
    segmented($('hot-mode'), [{label: 'All methods', value: 'all'}, {label: 'Your methods', value: 'app'}],
        profile.hotMode, value => {
            profile.hotMode = value;
            renderHotList();
        });
    $('profile-window').hidden = profile.scope !== null;
}

function segmented(container, options, current, onSelect) {
    container.replaceChildren(...options.map(option => el('button', {
        type: 'button',
        class: 'segment',
        'aria-pressed': String(option.value === current),
        onclick: () => {
            onSelect(option.value);
            renderProfileControls();
            renderProfileScope();
        },
    }, option.label)));
}

async function loadProfile() {
    const profile = state.profile;
    profile.request?.abort();
    const request = profile.request = new AbortController();
    const parameters = new URLSearchParams({thread: profile.thread, frames: profile.frames, samples: profile.samples});
    if (profile.scope) {
        parameters.set('request', String(profile.scope.id));
        parameters.set('scope', profile.scope.kind);
    } else {
        parameters.set('window', String(profile.seconds));
    }
    let data;
    try {
        const response = await fetch(`api/profile?${parameters}`, {signal: request.signal});
        data = response.ok ? linkParents(await response.json()) : {missing: true};
    } catch (error) {
        data = {failed: true};
    }
    if (request.signal.aborted) {
        return;
    }
    profile.request = null;
    profile.data = data;
    profile.loadedAt = Date.now();
    if (profile.frames === 'app' && !profile.framesChosen && profile.data.packages && profile.data.packages.length === 0) {
        profile.frames = 'all';
        renderProfileControls();
        loadProfile();
        return;
    }
    renderProfile();
}

function linkParents(data) {
    const link = (node, parent) => {
        node.parent = parent;
        node.children.forEach(child => link(child, node));
    };
    if (data.root) {
        link(data.root, null);
    }
    return data;
}

function renderProfile() {
    renderProfileScope();
    renderThreadOptions();
    $('profile-status').textContent = profileStatus(state.profile.data);
    renderFlame();
    renderHotList();
}

function profileStatus(data) {
    if (!data) {
        return 'Loading samples…';
    }
    if (data.missing) {
        return 'The agent no longer keeps this request.';
    }
    if (data.failed) {
        return 'Could not load samples from the agent.';
    }
    if (!data.source) {
        return 'Sampling is off or still starting. It runs unless the agent is started with sampling=false.';
    }
    const range = PROFILE_WINDOWS.find(option => option.value === state.profile.seconds);
    return `${plural(data.samples, 'sample', 'samples')} from ${data.source}, one every ${data.intervalMs} ms`
        + (state.profile.scope ? '' : `, in the last ${range ? range.label : `${state.profile.seconds} s`}`)
        + (data.agentSamples > 0 ? `. QueryScope itself took ${formatCount(data.agentSamples)} more.` : '');
}

function renderThreadOptions() {
    const data = state.profile.data;
    const threads = data && data.threads ? data.threads : [];
    const options = [el('option', {value: 'all'}, 'All threads')];
    for (const thread of threads) {
        options.push(el('option', {value: String(thread.id)}, `${thread.name} · ${formatCount(thread.samples)}`));
    }
    if (state.profile.thread !== 'all' && !threads.some(thread => String(thread.id) === state.profile.thread)) {
        options.push(el('option', {value: state.profile.thread}, `thread ${state.profile.thread} · 0`));
    }
    $('profile-thread').replaceChildren(...options);
    $('profile-thread').value = state.profile.thread;
}

function renderProfileScope() {
    const scope = state.profile.scope;
    const box = $('profile-scope');
    box.hidden = scope === null;
    if (!scope) {
        return;
    }
    const choice = el('div', {class: 'segmented', role: 'group', 'aria-label': 'Scope'});
    box.replaceChildren(
        el('span', {class: 'scope-label'}, scope.kind === 'request'
            ? `Samples of one call of ${scope.label}`
            : `Samples of every stored call of ${scope.endpoint}`),
        choice,
        el('button', {
            type: 'button',
            class: 'text-button',
            onclick: () => {
                state.profile.scope = null;
                state.profile.focus = [];
                renderProfileControls();
                loadProfile();
            },
        }, 'Whole app'));
    segmented(choice, [{label: 'This call', value: 'request'}, {label: 'All calls', value: 'endpoint'}], scope.kind,
        value => {
            scope.kind = value;
            state.profile.focus = [];
            loadProfile();
        });
}

function renderFlame() {
    const container = $('flame');
    const data = state.profile.data;
    if (!data || !data.root || data.samples === 0) {
        container.style.height = '';
        container.replaceChildren(el('p', {class: 'empty'}, emptyFlameText(data)));
        $('profile-reset').hidden = true;
        $('profile-zoom').textContent = '';
        return;
    }
    const width = container.clientWidth || 1000;
    const focus = findFocus(data.root, state.profile.focus);
    const chain = [];
    for (let node = focus.parent; node !== null; node = node.parent) {
        chain.unshift(node);
    }
    const layout = chain.map((node, depth) => ({node, x: 0, width: 1, depth, ancestor: true}));
    layoutFlame(focus, 0, 1, chain.length, width, layout);
    state.profile.layout = layout;
    const search = state.profile.search.toLowerCase();
    const rows = Math.max(...layout.map(item => item.depth)) + 1;
    container.style.height = `${rows * FLAME_ROW}px`;
    container.replaceChildren(...layout.map((item, index) => {
        const matches = search !== '' && item.node.method.toLowerCase().includes(search);
        return el('div', {
            class: ['flame-node', frameKind(item.node), item.ancestor ? 'ancestor' : '', matches ? 'match' : '',
                search !== '' && !matches ? 'faded' : ''].join(' '),
            'data-index': String(index),
            style: {left: percent(item.x), width: percent(item.width), top: `${item.depth * FLAME_ROW}px`},
        }, item.width * width >= FLAME_LABEL_PX ? frameLabel(item.node) : null);
    }));
    $('profile-reset').hidden = state.profile.focus.length === 0;
    $('profile-zoom').textContent = focus === data.root ? '' : `Zoomed into ${frameLabel(focus)}`;
}

function layoutFlame(node, x, width, depth, pixels, layout) {
    if (layout.length >= FLAME_MAX_NODES) {
        return;
    }
    layout.push({node, x, width, depth, ancestor: false});
    let offset = x;
    for (const child of node.children) {
        const childWidth = width * child.total / node.total;
        if (childWidth * pixels >= FLAME_MIN_PX) {
            layoutFlame(child, offset, childWidth, depth + 1, pixels, layout);
        }
        offset += childWidth;
    }
}

function emptyFlameText(data) {
    if (!data) {
        return 'Loading samples…';
    }
    if (data.missing || data.failed || !data.source) {
        return profileStatus(data);
    }
    if (state.profile.scope && state.profile.scope.kind === 'request') {
        return `This call ran too briefly to be sampled, one sample is taken every ${data.intervalMs} ms. `
            + 'Choose all calls to see every stored call of this endpoint together.';
    }
    return 'No samples yet. Use the app, or choose a longer time window.';
}

function findFocus(root, path) {
    let node = root;
    for (const method of path) {
        const child = node.children.find(candidate => candidate.method === method);
        if (!child) {
            break;
        }
        node = child;
    }
    return node;
}

function pathOf(node) {
    const path = [];
    for (let current = node; current.parent !== null; current = current.parent) {
        path.unshift(current.method);
    }
    return path;
}

function zoomFlame(event) {
    const target = event.target.closest('.flame-node');
    if (!target) {
        return;
    }
    state.profile.focus = pathOf(state.profile.layout[Number(target.dataset.index)].node);
    $('flame-tooltip').hidden = true;
    renderFlame();
}

function hoverFlame(event) {
    const tooltip = $('flame-tooltip');
    const target = event.target.closest('.flame-node');
    if (!target) {
        tooltip.hidden = true;
        return;
    }
    const node = state.profile.layout[Number(target.dataset.index)].node;
    const samples = Math.max(state.profile.data.samples, 1);
    const kind = {app: 'Your code', lib: 'Library or framework', jdk: 'JDK', special: ''}[frameKind(node)];
    tooltip.replaceChildren(...[
        el('div', {class: 'tooltip-path'}, node.method),
        el('div', {class: 'tooltip-meta'}, [
            `${formatCount(node.total)} ${node.total === 1 ? 'sample' : 'samples'}, ${wholePercent(node.total / samples)}`,
            node.self > 0 ? `${formatCount(node.self)} on its own line` : null,
            kind || null,
        ].filter(Boolean).join(' · ')),
        el('div', {class: 'tooltip-meta'}, 'Click to zoom in'),
    ]);
    tooltip.hidden = false;
    const bounds = $('flame').getBoundingClientRect();
    const mouseX = event.clientX - bounds.left;
    const mouseY = event.clientY - bounds.top;
    tooltip.style.left = `${Math.max(0, Math.min(mouseX + 14, bounds.width - tooltip.offsetWidth))}px`;
    tooltip.style.top = `${mouseY + 18}px`;
}

function frameKind(node) {
    if (SPECIAL_FRAMES.has(node.method)) {
        return 'special';
    }
    if (node.app) {
        return 'app';
    }
    return JDK_PREFIXES.some(prefix => node.method.startsWith(prefix)) ? 'jdk' : 'lib';
}

function frameLabel(node) {
    return SPECIAL_FRAMES.has(node.method) ? node.method : shortMethod(node.method);
}

function renderHotList() {
    const data = state.profile.data;
    const yours = state.profile.hotMode === 'app';
    $('hot-hint').textContent = yours
        ? 'Your methods by the samples taken in them or in library code they call'
        : 'Methods by the samples taken while they were running their own code';
    if (!data || !data.hot || data.samples === 0) {
        $('hot-list').replaceChildren(el('p', {class: 'empty'}, 'No samples yet.'));
        return;
    }
    const methods = yours
        ? data.hot.filter(method => method.own > 0).sort((a, b) => b.own - a.own)
        : [...data.hot].filter(method => method.self > 0).sort((a, b) => b.self - a.self);
    if (methods.length === 0) {
        $('hot-list').replaceChildren(el('p', {class: 'empty'}, yours
            ? 'None of your methods were on the CPU in these samples, or QueryScope does not know your package yet.'
            : 'No samples yet.'));
        return;
    }
    const samples = data.samples;
    const hot = table(
        [
            {title: 'Method', width: 'auto'},
            {title: yours ? 'In it and its libraries' : 'Self', width: '170px', number: true},
            {title: 'Total', width: '90px', number: true},
            {title: 'Share of samples', width: '22%'},
        ],
        methods.slice(0, HOT_ROWS).map(method => {
            const count = yours ? method.own : method.self;
            return el('tr', {
                    class: 'clickable',
                    onclick: () => {
                        state.profile.search = method.method;
                        $('profile-search').value = method.method;
                        renderFlame();
                        $('flame').scrollIntoView({block: 'center', behavior: 'smooth'});
                    },
                },
                el('td', {}, el('div', {class: 'call-name'},
                    el('code', {title: method.method}, method.app ? shortMethod(method.method) : method.method),
                    method.app ? el('span', {class: 'label your-code'}, 'Your code') : null)),
                el('td', {class: 'number'}, `${(count * 100 / samples).toFixed(1)}%`),
                el('td', {class: 'number'}, `${(method.total * 100 / samples).toFixed(1)}%`),
                el('td', {},
                    el('div', {class: 'call-share'},
                        el('div', {class: 'call-share-total', style: {width: percent(method.total / samples)}}),
                        el('div', {class: 'call-share-self', style: {width: percent(count / samples)}}))));
        }));
    hot.classList.add('call-table');
    $('hot-list').replaceChildren(hot);
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
    const items = state.sqlReports
        .flatMap(report => report.view.sqlFindings.map(finding => ({report, finding})))
        .slice(0, FEED_SIZE);
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
    $('snackbar-action').onclick = () => {
        $('snackbar').hidden = true;
        openFinding(report, finding);
    };
    showSnackbar(`New finding: ${findingText(finding)} in ${report.method} ${report.view.endpointPath}`, SNACK_MS);
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
                el('div', {class: 'details-actions'},
                    el('button', {type: 'button', class: 'text-button', onclick: () => openProfile(report)},
                        'CPU samples'),
                    report.view.hasSql
                        ? el('button', {type: 'button', class: 'text-button', onclick: () => openRequest(report.id)},
                            'SQL queries')
                        : null)),
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
    const role = ROLE_PRIORITY.map(type => FINDING_TYPES[type].css)
        .find(css => node.queryIndexes.some(index => context.roles[index] === css));
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

function fact(term, value) {
    return el('div', {}, el('dt', {}, term), el('dd', {}, value));
}

function findingCards(findings, emptyText) {
    if (findings.length === 0) {
        return [el('div', {class: 'finding clean'}, emptyText)];
    }
    return findings.filter(finding => FINDING_TYPES[finding.type]).map(findingCard);
}

function findingCard(finding) {
    const type = FINDING_TYPES[finding.type];
    return el('div', {class: `finding ${type.css}`},
        el('div', {class: 'finding-title'}, type.title(finding)),
        finding.sql ? el('code', {class: 'sql-block'}, highlightSql(finding.sql)) : null,
        el('div', {class: 'finding-meta'}, type.meta(finding)),
        el('div', {class: 'finding-hint'}, type.hint));
}

function share(part, whole) {
    return `${whole > 0 ? Math.min(100, Math.round(part / whole * 100)) : 0}%`;
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
    for (const type of ROLE_PRIORITY) {
        for (const finding of report.findings.filter(candidate => candidate.type === type)) {
            (finding.queryIndexes ?? [finding.queryIndex]).forEach(index => roles[index] ||= FINDING_TYPES[type].css);
        }
    }
    return roles;
}

function highlightQuery(index) {
    document.querySelectorAll('tr.highlight').forEach(other => other.classList.remove('highlight'));
    const row = $(`query-${index}`);
    if (row) {
        row.classList.add('highlight');
        row.scrollIntoView({block: 'center', behavior: 'smooth'});
    }
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
    return open < 0 ? shortMethod(callSite) : shortMethod(callSite.slice(0, open)) + callSite.slice(open);
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
    if (ms >= 999.5) {
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

