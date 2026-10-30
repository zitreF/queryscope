const MAX_REPORTS = 200;
const KPI_WINDOW_MS = 60_000;
const SPARK_BUCKETS = 30;
const FEED_SIZE = 10;
const SNACK_MS = 6000;
const RECONNECT_MS = 2000;
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
        meta: finding => [createCallSiteLine(finding.callSite), ` · ${formatMs(finding.totalMs)} in total`],
        hint: 'Load the rows in one query (join fetch, @EntityGraph or batch fetching) instead of one query per parent row.',
    },
    slowQuery: {
        area: 'sql', css: 'slow', name: 'Slow', label: finding => `Slow ${formatMs(finding.durationMs)}`,
        title: finding => `Slow query: ${formatMs(finding.durationMs)}`,
        meta: finding => [createCallSiteLine(finding.callSite), ` · query #${finding.queryIndex + 1}`],
        hint: 'Check the query plan (EXPLAIN) for full table scans, a missing index or a join on an unindexed column.',
    },
    repeatedWrite: {
        area: 'sql', css: 'repeated-write', name: 'Writes', label: finding => `Writes ×${finding.executions}`,
        title: finding => `Unbatched writes: the same statement ran ${finding.executions} times`,
        meta: finding => [createCallSiteLine(finding.callSite), ` · ${formatMs(finding.totalMs)} in total`],
        hint: 'Send them as one batch: addBatch and executeBatch, or hibernate.jdbc.batch_size with sequence-generated ids.',
    },
    duplicateQuery: {
        area: 'sql', css: 'duplicate', name: 'Duplicate', label: finding => `Duplicate ×${finding.executions}`,
        title: finding => `Duplicate query: the same SELECT with the same parameters ran ${finding.executions} times`,
        meta: finding => [`Parameters ${finding.parameters || 'none'} · `, createCallSiteLine(finding.callSite)],
        hint: 'Reuse the first result, pass it along, or cache it for the request.',
    },
    largeResult: {
        area: 'sql', css: 'large-result', name: 'Rows', label: finding => `${formatCount(finding.rows)} rows`,
        title: finding => `Large result: ${formatCount(finding.rows)} rows`,
        meta: finding => [createCallSiteLine(finding.callSite), ` · query #${finding.queryIndex + 1}`],
        hint: 'Fetch only what the response needs: paginate (LIMIT, Pageable), filter in SQL or select fewer columns.',
    },
    tooManyQueries: {
        area: 'sql', css: 'too-many', name: 'Queries', label: finding => `${finding.queries} queries`,
        title: finding => `Too many queries: ${finding.queries} in one unit of work`,
        meta: finding => `${percentText(finding.databaseMs, finding.durationMs)} of the time in the database`
            + ` (${formatMs(finding.databaseMs)} of ${formatMs(finding.durationMs)}) · the limit is ${finding.limit}`,
        hint: 'Look for N+1 patterns and repeated lookups, and load related data with joins instead of separate queries.',
    },
    slowMethod: {
        area: 'java', css: 'slow-method', name: 'Code', label: finding => `Slow code ${formatMs(finding.ownMs)}`,
        title: finding => `Slow method: ${formatMs(finding.ownMs)} in its own code, outside SQL`,
        meta: finding => [createElement('code', {title: finding.method}, shortMethod(finding.method)),
            ` · ${countWithNoun(finding.calls, 'call', 'calls')}`
                + ` · ${percentText(finding.ownMs, finding.durationMs)} of the time`],
        hint: 'The time goes to Java, not to the database: this method and the library code it calls. Look for loops that '
            + 'build strings, heavy mapping or serialization, and blocking calls. The call tree below shows where.',
    },
};
const VIEWS = {
    'sql/live': 'view-live',
    'sql/requests': 'view-requests',
    'java/profile': 'view-profile',
    'java/calls': 'view-calls',
    'java/traces': 'view-traces',
};
const PROFILE_WINDOWS = [
    {label: '1 min', value: 60},
    {label: '5 min', value: 300},
    {label: '15 min', value: 900},
];
const PROFILE_REFRESH_MS = 5000;
const TRACE_REFRESH_MS = 2000;
const UNTRACEABLE_PREFIXES = ['java.', 'jdk.', 'sun.', 'com.sun.', 'me.fertiz.queryscope.agent.', '('];
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
const HIGHLIGHT_PRIORITY = ['nPlusOne', 'slowQuery', 'repeatedWrite', 'largeResult', 'duplicateQuery'];

const state = {
    reports: [],
    sqlReports: [],
    javaReports: [],
    reportsWhilePaused: [],
    selectedRequestId: null,
    followNewestRequest: true,
    onlyFindings: false,
    selectedCallTreeId: null,
    followNewestCallTree: true,
    onlySlowCode: false,
    renderedCallTreeId: undefined,
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
        inFlight: null,
        samples: 'cpu',
        hotMode: 'all',
        scope: null,
        zoomPath: [],
        search: '',
        data: null,
        layout: [],
        loadedAt: 0,
    },
    traces: {
        data: null,
        inFlight: null,
        loadedAt: 0,
        selected: null,
    },
    windowMs: WINDOWS[0].ms,
    renderedRequestId: undefined,
    laneEnds: [],
    streamHitBoxes: [],
    hoverId: null,
    kpiNodes: null,
    feedAgeCells: [],
    announcedFindingKeys: new Set(),
    callTrees: new Map(),
    callTreeExpanded: new Map(),
};

const rootStyle = getComputedStyle(document.documentElement);
const cssVariable = name => rootStyle.getPropertyValue(name).trim();
const COLORS = {
    blue: cssVariable('--blue'),
    red: cssVariable('--red'),
    yellow: cssVariable('--yellow'),
    orange: cssVariable('--orange'),
    muted: cssVariable('--muted'),
    border: cssVariable('--border'),
    text: cssVariable('--text'),
};
const FONTS = {
    mono: cssVariable('--mono'),
};
const SEVERITY_COLORS = {'ok': COLORS.blue, 'warn': COLORS.orange, 'slow': COLORS.yellow, 'n-plus-one': COLORS.red};

const streamCanvas = byId('stream-canvas');
const tooltip = byId('stream-tooltip');

for (const button of document.querySelectorAll('.tab')) {
    button.addEventListener('click', () => selectView(state.lastViews[button.dataset.area]));
}
for (const button of document.querySelectorAll('.subtab')) {
    button.addEventListener('click', () => selectView(button.dataset.view));
}
window.addEventListener('hashchange', () => selectView(viewFromHash()));
byId('pause').addEventListener('click', togglePause);
byId('clear').addEventListener('click', clearAll);
byId('only-findings').addEventListener('change', event => {
    state.onlyFindings = event.target.checked;
    renderAll();
});
byId('only-slow-code').addEventListener('change', event => {
    state.onlySlowCode = event.target.checked;
    renderAll();
});
byId('trace-form').addEventListener('submit', event => {
    event.preventDefault();
    startTrace(byId('trace-method').value);
});
byId('profile-search').addEventListener('input', event => {
    state.profile.search = event.target.value.trim();
    renderFlame();
});
byId('profile-thread').addEventListener('change', event => {
    state.profile.thread = event.target.value;
    state.profile.zoomPath = [];
    loadProfile();
});
byId('profile-reset').addEventListener('click', () => {
    state.profile.zoomPath = [];
    renderFlame();
});
byId('flame').addEventListener('mousemove', hoverFlame);
byId('flame').addEventListener('mouseleave', () => byId('flame-tooltip').hidden = true);
byId('flame').addEventListener('click', zoomFlame);
window.addEventListener('resize', () => {
    if (state.view === 'java/profile') {
        renderFlame();
    }
});
streamCanvas.addEventListener('mousemove', event => hoverLiveStream(event));
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
renderAll();
connectToEventStream();
setInterval(onEverySecond, 1000);
requestAnimationFrame(animateLiveStream);

