const MAX_REPORTS = 200;

const state = {
    reports: [],
    selectedId: null,
    follow: true,
    onlyFindings: false,
    freshIds: new Set(),
    renderedId: undefined,
};

const list = document.getElementById('request-list');
const noRequests = document.getElementById('no-requests');
const details = document.getElementById('details');
const stats = document.getElementById('stats');
const connection = document.getElementById('connection');
const onlyFindings = document.getElementById('only-findings');

onlyFindings.addEventListener('change', () => {
    state.onlyFindings = onlyFindings.checked;
    render();
});

document.getElementById('clear').addEventListener('click', async () => {
    await fetch('api/requests', {method: 'DELETE'});
    state.reports = [];
    state.selectedId = null;
    state.follow = true;
    render();
});

render();
connect();

function connect() {
    const events = new EventSource('api/events');
    let buffered = null;

    events.addEventListener('open', async () => {
        buffered = [];
        try {
            const response = await fetch('api/requests');
            const stored = await response.json();
            state.reports = newestUnique([...buffered.reverse(), ...stored]);
            render();
            setConnection(true);
        } finally {
            buffered = null;
        }
    });

    events.addEventListener('request', event => {
        const report = JSON.parse(event.data);
        if (buffered) {
            buffered.push(report);
            return;
        }
        state.reports = newestUnique([report, ...state.reports]);
        state.freshIds.add(report.id);
        render();
    });

    events.addEventListener('error', () => setConnection(false));
}

function newestUnique(reports) {
    const seen = new Set();
    return reports
        .filter(report => !seen.has(report.id) && seen.add(report.id))
        .slice(0, MAX_REPORTS);
}

function setConnection(live) {
    connection.textContent = live ? 'live' : 'reconnecting';
    connection.classList.toggle('live', live);
}

function render() {
    const visible = state.reports.filter(report => !state.onlyFindings || report.findings.length > 0);
    if (state.follow || !visible.some(report => report.id === state.selectedId)) {
        state.selectedId = visible.length > 0 ? visible[0].id : null;
    }
    renderList(visible);
    renderStats();
    const selected = state.reports.find(report => report.id === state.selectedId) ?? null;
    if ((selected?.id ?? null) !== state.renderedId) {
        renderDetails(selected);
        state.renderedId = selected?.id ?? null;
    }
}

function renderList(visible) {
    list.replaceChildren(...visible.map((report, index) => requestItem(report, index)));
    noRequests.hidden = visible.length > 0;
    noRequests.textContent = state.reports.length > 0
        ? 'No requests with findings yet.'
        : 'Waiting for HTTP requests that run SQL.';
    state.freshIds.clear();
}

function renderStats() {
    const withNPlusOne = state.reports.filter(report => report.findings.some(isNPlusOne)).length;
    const withSlow = state.reports.filter(report => report.findings.some(finding => !isNPlusOne(finding))).length;
    stats.textContent = `${plural(state.reports.length, 'request', 'requests')} · ${withNPlusOne} with N+1 · ${withSlow} with slow queries`;
}

function requestItem(report, index) {
    const nPlusOnes = report.findings.filter(isNPlusOne);
    const slowCount = report.findings.length - nPlusOnes.length;
    const classes = ['request'];
    if (report.id === state.selectedId) {
        classes.push('selected');
    }
    if (state.freshIds.has(report.id)) {
        classes.push('fresh');
    }
    return el('li', {
            class: classes.join(' '),
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
            el('span', {class: report.status >= 400 ? 'status-error' : ''}, String(report.status)),
            el('span', {}, plural(report.queries.length, 'query', 'queries')),
            el('span', {}, formatMs(report.durationMs)),
            nPlusOnes.map(finding => el('span', {class: 'badge n-plus-one'}, `N+1 ×${finding.executions}`)),
            slowCount > 0 ? el('span', {class: 'badge slow'}, slowCount === 1 ? 'slow query' : `${slowCount} slow`) : null));
}

function renderDetails(report) {
    if (!report) {
        details.replaceChildren(el('p', {class: 'placeholder'}, 'Requests that run SQL show up here as they happen.'));
        return;
    }
    const roles = queryRoles(report);
    details.replaceChildren(
        el('h1', {}, el('span', {class: 'method'}, report.method), el('span', {}, report.path)),
        el('p', {class: 'summary-meta'}, [
            `HTTP ${report.status}`,
            formatMs(report.durationMs),
            plural(report.queries.length, 'query', 'queries'),
            new Date(report.startedAt).toLocaleTimeString(),
        ].join(' · ')),
        el('h2', {}, 'Findings'),
        ...findingCards(report),
        el('h2', {}, 'Timeline'),
        timeline(report, roles),
        el('h2', {}, 'Queries'),
        queryTable(report, roles));
}

