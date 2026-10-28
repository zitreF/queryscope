const REFRESH_MS = 3000;
const SNACK_MS = 5000;

const state = {
    processes: [],
    loaded: false,
    filter: '',
    attaching: new Set(),
    errors: new Map(),
    snackTimer: 0,
};

const $ = id => document.getElementById(id);

$('filter').addEventListener('input', event => {
    state.filter = event.target.value.trim().toLowerCase();
    render();
});

refresh();
setInterval(refresh, REFRESH_MS);

async function refresh() {
    try {
        const response = await fetch('api/processes');
        state.processes = await response.json();
        state.loaded = true;
        setStatus(`${plural(state.processes.length, 'Java process', 'Java processes')}`, true);
    } catch (error) {
        setStatus('The QueryScope hub has stopped', false);
    }
    render();
}

function setStatus(text, live) {
    $('connection').textContent = text;
    document.body.classList.toggle('live', live);
}

function render() {
    if (!state.loaded) {
        return;
    }
    const visible = state.processes.filter(process => state.filter === ''
        || `${process.name} ${process.command} ${process.pid}`.toLowerCase().includes(state.filter));
    if (visible.length === 0) {
        $('processes').replaceChildren(el('p', {class: 'empty'}, state.processes.length === 0
            ? 'No other Java process is running. Start your app and it shows up here.'
            : 'No process matches the filter.'));
        return;
    }
    $('processes').replaceChildren(el('table', {class: 'hub-table'},
        el('colgroup', {},
            el('col', {style: {width: 'auto'}}),
            el('col', {style: {width: '110px'}}),
            el('col', {style: {width: '340px'}})),
        el('thead', {}, el('tr', {},
            el('th', {}, 'Application'),
            el('th', {class: 'number'}, 'PID'),
            el('th', {}, ''))),
        el('tbody', {}, visible.map(row))));
}

function row(process) {
    const error = state.errors.get(process.pid);
    return el('tr', {},
        el('td', {},
            el('div', {class: 'hub-name'}, process.name),
            el('div', {class: 'hub-command', title: process.command}, process.command || '—'),
            error ? el('div', {class: 'hub-error'}, error) : null),
        el('td', {class: 'number'}, String(process.pid)),
        el('td', {}, el('div', {class: 'hub-action'}, action(process))));
}

function action(process) {
    if (process.panelPort) {
        return [
            el('span', {class: 'hub-attached'}, `Attached on port ${process.panelPort}`),
            el('a', {class: 'text-button', href: `http://localhost:${process.panelPort}`, target: '_blank', rel: 'noopener'},
                'Open panel'),
        ];
    }
    const busy = state.attaching.has(process.pid);
    const button = el('button', {type: 'button', class: 'filled-button', onclick: () => attach(process)},
        busy ? 'Attaching…' : 'Attach');
    button.disabled = busy;
    return button;
}

async function attach(process) {
    state.attaching.add(process.pid);
    state.errors.delete(process.pid);
    render();
    try {
        const response = await fetch('api/attach', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({pid: process.pid}),
        });
        const result = await response.json().catch(() => ({}));
        if (!response.ok) {
            throw new Error(result.error || `The hub answered ${response.status}`);
        }
        process.panelPort = result.panelPort;
        snack(`QueryScope is attached to ${process.name}. Its panel is on port ${result.panelPort}.`);
    } catch (error) {
        state.errors.set(process.pid, error.message);
    } finally {
        state.attaching.delete(process.pid);
        render();
    }
}

function snack(text) {
    $('snackbar-text').textContent = text;
    $('snackbar').hidden = false;
    clearTimeout(state.snackTimer);
    state.snackTimer = setTimeout(() => $('snackbar').hidden = true, SNACK_MS);
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