function connectToEventStream() {
    const events = new EventSource('api/events');
    let buffered = null;

    events.addEventListener('open', async () => {
        buffered = [];
        try {
            const response = await fetch('api/requests');
            if (!response.ok) {
                throw new Error(`the panel answered ${response.status}`);
            }
            const stored = (await response.json()).map(addViewFields);
            setReports([...buffered.reverse(), ...stored]);
            state.reports.forEach(report => report.findings.forEach(
                finding => state.announcedFindingKeys.add(findingKey(report, finding))));
            relayoutLanes();
            renderAll();
            setConnection(true);
        } catch (error) {
            events.close();
            setConnection(false);
            setTimeout(connectToEventStream, RECONNECT_MS);
        } finally {
            buffered = null;
        }
    });

    events.addEventListener('request', event => {
        const report = addViewFields(JSON.parse(event.data));
        if (buffered) {
            buffered.push(report);
        } else if (state.paused) {
            state.reportsWhilePaused.push(report);
            updatePauseButton();
        } else {
            addLiveReport(report);
        }
    });

    events.addEventListener('error', () => setConnection(false));
}

function addLiveReport(report) {
    setReports([report, ...state.reports]);
    if (report.view.hasSql) {
        assignLane(report);
    }
    announceNewFinding(report);
    renderAll();
}

function addViewFields(report) {
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
        queryCount: report.queries.length + report.omittedQueries,
        dbMs: report.queries.reduce((sum, query) => sum + query.durationMs, report.omittedMs),
        endpointPath: endpointPath(report.path),
        severity: types.has('nPlusOne') ? 'n-plus-one' : types.has('slowQuery') ? 'slow' : types.size > 0 ? 'warn' : 'ok',
        types,
        lane: 0,
    };
    return report;
}

function setReports(reports) {
    state.reports = keepNewestUniqueReports(reports);
    state.sqlReports = state.reports.filter(report => report.view.hasSql);
    state.javaReports = state.reports.filter(report => report.methodCount > 0);
}

function keepNewestUniqueReports(reports) {
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

function displayTime() {
    return state.paused ? state.pausedAt : Date.now();
}

function setConnection(connected) {
    state.connected = connected;
    updateStatus();
}

function updateStatus() {
    document.body.classList.toggle('live', state.connected && !state.paused);
    document.body.classList.toggle('paused', state.paused);
    byId('connection').textContent = state.paused ? 'Paused' : state.connected ? 'Live' : 'Reconnecting';
}

function togglePause() {
    state.paused = !state.paused;
    if (state.paused) {
        state.pausedAt = Date.now();
    } else {
        const pending = state.reportsWhilePaused;
        state.reportsWhilePaused = [];
        pending.forEach(addLiveReport);
    }
    updatePauseButton();
    updateStatus();
    renderAll();
}

function updatePauseButton() {
    byId('pause').textContent = state.paused
        ? `Resume${state.reportsWhilePaused.length > 0 ? ` (${state.reportsWhilePaused.length} new)` : ''}`
        : 'Pause';
}

async function clearAll() {
    try {
        const response = await fetch('api/requests', {method: 'DELETE'});
        if (!response.ok) {
            throw new Error(`the panel answered ${response.status}`);
        }
    } catch (error) {
        byId('snackbar-action').hidden = true;
        showSnackbar(`Could not clear the requests: ${error.message}`, SNACK_MS);
        return;
    }
    setReports([]);
    state.reportsWhilePaused = [];
    state.announcedFindingKeys.clear();
    state.selectedRequestId = null;
    state.followNewestRequest = true;
    state.selectedCallTreeId = null;
    state.followNewestCallTree = true;
    state.laneEnds = [];
    updatePauseButton();
    renderAll();
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
        byId(id).hidden = key !== view;
    }
    if (location.hash !== `#${view}`) {
        history.replaceState(null, '', `#${view}`);
    }
    if (view === 'java/profile') {
        loadProfile();
    }
    if (view === 'java/traces') {
        loadTraces();
    }
}

function openRequest(id) {
    state.selectedRequestId = id;
    state.followNewestRequest = false;
    selectView('sql/requests');
    renderAll();
}

function openCalls(id) {
    state.selectedCallTreeId = id;
    state.followNewestCallTree = false;
    selectView('java/calls');
    renderAll();
}