function findingCards(report) {
    if (report.findings.length === 0) {
        return [el('div', {class: 'finding clean'}, 'No N+1 or slow queries in this request.')];
    }
    return report.findings.map(finding => isNPlusOne(finding) ? nPlusOneCard(finding) : slowQueryCard(finding));
}

function nPlusOneCard(finding) {
    return el('div', {class: 'finding n-plus-one'},
        el('div', {class: 'finding-title'}, `N+1: the same query ran ${finding.executions} times`),
        el('code', {class: 'sql'}, finding.sql),
        el('div', {class: 'finding-meta'}, callSiteLine(finding.callSite), ` · ${formatMs(finding.totalMs)} in total`),
        el('div', {class: 'finding-meta'},
            'Load the rows in one query (join fetch, @EntityGraph or batch fetching) instead of one query per parent row.'));
}

function slowQueryCard(finding) {
    return el('div', {class: 'finding slow'},
        el('div', {class: 'finding-title'}, `Slow query: ${formatMs(finding.durationMs)}`),
        el('code', {class: 'sql'}, finding.sql),
        el('div', {class: 'finding-meta'}, callSiteLine(finding.callSite), ` · query #${finding.queryIndex + 1}`));
}

function callSiteLine(callSite) {
    if (!callSite) {
        return 'call site unknown';
    }
    return ['at ', el('code', {title: callSite}, shortCallSite(callSite))];
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
    return el('div', {class: 'timeline'},
        el('div', {class: 'axis'}, [0, 0.25, 0.5, 0.75, 1].map(fraction => el('span', {}, formatMs(end * fraction)))),
        rows,
        el('div', {class: 'legend'},
            el('span', {}, 'query'),
            el('span', {class: 'n-plus-one'}, 'N+1'),
            el('span', {class: 'slow'}, 'slow')));
}

function queryTable(report, roles) {
    return el('table', {},
        el('colgroup', {},
            el('col', {style: {width: '48px'}}),
            el('col', {style: {width: '88px'}}),
            el('col', {style: {width: '88px'}}),
            el('col', {}),
            el('col', {style: {width: '30%'}})),
        el('thead', {}, el('tr', {}, ['#', 'Start', 'Duration', 'SQL', 'Call site'].map(title => el('th', {}, title)))),
        el('tbody', {}, report.queries.map((query, index) => el('tr', {id: `query-${index}`, class: roles[index]},
            el('td', {class: 'number'}, String(index + 1)),
            el('td', {class: 'number'}, `+${formatMs(query.offsetMs)}`),
            el('td', {class: 'number'}, formatMs(query.durationMs)),
            el('td', {class: 'sql', title: query.sql}, (query.failed ? 'failed: ' : '') + oneLine(query.sql)),
            el('td', {class: 'call-site', title: query.callSite ?? ''}, query.callSite ? shortCallSite(query.callSite) : '')))));
}

function queryRoles(report) {
    const roles = report.queries.map(() => '');
    for (const finding of report.findings) {
        if (isNPlusOne(finding)) {
            finding.queryIndexes.forEach(index => roles[index] = 'n-plus-one');
        }
    }
    for (const finding of report.findings) {
        if (!isNPlusOne(finding)) {
            roles[finding.queryIndex] = 'slow';
        }
    }
    return roles;
}

function highlightQuery(index) {
    const row = document.getElementById(`query-${index}`);
    row.classList.remove('highlight');
    void row.offsetWidth;
    row.classList.add('highlight');
    row.scrollIntoView({block: 'center', behavior: 'smooth'});
}

function isNPlusOne(finding) {
    return finding.type === 'nPlusOne';
}

function shortCallSite(callSite) {
    const open = callSite.indexOf('(');
    const qualified = open < 0 ? callSite : callSite.slice(0, open);
    return qualified.split('.').slice(-2).join('.') + (open < 0 ? '' : callSite.slice(open));
}

function oneLine(sql) {
    return sql.replace(/\s+/g, ' ').trim();
}

function formatMs(ms) {
    if (ms >= 100) {
        return `${ms.toFixed(0)} ms`;
    }
    return `${ms.toFixed(ms >= 10 ? 1 : 2)} ms`;
}

function percent(fraction) {
    return `${Math.min(100, Math.max(0, fraction * 100)).toFixed(3)}%`;
}

function plural(count, one, many) {
    return `${count} ${count === 1 ? one : many}`;
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