function openProfile(report) {
    state.profile.scope = {
        id: report.id,
        kind: 'request',
        label: `${report.method} ${report.path}`,
        endpoint: isTask(report) ? `Task ${report.path}` : `${report.method} ${report.view.endpointPath}`,
    };
    state.profile.zoomPath = [];
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

function renderAll() {
    byId('request-count').textContent = String(state.sqlReports.length);
    byId('call-count').textContent = String(state.javaReports.length);
    byId('stream-empty').hidden = state.sqlReports.length > 0;
    renderKpis();
    renderEndpoints();
    renderFeed();
    renderRequestList();
    renderCallTreeList();
}

function onEverySecond() {
    renderKpis();
    if (state.view === 'java/profile' && !state.paused && state.profile.scope === null
        && state.profile.inFlight === null && Date.now() - state.profile.loadedAt >= PROFILE_REFRESH_MS) {
        loadProfile();
    }
    if (state.view === 'java/traces' && !state.paused && state.traces.inFlight === null
        && Date.now() - state.traces.loadedAt >= TRACE_REFRESH_MS) {
        loadTraces();
    }
    const current = displayTime();
    for (const {node, end} of state.feedAgeCells) {
        node.textContent = timeAgo(current - end);
    }
}

function animateLiveStream() {
    if (state.view === 'sql/live') {
        drawStream();
    }
    requestAnimationFrame(animateLiveStream);
}

function renderKpis() {
    const end = displayTime();
    const from = end - KPI_WINDOW_MS;
    const recent = state.sqlReports.filter(report => report.view.end > from && report.view.end <= end);
    const queries = recent.reduce((sum, report) => sum + report.view.queryCount, 0);
    const duration = recent.reduce((sum, report) => sum + report.durationMs, 0);
    const db = recent.reduce((sum, report) => sum + report.view.dbMs, 0);
    const withNPlusOne = state.sqlReports.filter(report => report.view.types.has('nPlusOne'));
    const withSlow = state.sqlReports.filter(report => report.view.types.has('slowQuery'));
    const dbSeries = kpiBuckets(end, report => report.view.dbMs);
    const durationSeries = kpiBuckets(end, report => report.durationMs);
    const kpis = [
        {key: 'requests', label: 'Requests per minute', value: recent.length,
            sub: `${countWithNoun(state.sqlReports.length, 'request', 'requests')} kept`,
            series: kpiBuckets(end, () => 1)},
        {key: 'queries', label: 'Queries per minute', value: queries,
            sub: recent.length > 0 ? `${(queries / recent.length).toFixed(1)} per request` : 'No requests in the last minute',
            series: kpiBuckets(end, report => report.view.queryCount)},
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
    if (!state.kpiNodes) {
        state.kpiNodes = new Map();
        byId('kpis').replaceChildren(...kpis.map(kpi => {
            const value = createElement('div', {class: 'stat-value'});
            const sub = createElement('div', {class: 'stat-sub'});
            const canvas = kpi.series ? createElement('canvas', {}) : null;
            const node = createElement('div', {class: 'stat'},
                createElement('div', {class: 'stat-label'}, kpi.label), value, sub, canvas);
            state.kpiNodes.set(kpi.key, {node, value, sub, canvas});
            return node;
        }));
    }
    for (const kpi of kpis) {
        const view = state.kpiNodes.get(kpi.key);
        view.node.className = `stat ${kpi.alert ?? ''}`.trim();
        view.value.replaceChildren(
            ...[String(kpi.value), kpi.unit ? createElement('small', {}, kpi.unit) : null].filter(Boolean));
        view.sub.textContent = kpi.sub;
        if (view.canvas) {
            drawSparkline(view.canvas, kpi.series);
        }
    }
}

function kpiBuckets(end, valueOf) {
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
    const context = resetCanvas(canvas);
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
    byId('window-chips').replaceChildren(...WINDOWS.map(option => createElement('button', {
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
    renderSegmentedControl(byId('profile-window'), PROFILE_WINDOWS, profile.seconds, value => {
        profile.seconds = value;
        loadProfile();
    });
    renderSegmentedControl(byId('profile-frames'),
        [{label: 'All frames', value: 'all'}, {label: 'Your code', value: 'app'}],
        profile.frames, value => {
            profile.frames = value;
            profile.framesChosen = true;
            profile.zoomPath = [];
            loadProfile();
        });
    renderSegmentedControl(byId('profile-samples'),
        [{label: 'CPU', value: 'cpu'}, {label: 'CPU and native', value: 'all'}],
        profile.samples, value => {
            profile.samples = value;
            profile.zoomPath = [];
            loadProfile();
        });
    renderSegmentedControl(byId('hot-mode'),
        [{label: 'All methods', value: 'all'}, {label: 'Your methods', value: 'app'}],
        profile.hotMode, value => {
            profile.hotMode = value;
            renderHotList();
        });
    byId('profile-window').hidden = profile.scope !== null;
}

function renderSegmentedControl(container, options, current, onSelect) {
    container.replaceChildren(...options.map(option => createElement('button', {
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
    profile.inFlight?.abort();
    const request = profile.inFlight = new AbortController();
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
        data = response.ok ? addParentLinks(await response.json()) : {missing: true};
    } catch (error) {
        data = {failed: true};
    }
    if (request.signal.aborted) {
        return;
    }
    profile.inFlight = null;
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

function addParentLinks(data) {
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
    byId('profile-status').textContent = profileStatus(state.profile.data);
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
    return `${countWithNoun(data.samples, 'sample', 'samples')} from ${data.source}, one every ${data.intervalMs} ms`
        + (state.profile.scope ? '' : `, in the last ${range ? range.label : `${state.profile.seconds} s`}`)
        + (data.agentSamples > 0 ? `. QueryScope itself took ${formatCount(data.agentSamples)} more.` : '');
}

function renderThreadOptions() {
    const data = state.profile.data;
    const threads = data && data.threads ? data.threads : [];
    const options = [createElement('option', {value: 'all'}, 'All threads')];
    for (const thread of threads) {
        options.push(createElement('option', {value: String(thread.id)},
            `${thread.name} · ${formatCount(thread.samples)}`));
    }
    if (state.profile.thread !== 'all' && !threads.some(thread => String(thread.id) === state.profile.thread)) {
        options.push(createElement('option', {value: state.profile.thread}, `thread ${state.profile.thread} · 0`));
    }
    byId('profile-thread').replaceChildren(...options);
    byId('profile-thread').value = state.profile.thread;
}

function renderProfileScope() {
    const scope = state.profile.scope;
    const box = byId('profile-scope');
    box.hidden = scope === null;
    if (!scope) {
        return;
    }
    const choice = createElement('div', {class: 'segmented', role: 'group', 'aria-label': 'Scope'});
    box.replaceChildren(
        createElement('span', {class: 'scope-label'}, scope.kind === 'request'
            ? `Samples of one call of ${scope.label}`
            : `Samples of every stored call of ${scope.endpoint}`),
        choice,
        createElement('button', {
            type: 'button',
            class: 'text-button',
            onclick: () => {
                state.profile.scope = null;
                state.profile.zoomPath = [];
                renderProfileControls();
                loadProfile();
            },
        }, 'Whole app'));
    renderSegmentedControl(choice,
        [{label: 'This call', value: 'request'}, {label: 'All calls', value: 'endpoint'}], scope.kind,
        value => {
            scope.kind = value;
            state.profile.zoomPath = [];
            loadProfile();
        });
}

function renderFlame() {
    const container = byId('flame');
    const data = state.profile.data;
    if (!data || !data.root || data.samples === 0) {
        container.style.height = '';
        container.replaceChildren(createElement('p', {class: 'empty'}, emptyFlameText(data)));
        byId('profile-reset').hidden = true;
        byId('profile-zoom').textContent = '';
        return;
    }
    const width = container.clientWidth || 1000;
    const zoomedNode = findZoomedNode(data.root, state.profile.zoomPath);
    const chain = [];
    for (let node = zoomedNode.parent; node !== null; node = node.parent) {
        chain.unshift(node);
    }
    const layout = chain.map((node, depth) => ({node, x: 0, width: 1, depth, ancestor: true}));
    layoutFlame(zoomedNode, 0, 1, chain.length, width, layout);
    state.profile.layout = layout;
    const search = state.profile.search.toLowerCase();
    const rows = Math.max(...layout.map(item => item.depth)) + 1;
    container.style.height = `${rows * FLAME_ROW}px`;
    container.replaceChildren(...layout.map((item, index) => {
        const matches = search !== '' && item.node.method.toLowerCase().includes(search);
        return createElement('div', {
            class: ['flame-node', frameKind(item.node), item.ancestor ? 'ancestor' : '', matches ? 'match' : '',
                search !== '' && !matches ? 'faded' : ''].join(' '),
            'data-index': String(index),
            style: {left: cssPercent(item.x), width: cssPercent(item.width), top: `${item.depth * FLAME_ROW}px`},
        }, item.width * width >= FLAME_LABEL_PX ? frameLabel(item.node) : null);
    }));
    byId('profile-reset').hidden = state.profile.zoomPath.length === 0;
    byId('profile-zoom').textContent = zoomedNode === data.root ? '' : `Zoomed into ${frameLabel(zoomedNode)}`;
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

function findZoomedNode(root, path) {
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

function methodPathTo(node) {
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
    state.profile.zoomPath = methodPathTo(state.profile.layout[Number(target.dataset.index)].node);
    byId('flame-tooltip').hidden = true;
    renderFlame();
}

function hoverFlame(event) {
    const tooltip = byId('flame-tooltip');
    const target = event.target.closest('.flame-node');
    if (!target) {
        tooltip.hidden = true;
        return;
    }
    const node = state.profile.layout[Number(target.dataset.index)].node;
    const samples = Math.max(state.profile.data.samples, 1);
    const kind = {app: 'Your code', lib: 'Library or framework', jdk: 'JDK', special: ''}[frameKind(node)];
    tooltip.replaceChildren(...[
        createElement('div', {class: 'tooltip-path'}, node.method),
        createElement('div', {class: 'tooltip-meta'}, [
            `${formatCount(node.total)} ${node.total === 1 ? 'sample' : 'samples'}, `
                + roundedPercentText(node.total / samples),
            node.self > 0 ? `${formatCount(node.self)} on its own line` : null,
            kind || null,
        ].filter(Boolean).join(' · ')),
        createElement('div', {class: 'tooltip-meta'}, 'Click to zoom in'),
    ]);
    tooltip.hidden = false;
    const bounds = byId('flame').getBoundingClientRect();
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
    byId('hot-hint').textContent = yours
        ? 'Your methods by the samples taken in them or in library code they call'
        : 'Methods by the samples taken while they were running their own code';
    if (!data || !data.hot || data.samples === 0) {
        byId('hot-list').replaceChildren(createElement('p', {class: 'empty'}, 'No samples yet.'));
        return;
    }
    const methods = yours
        ? data.hot.filter(method => method.selfWithLibraries > 0)
            .sort((a, b) => b.selfWithLibraries - a.selfWithLibraries)
        : [...data.hot].filter(method => method.self > 0).sort((a, b) => b.self - a.self);
    if (methods.length === 0) {
        byId('hot-list').replaceChildren(createElement('p', {class: 'empty'}, yours
            ? 'None of your methods were on the CPU in these samples, or QueryScope does not know your package yet.'
            : 'No samples yet.'));
        return;
    }
    const samples = data.samples;
    const hot = createTable(
        [
            {title: 'Method', width: 'auto'},
            {title: yours ? 'In it and its libraries' : 'Self', width: '170px', number: true},
            {title: 'Total', width: '90px', number: true},
            {title: 'Share of samples', width: '22%'},
            {title: '', width: '80px'},
        ],
        methods.slice(0, HOT_ROWS).map(method => {
            const count = yours ? method.selfWithLibraries : method.self;
            return createElement('tr', {
                    class: 'clickable',
                    onclick: () => {
                        state.profile.search = method.method;
                        byId('profile-search').value = method.method;
                        renderFlame();
                        byId('flame').scrollIntoView({block: 'center', behavior: 'smooth'});
                    },
                },
                createElement('td', {}, createElement('div', {class: 'call-name'},
                    createElement('code', {title: method.method},
                        method.app ? shortMethod(method.method) : method.method),
                    method.app ? createElement('span', {class: 'label your-code'}, 'Your code') : null)),
                createElement('td', {class: 'number'}, `${(count * 100 / samples).toFixed(1)}%`),
                createElement('td', {class: 'number'}, `${(method.total * 100 / samples).toFixed(1)}%`),
                createElement('td', {},
                    createElement('div', {class: 'call-share'},
                        createElement('div',
                            {class: 'call-share-total', style: {width: cssPercent(method.total / samples)}}),
                        createElement('div',
                            {class: 'call-share-self', style: {width: cssPercent(count / samples)}}))),
                createElement('td', {}, createTraceButton(method.method)));
        }));
    hot.classList.add('call-table');
    byId('hot-list').replaceChildren(hot);
}

function laneCount() {
    const height = streamCanvas.clientHeight || 260;
    return Math.max(1, Math.floor((height - STREAM_TOP - 6) / LANE_HEIGHT));
}

function barWidth(report, width) {
    const durationWidth = report.durationMs / state.windowMs * width;
    return Math.max(durationWidth, 8 + Math.min(80, report.view.queryCount * 1.6));
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
    const context = resetCanvas(streamCanvas);
    if (!context) {
        return;
    }
    const {width, height} = context.size;
    const end = displayTime();
    const from = end - state.windowMs;
    const x = time => (time - from) / state.windowMs * width;

    context.font = `11px ${FONTS.mono}`;
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
    state.streamHitBoxes = hits;
}

function hoverLiveStream(event) {
    const bounds = streamCanvas.getBoundingClientRect();
    const mouseX = event.clientX - bounds.left;
    const mouseY = event.clientY - bounds.top;
    const hit = [...state.streamHitBoxes].reverse().find(box =>
        mouseX >= box.left - 3 && mouseX <= box.right + 3 && mouseY >= box.top - 4 && mouseY <= box.bottom + 4);
    state.hoverId = hit ? hit.id : null;
    streamCanvas.classList.toggle('pointing', Boolean(hit));
    if (!hit) {
        tooltip.hidden = true;
        return;
    }
    const report = hit.report;
    tooltip.replaceChildren(...[
        createElement('div', {class: 'tooltip-path'}, `${report.method} ${report.path}`),
        createElement('div', {class: 'tooltip-meta'}, [
            isTask(report) ? 'Task' : `HTTP ${report.status}`,
            formatMs(report.durationMs),
            countWithNoun(report.view.queryCount, 'query', 'queries'),
            `${formatMs(report.view.dbMs)} in database`,
        ].join(' · ')),
        report.view.sqlFindings.length > 0
            ? createElement('div', {class: 'tooltip-meta'}, report.view.sqlFindings.map(createFindingLabel))
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
        group.queries += report.view.queryCount;
        group.duration += report.durationMs;
        group.db += report.view.dbMs;
        report.view.types.forEach(type => group.types.add(type));
        groups.set(key, group);
    }
    const top = [...groups.values()].sort((a, b) => b.db - a.db).slice(0, TOP_ENDPOINTS);
    if (top.length === 0) {
        byId('endpoints').replaceChildren(
            createElement('p', {class: 'empty'}, 'Endpoints appear here after their first request.'));
        return;
    }
    byId('endpoints').replaceChildren(createTable(
        [
            {title: 'Endpoint or task', width: 'auto'},
            {title: 'Calls', width: '70px', number: true},
            {title: 'Queries per call', width: '120px', number: true},
            {title: 'Avg time', width: '90px', number: true},
            {title: 'Database time', width: '120px', number: true},
            {title: 'Findings', width: '190px'},
        ],
        top.map(group => createElement('tr', {class: 'clickable', onclick: () => openRequest(group.latestId)},
            createElement('td', {class: 'mono ellipsis', title: group.path},
                createElement('span', {class: 'method'}, group.method), group.path),
            createElement('td', {class: 'number'}, String(group.calls)),
            createElement('td', {class: 'number'}, (group.queries / group.calls).toFixed(1)),
            createElement('td', {class: 'number'}, formatMs(group.duration / group.calls)),
            createElement('td', {class: 'number'}, formatMs(group.db)),
            createElement('td', {}, group.types.size === 0 ? '—' : Object.keys(FINDING_TYPES)
                .filter(type => group.types.has(type))
                .map(type => createElement('span', {class: `label ${FINDING_TYPES[type].css}`},
                    FINDING_TYPES[type].name)))))));
}

function renderFeed() {
    const items = state.sqlReports
        .flatMap(report => report.view.sqlFindings.map(finding => ({report, finding})))
        .slice(0, FEED_SIZE);
    state.feedAgeCells = [];
    if (items.length === 0) {
        byId('findings-feed').replaceChildren(createElement('p', {class: 'empty'}, 'No findings so far.'));
        return;
    }
    const current = displayTime();
    byId('findings-feed').replaceChildren(createTable(
        [
            {title: 'When', width: '80px'},
            {title: 'Finding', width: '130px'},
            {title: 'Request and location', width: 'auto'},
        ],
        items.slice(0, FEED_SIZE).map(({report, finding}) => {
            const time = createElement('td', {}, timeAgo(current - report.view.end));
            state.feedAgeCells.push({node: time, end: report.view.end});
            return createElement('tr', {class: 'clickable', onclick: () => openRequest(report.id)},
                time,
                createElement('td', {}, createFindingLabel(finding)),
                createElement('td', {class: 'ellipsis'},
                    createElement('div', {class: 'path', title: report.path}, `${report.method} ${report.path}`),
                    createElement('div', {class: 'path', title: finding.callSite ?? finding.method ?? ''},
                        createElement('span', {class: 'muted'}, findingLocation(finding)))));
        })));
}

function createTraceButton(method) {
    if (UNTRACEABLE_PREFIXES.some(prefix => method.startsWith(prefix)) || method.includes('$$')) {
        return null;
    }
    return createElement('button', {
        type: 'button',
        class: 'text-button',
        title: 'Record every call of this method',
        onclick: event => {
            event.stopPropagation();
            startTrace(toMethodReference(method));
        },
    }, 'Trace');
}

function toMethodReference(method) {
    const dot = method.lastIndexOf('.');
    return `${method.slice(0, dot)}#${method.slice(dot + 1)}`;
}

async function startTrace(reference) {
    byId('trace-error').textContent = '';
    try {
        const response = await fetch('api/traces', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({method: reference.trim()}),
        });
        const result = await response.json().catch(() => ({}));
        if (!response.ok) {
            throw new Error(result.error || `the panel answered ${response.status}`);
        }
        state.traces.selected = result.method;
        byId('trace-method').value = '';
        if (result.loadedClasses === 0) {
            byId('trace-error').textContent = 'The class is not loaded yet, its calls show up once it is.';
        }
    } catch (error) {
        byId('trace-error').textContent = error.message;
    }
    if (state.view === 'java/traces') {
        loadTraces();
    } else {
        selectView('java/traces');
    }
}

async function stopTrace(method) {
    try {
        const response = await fetch(`api/traces/${encodeURIComponent(method)}`, {method: 'DELETE'});
        if (!response.ok && response.status !== 404) {
            throw new Error(`the panel answered ${response.status}`);
        }
    } catch (error) {
        byId('snackbar-action').hidden = true;
        showSnackbar(`Could not stop tracing ${shortMethod(method)}: ${error.message}`, SNACK_MS);
    }
    loadTraces();
}

async function loadTraces() {
    const traces = state.traces;
    traces.inFlight?.abort();
    const request = traces.inFlight = new AbortController();
    let data;
    try {
        const response = await fetch('api/traces', {signal: request.signal});
        data = response.ok ? await response.json() : null;
    } catch (error) {
        data = null;
    }
    if (request.signal.aborted) {
        return;
    }
    traces.inFlight = null;
    traces.data = data;
    traces.loadedAt = Date.now();
    renderTraces();
}

function renderTraces() {
    const data = state.traces.data;
    const traces = data ? data.traces : [];
    byId('trace-count').textContent = String(traces.length);
    if (traces.length === 0) {
        byId('trace-list').replaceChildren(createElement('p', {class: 'empty'}, data
            ? 'No method is traced yet. Name one above, press Trace next to a hot method in the profiler, '
            + 'or start the app with trace=com.acme.OrderService#findAll.'
            : 'Could not load the traced methods.'));
        byId('trace-details').hidden = true;
        return;
    }
    if (!traces.some(trace => trace.method === state.traces.selected)) {
        state.traces.selected = traces[0].method;
    }
    const table = createTable(
        [
            {title: 'Method', width: 'auto'},
            {title: 'Calls', width: '100px', number: true},
            {title: 'Failed', width: '90px', number: true},
            {title: 'Average', width: '100px', number: true},
            {title: 'p95', width: '100px', number: true},
            {title: 'Max', width: '100px', number: true},
            {title: '', width: '80px'},
        ],
        traces.map(trace => createElement('tr', {
                class: `clickable${trace.method === state.traces.selected ? ' selected' : ''}`,
                onclick: () => {
                    state.traces.selected = trace.method;
                    renderTraces();
                },
            },
            createElement('td', {}, createElement('code', {title: trace.method}, shortMethod(trace.method))),
            createElement('td', {class: 'number'}, formatCount(trace.calls)),
            createElement('td', {class: trace.failures > 0 ? 'number status-error' : 'number'},
                formatCount(trace.failures)),
            createElement('td', {class: 'number'}, trace.calls > 0 ? formatMs(trace.averageMs) : '—'),
            createElement('td', {class: 'number'}, trace.calls > 0 ? formatMs(trace.p95Ms) : '—'),
            createElement('td', {class: 'number'}, trace.calls > 0 ? formatMs(trace.maxMs) : '—'),
            createElement('td', {}, createElement('button', {
                type: 'button',
                class: 'text-button',
                onclick: event => {
                    event.stopPropagation();
                    stopTrace(trace.method);
                },
            }, 'Stop')))));
    table.classList.add('call-table');
    byId('trace-list').replaceChildren(table);
    renderTraceDetails(traces.find(trace => trace.method === state.traces.selected));
}

function renderTraceDetails(trace) {
    const details = byId('trace-details');
    details.hidden = false;
    const summary = trace.calls > 0
        ? `Median ${formatMs(trace.p50Ms)}, p99 ${formatMs(trace.p99Ms)}, ${formatMs(trace.totalMs)} in all calls. `
        : 'No calls yet. ';
    details.replaceChildren(
        createElement('div', {class: 'panel-header'}, createElement('div', {},
            createElement('h2', {}, createElement('code', {}, trace.method)),
            createElement('p', {class: 'panel-hint'},
                `${summary}Traced since ${new Date(parseInstant(trace.since)).toLocaleTimeString()}.`))),
        createElement('h3', {class: 'section-title'}, 'Slowest calls'),
        createTracedCallTable(trace.slowest),
        createElement('h3', {class: 'section-title'}, 'Latest calls'),
        createTracedCallTable(trace.recent));
}

function createTracedCallTable(calls) {
    if (calls.length === 0) {
        return createElement('p', {class: 'empty'}, 'No calls yet.');
    }
    const now = Date.now();
    const table = createTable(
        [
            {title: 'Ended', width: '110px'},
            {title: 'Duration', width: '120px', number: true},
            {title: 'Request or task', width: 'auto'},
            {title: 'Thread', width: '28%'},
        ],
        calls.map(call => createElement('tr', {},
            createElement('td', {}, timeAgo(now - parseInstant(call.endedAt))),
            createElement('td', {class: call.failed ? 'number status-error' : 'number'},
                call.failed ? `${formatMs(call.durationMs)}, threw` : formatMs(call.durationMs)),
            createElement('td', {}, createUnitLink(call)),
            createElement('td', {class: 'ellipsis', title: call.thread}, call.thread))));
    table.classList.add('call-table');
    return table;
}

function createUnitLink(call) {
    if (call.unitId === null) {
        return createElement('span', {class: 'muted'}, 'Outside any request or task');
    }
    const report = state.reports.find(candidate => candidate.id === call.unitId);
    if (!report) {
        return call.unit;
    }
    return createElement('button', {
        type: 'button',
        class: 'text-button',
        onclick: () => report.view.hasSql ? openRequest(report.id) : openCalls(report.id),
    }, call.unit);
}

function createTable(columns, rows) {
    return createElement('table', {},
        createElement('colgroup', {}, columns.map(column => createElement('col', {style: {width: column.width}}))),
        createElement('thead', {}, createElement('tr', {}, columns.map(column =>
            createElement('th', {class: column.number ? 'number' : ''}, column.title)))),
        createElement('tbody', {}, rows));
}

function announceNewFinding(report) {
    const finding = report.findings.find(candidate => !state.announcedFindingKeys.has(findingKey(report, candidate)));
    if (!finding) {
        return;
    }
    report.findings.forEach(candidate => state.announcedFindingKeys.add(findingKey(report, candidate)));
    byId('snackbar-action').onclick = () => {
        byId('snackbar').hidden = true;
        openFinding(report, finding);
    };
    byId('snackbar-action').hidden = false;
    showSnackbar(`New finding: ${findingText(finding)} in ${report.method} ${report.view.endpointPath}`, SNACK_MS);
}

function renderRequestList() {
    const visible = state.sqlReports.filter(report => !state.onlyFindings || report.view.sqlFindings.length > 0);
    if (state.followNewestRequest || !visible.some(report => report.id === state.selectedRequestId)) {
        state.selectedRequestId = visible.length > 0 ? visible[0].id : null;
    }
    byId('request-list').replaceChildren(...visible.map((report, index) => createRequestListItem(report, index)));
    byId('no-requests').hidden = visible.length > 0;
    byId('no-requests').textContent = state.sqlReports.length > 0
        ? 'No requests with findings yet.'
        : 'Waiting for requests or tasks that run SQL.';
    const selected = state.sqlReports.find(report => report.id === state.selectedRequestId) ?? null;
    if ((selected?.id ?? null) !== state.renderedRequestId) {
        renderRequestDetails(selected);
        state.renderedRequestId = selected?.id ?? null;
    }
}

function createRequestListItem(report, index) {
    return createElement('li', {
            class: `request${report.id === state.selectedRequestId ? ' selected' : ''}`,
            onclick: () => {
                state.selectedRequestId = report.id;
                state.followNewestRequest = index === 0;
                renderAll();
            },
        },
        createElement('div', {class: 'request-line'},
            createElement('span', {class: 'method'}, report.method),
            createElement('span', {class: 'path', title: report.path}, report.path)),
        createElement('div', {class: 'request-meta'},
            isTask(report)
                ? createElement('span', {}, 'Task')
                : createElement('span', {class: report.status >= 400 ? 'status-error' : ''}, String(report.status)),
            createElement('span', {}, countWithNoun(report.view.queryCount, 'query', 'queries')),
            createElement('span', {}, formatMs(report.durationMs)),
            report.view.sqlFindings.map(createFindingLabel)));
}

function renderRequestDetails(report) {
    if (!report) {
        byId('details').replaceChildren(createElement('div', {class: 'panel'},
            createElement('p', {class: 'empty', style: {paddingTop: '20px'}}, 'Select a request to see its queries.')));
        return;
    }
    const highlights = queryHighlightClasses(report);
    byId('details').replaceChildren(
        createElement('div', {class: 'panel details-header'},
            createElement('div', {class: 'details-title'},
                createElement('h1', {}, createElement('span', {class: 'method'}, report.method), report.path),
                report.methodCount > 0
                    ? createElement('button',
                        {type: 'button', class: 'text-button', onclick: () => openCalls(report.id)},
                        'Call tree')
                    : null),
            createElement('dl', {class: 'facts'},
                isTask(report) ? createFact('Kind', 'Task outside HTTP') : createFact('Status', String(report.status)),
                createFact('Duration', formatMs(report.durationMs)),
                createFact('Queries', formatCount(report.view.queryCount)),
                createFact('Time in database', formatMs(report.view.dbMs)),
                createFact('Started', new Date(report.view.start).toLocaleTimeString()))),
        createElement('h3', {class: 'section-title'}, 'Findings'),
        ...createFindingCards(report.view.sqlFindings, 'No SQL findings in this request.'),
        createElement('h3', {class: 'section-title'}, 'Timeline'),
        createTimeline(report, highlights),
        createElement('h3', {class: 'section-title'}, 'Queries'),
        ...(report.omittedQueries > 0 ? [createElement('p', {class: 'empty'},
            `First ${formatCount(report.queries.length)} of ${formatCount(report.view.queryCount)} queries.`
            + ' The rest count in the totals but are not listed.')] : []),
        createQueryTable(report, highlights));
}

function renderCallTreeList() {
    const visible = state.javaReports.filter(report => !state.onlySlowCode || report.view.javaFindings.length > 0);
    if (state.followNewestCallTree || !visible.some(report => report.id === state.selectedCallTreeId)) {
        state.selectedCallTreeId = visible.length > 0 ? visible[0].id : null;
    }
    byId('call-list').replaceChildren(...visible.map((report, index) => createCallTreeListItem(report, index)));
    byId('no-calls').hidden = visible.length > 0;
    byId('no-calls').textContent = state.javaReports.length > 0
        ? 'No requests with slow code yet.'
        : 'Waiting for requests or tasks that run your code.';
    const selected = state.javaReports.find(report => report.id === state.selectedCallTreeId) ?? null;
    if ((selected?.id ?? null) !== state.renderedCallTreeId) {
        renderCallTreeDetails(selected);
        state.renderedCallTreeId = selected?.id ?? null;
    }
}

function createCallTreeListItem(report, index) {
    return createElement('li', {
            class: `request${report.id === state.selectedCallTreeId ? ' selected' : ''}`,
            onclick: () => {
                state.selectedCallTreeId = report.id;
                state.followNewestCallTree = index === 0;
                renderAll();
            },
        },
        createElement('div', {class: 'request-line'},
            createElement('span', {class: 'method'}, report.method),
            createElement('span', {class: 'path', title: report.path}, report.path)),
        createElement('div', {class: 'request-meta'},
            isTask(report)
                ? createElement('span', {}, 'Task')
                : createElement('span', {class: report.status >= 400 ? 'status-error' : ''}, String(report.status)),
            createElement('span', {}, formatMs(report.durationMs)),
            createElement('span', {}, countWithNoun(report.methodCount, 'method', 'methods')),
            report.view.javaFindings.map(createFindingLabel)));
}

function renderCallTreeDetails(report) {
    if (!report) {
        byId('call-details').replaceChildren(createElement('div', {class: 'panel'},
            createElement('p', {class: 'empty', style: {paddingTop: '20px'}},
                'Select a request to see its call tree.')));
        return;
    }
    byId('call-details').replaceChildren(
        createElement('div', {class: 'panel details-header'},
            createElement('div', {class: 'details-title'},
                createElement('h1', {}, createElement('span', {class: 'method'}, report.method), report.path),
                createElement('div', {class: 'details-actions'},
                    createElement('button', {type: 'button', class: 'text-button', onclick: () => openProfile(report)},
                        'CPU samples'),
                    report.view.hasSql
                        ? createElement('button',
                            {type: 'button', class: 'text-button', onclick: () => openRequest(report.id)},
                            'SQL queries')
                        : null)),
            createElement('dl', {class: 'facts', id: 'call-facts'}, createCallTreeFacts(report, null))),
        createElement('h3', {class: 'section-title'}, 'Findings'),
        ...createFindingCards(report.view.javaFindings, 'No slow code in this request.'),
        createElement('h3', {class: 'section-title'}, 'Call tree'),
        createElement('div', {class: 'panel call-tree', id: 'call-tree', 'data-id': report.id},
            createElement('p', {class: 'empty', style: {paddingTop: '20px'}}, 'Loading the call tree…')),
        createElement('h3', {class: 'section-title', id: 'method-queries-title'}, 'Queries of a method'),
        createElement('div', {class: 'panel', id: 'method-queries'},
            createElement('p', {class: 'empty', style: {paddingTop: '20px'}}, report.view.hasSql
                ? 'Select a method with queries in the call tree to see its SQL here.'
                : 'This request ran no SQL.')),
        createElement('h3', {class: 'section-title'}, 'Methods by self time'),
        createElement('div', {class: 'panel', id: 'hot-methods'},
            createElement('p', {class: 'empty', style: {paddingTop: '20px'}}, 'Loading…')));
    state.callTreeExpanded = new Map();
    loadCallTree(report);
}

function createCallTreeFacts(report, tree) {
    const ownMs = tree && tree.methods ? tree.methods.reduce((sum, node) => sum + node.totalMs, 0) : null;
    return [
        isTask(report) ? createFact('Kind', 'Task outside HTTP') : createFact('Status', String(report.status)),
        createFact('Duration', formatMs(report.durationMs)),
        createFact('In your code', ownMs === null ? '…' : formatMs(Math.min(ownMs, report.durationMs))),
        createFact('In SQL', formatMs(report.view.dbMs)),
        createFact('Methods', String(report.methodCount)),
        createFact('Started', new Date(report.view.start).toLocaleTimeString()),
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
    const container = byId('call-tree');
    if (!container || container.dataset.id !== String(report.id)) {
        return;
    }
    const highlights = queryHighlightClasses(report);
    renderCallTree(report, tree, highlights);
    if (tree.methods) {
        byId('call-facts').replaceChildren(...createCallTreeFacts(report, tree));
        renderHotMethods(report, tree);
    } else {
        byId('hot-methods').replaceChildren(createElement('p', {class: 'empty', style: {paddingTop: '20px'}}, '—'));
    }
}

function renderCallTree(report, tree, highlights) {
    const container = byId('call-tree');
    if (!container || container.dataset.id !== String(report.id)) {
        return;
    }
    const unit = isTask(report) ? 'task' : 'request';
    const message = text => [createElement('p', {class: 'empty', style: {paddingTop: '20px'}}, text)];
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
        highlights,
        duration: Math.max(report.durationMs, 0.001),
        slowMethods: new Set(report.findings.filter(finding => finding.type === 'slowMethod').map(finding => finding.method)),
        rerender: () => renderCallTree(report, tree, highlights),
    };
    const rows = [];
    appendCallTreeRows(root, 0, 'root', context, rows);
    const calls = createTable(
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
            ? createElement('p', {class: 'call-warm-up'},
                `The JVM loaded ${formatCount(tree.classesLoaded)} classes while this `
                + `${unit} ran, so its times include warm-up and slow methods are not reported for it.`)
            : null,
        calls,
        tree.truncated
            ? createElement('p', {class: 'empty'},
                'The tree stops at 1,000 methods or 100 levels, the rest is counted in the method above.')
            : null,
    ].filter(node => node !== null));
}

function appendCallTreeRows(node, depth, path, context, rows) {
    rows.push(createCallTreeRow(node, depth, path, context));
    if (isExpanded(node, depth, path, context)) {
        node.children.forEach((child, index) =>
            appendCallTreeRows(child, depth + 1, `${path}/${index}`, context, rows));
    }
}

function createCallTreeRow(node, depth, path, context) {
    const hasChildren = node.children.length > 0;
    const expanded = hasChildren && isExpanded(node, depth, path, context);
    const queries = node.queryIndexes.length;
    const role = HIGHLIGHT_PRIORITY.map(type => FINDING_TYPES[type].css)
        .find(css => node.queryIndexes.some(index => context.highlights[index] === css));
    const slow = node.method !== null && !node.startedBeforeUnit && context.slowMethods.has(node.method);
    const name = node.method === null
        ? createElement('span', {class: 'call-unit'}, isTask(context.report)
            ? `Task ${context.report.path}`
            : `${context.report.method} ${context.report.path}`)
        : createElement('code', {title: node.method}, shortMethod(node.method));
    const properties = {class: ['call-row', queries > 0 ? 'clickable' : '', slow ? 'slow-method' : ''].join(' ')};
    if (queries > 0) {
        properties.onclick = () => showMethodQueries(context.report, node, context.highlights);
    }
    return createElement('tr', properties,
        createElement('td', {style: {paddingLeft: `${20 + depth * CALL_INDENT}px`}}, createElement('div', {class: 'call-name'},
            hasChildren
                ? createElement('button', {
                    class: 'call-toggle',
                    'aria-expanded': String(expanded),
                    'aria-label': expanded ? 'Collapse' : 'Expand',
                    onclick: event => {
                        event.stopPropagation();
                        state.callTreeExpanded.set(path, !expanded);
                        context.rerender();
                    },
                }, expanded ? '▾' : '▸')
                : createElement('span', {class: 'call-toggle'}),
            name,
            node.startedBeforeUnit
                ? createElement('span', {class: 'call-note',
                        title: 'This method was already running when the task started, '
                        + 'so only the time inside the task is counted.'}, 'already running')
                : null,
            slow ? createElement('span', {class: 'label slow-method'}, 'Slow code') : null)),
        createElement('td', {class: 'number'}, formatCount(node.calls)),
        createElement('td', {class: 'number'}, formatMs(node.totalMs)),
        createElement('td', {
            class: 'number',
            title: node.method === null ? 'Time outside your methods: the framework, the server and the database driver' : '',
        }, formatMs(node.selfMs)),
        createElement('td', {class: 'number', title: queries > 0 ? 'Click the row to see these queries below' : ''},
            queries > 0 ? createElement('span', {class: `call-queries ${role ?? ''}`}, String(queries)) : '—'),
        createElement('td', {},
            createElement('div', {class: 'call-share',
                    title: `${roundedPercentText(node.totalMs / context.duration)} in total, `
                    + `${roundedPercentText(node.selfMs / context.duration)} in its own code`},
                createElement('div',
                    {class: 'call-share-total', style: {width: cssPercent(node.totalMs / context.duration)}}),
                createElement('div',
                    {class: 'call-share-self', style: {width: cssPercent(node.selfMs / context.duration)}}))));
}

function showMethodQueries(report, node, highlights) {
    const title = node.method === null ? 'outside your methods' : shortMethod(node.method);
    byId('method-queries-title').textContent = `Queries of ${title}`;
    const rows = node.queryIndexes.map(index => {
        const query = report.queries[index];
        return createElement('tr', {class: highlights[index]},
            createElement('td', {class: 'number'}, String(index + 1)),
            createElement('td', {class: 'number'}, formatMs(query.durationMs)),
            createElement('td', {class: 'number'},
                query.rows === null || query.rows === undefined ? '—' : formatCount(query.rows)),
            createElement('td', {class: 'sql', title: collapseWhitespace(query.sql)},
                query.failed ? createElement('span', {class: 'status-error'}, 'Failed ') : null,
                highlightSql(collapseWhitespace(query.sql))),
            createElement('td', {class: 'params', title: query.parameters || ''}, query.parameters || '—'));
    });
    const queries = createTable(
        [
            {title: '#', width: '52px', number: true},
            {title: 'Duration', width: '90px', number: true},
            {title: 'Rows', width: '76px', number: true},
            {title: 'SQL', width: 'auto'},
            {title: 'Parameters', width: '18%'},
        ],
        rows);
    queries.classList.add('query-table');
    byId('method-queries').replaceChildren(queries);
    byId('method-queries-title').scrollIntoView({block: 'start', behavior: 'smooth'});
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
    const hot = createTable(
        [
            {title: 'Method', width: 'auto'},
            {title: 'Calls', width: '80px', number: true},
            {title: 'Self', width: '90px', number: true},
            {title: 'Queries', width: '80px', number: true},
            {title: 'Share of time', width: '18%'},
        ],
        top.map(entry => createElement('tr', {class: slow.has(entry.method) ? 'slow-method' : ''},
            createElement('td', {}, createElement('div', {class: 'call-name'},
                createElement('code', {title: entry.method}, shortMethod(entry.method)),
                slow.has(entry.method) ? createElement('span', {class: 'label slow-method'}, 'Slow code') : null)),
            createElement('td', {class: 'number'}, formatCount(entry.calls)),
            createElement('td', {class: 'number'}, formatMs(entry.selfMs)),
            createElement('td', {class: 'number'}, entry.queries > 0 ? String(entry.queries) : '—'),
            createElement('td', {},
                createElement('div',
                    {class: 'call-share', title: `${roundedPercentText(entry.selfMs / duration)} of the time`},
                    createElement('div',
                        {class: 'call-share-self', style: {width: cssPercent(entry.selfMs / duration)}}))))));
    hot.classList.add('call-table');
    byId('hot-methods').replaceChildren(hot);
}

function isExpanded(node, depth, path, context) {
    if (node.children.length === 0) {
        return false;
    }
    if (state.callTreeExpanded.has(path)) {
        return state.callTreeExpanded.get(path);
    }
    return depth === 0 || node.totalMs / context.duration >= CALL_EXPAND_SHARE || hasQueries(node);
}

function hasQueries(node) {
    return node.queryIndexes.length > 0 || node.children.some(hasQueries);
}

function createFact(term, value) {
    return createElement('div', {}, createElement('dt', {}, term), createElement('dd', {}, value));
}

function createFindingCards(findings, emptyText) {
    if (findings.length === 0) {
        return [createElement('div', {class: 'finding clean'}, emptyText)];
    }
    return findings.filter(finding => FINDING_TYPES[finding.type]).map(createFindingCard);
}

function createFindingCard(finding) {
    const type = FINDING_TYPES[finding.type];
    return createElement('div', {class: `finding ${type.css}`},
        createElement('div', {class: 'finding-title'}, type.title(finding)),
        finding.sql ? createElement('code', {class: 'sql-block'}, highlightSql(finding.sql)) : null,
        createElement('div', {class: 'finding-meta'}, type.meta(finding)),
        createElement('div', {class: 'finding-hint'}, type.hint));
}

function percentText(part, whole) {
    return `${whole > 0 ? Math.min(100, Math.round(part / whole * 100)) : 0}%`;
}

function findingArea(finding) {
    return FINDING_TYPES[finding.type]?.area ?? 'sql';
}

function createCallSiteLine(callSite) {
    if (!callSite) {
        return 'Location unknown';
    }
    return ['Called from ', createElement('code', {title: callSite}, shortCallSite(callSite))];
}

function createTimeline(report, highlights) {
    const end = Math.max(report.durationMs, ...report.queries.map(query => query.offsetMs + query.durationMs), 0.001);
    const rows = report.queries.map((query, index) => createElement('div', {
            class: 'bar-row',
            title: `#${index + 1} · ${formatMs(query.durationMs)} at +${formatMs(query.offsetMs)}\n`
                + collapseWhitespace(query.sql),
            onclick: () => highlightQuery(index),
        },
        createElement('span', {class: 'bar-index'}, String(index + 1)),
        createElement('div', {class: 'bar-track'},
            createElement('div', {
                class: ['bar', highlights[index], query.failed ? 'failed' : ''].join(' '),
                style: {left: cssPercent(query.offsetMs / end), width: cssPercent(query.durationMs / end)},
            }))));
    return createElement('div', {class: 'panel timeline'},
        createElement('div', {class: 'axis'},
            [0, 0.25, 0.5, 0.75, 1].map(fraction => createElement('span', {}, formatMs(end * fraction)))),
        rows);
}

function createQueryTable(report, highlights) {
    const queryRows = report.queries.map((query, index) => createElement('tr',
        {id: `query-${index}`, class: highlights[index]},
        createElement('td', {class: 'number'}, String(index + 1)),
        createElement('td', {class: 'number'}, `+${formatMs(query.offsetMs)}`),
        createElement('td', {class: 'number'}, formatMs(query.durationMs)),
        createElement('td', {class: 'number'},
            query.rows === null || query.rows === undefined ? '—' : formatCount(query.rows)),
        createElement('td', {class: 'sql', title: collapseWhitespace(query.sql)},
            query.failed ? createElement('span', {class: 'status-error'}, 'Failed ') : null,
            highlightSql(collapseWhitespace(query.sql))),
        createElement('td', {class: 'params', title: query.parameters || ''}, query.parameters || '—'),
        createElement('td', {class: 'call-site', title: query.callSite ?? ''},
            query.callSite ? shortCallSite(query.callSite) : '—')));
    const queries = createTable(
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
    return createElement('div', {class: 'panel'}, queries);
}

function queryHighlightClasses(report) {
    const highlights = report.queries.map(() => '');
    for (const type of HIGHLIGHT_PRIORITY) {
        for (const finding of report.findings.filter(candidate => candidate.type === type)) {
            (finding.queryIndexes ?? [finding.queryIndex]).forEach(index => highlights[index] ||= FINDING_TYPES[type].css);
        }
    }
    return highlights;
}

function highlightQuery(index) {
    document.querySelectorAll('tr.highlight').forEach(other => other.classList.remove('highlight'));
    const row = byId(`query-${index}`);
    if (row) {
        row.classList.add('highlight');
        row.scrollIntoView({block: 'center', behavior: 'smooth'});
    }
}

function highlightSql(sql) {
    const tokens = sql.match(/'(?:[^']|'')*'?|"[^"]*"?|`[^`]*`?|\d+(?:\.\d+)?|\?|[A-Za-z_][\w$]*|\s+|./g) ?? [];
    return tokens.map((token, index) => {
        if (token.startsWith("'")) {
            return createElement('span', {class: 'sql-string'}, token);
        }
        if (/^\d/.test(token)) {
            return createElement('span', {class: 'sql-number'}, token);
        }
        if (token === '?') {
            return createElement('span', {class: 'sql-param'}, token);
        }
        if (/^[A-Za-z_]/.test(token)) {
            if (SQL_KEYWORDS.has(token.toLowerCase())) {
                return createElement('span', {class: 'sql-keyword'}, token);
            }
            if (tokens[index + 1] === '(') {
                return createElement('span', {class: 'sql-function'}, token);
            }
        }
        return token;
    });
}

function createFindingLabel(finding) {
    const type = FINDING_TYPES[finding.type];
    return createElement('span', {class: `label ${type ? type.css : ''}`}, findingText(finding));
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

function collapseWhitespace(sql) {
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

function timeAgo(ms) {
    const seconds = Math.max(0, Math.round(ms / 1000));
    if (seconds < 60) {
        return `${seconds} s ago`;
    }
    const minutes = Math.round(seconds / 60);
    return minutes < 60 ? `${minutes} min ago` : `${Math.round(minutes / 60)} h ago`;
}

function cssPercent(fraction) {
    return `${Math.min(100, Math.max(0, fraction * 100)).toFixed(3)}%`;
}

function roundedPercentText(fraction) {
    return `${Math.round(Math.min(1, Math.max(0, fraction)) * 100)}%`;
}

function resetCanvas(canvas) {
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

