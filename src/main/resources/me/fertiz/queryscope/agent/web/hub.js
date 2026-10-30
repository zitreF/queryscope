const REFRESH_MS = 3000;
const SNACK_MS = 5000;

const state = {
    processes: null,
    filter: '',
    attaching: new Set(),
    errors: new Map(),
    refreshSequence: 0,
};

byId('filter').addEventListener('input', event => {
    state.filter = event.target.value.trim().toLowerCase();
    renderProcessTable();
});

refreshProcessesRepeatedly();

async function refreshProcessesRepeatedly() {
    await refreshProcesses();
    setTimeout(refreshProcessesRepeatedly, REFRESH_MS);
}

async function refreshProcesses() {
    const sequence = ++state.refreshSequence;
    const {processes, problem} = await fetchProcesses();
    if (sequence !== state.refreshSequence) {
        return;
    }
    if (processes) {
        state.processes = processes;
        setHubStatus(countWithNoun(processes.length, 'Java process', 'Java processes'), true);
    } else {
        setHubStatus(problem, false);
    }
    renderProcessTable();
}

async function fetchProcesses() {
    try {
        const response = await fetch('api/processes');
        return response.ok ? {processes: await response.json()} : {problem: `The hub answered ${response.status}`};
    } catch (error) {
        return {problem: 'The QueryScope hub has stopped'};
    }
}

function setHubStatus(text, live) {
    byId('connection').textContent = text;
    document.body.classList.toggle('live', live);
}

function renderProcessTable() {
    if (state.processes === null) {
        return;
    }
    const visible = state.processes.filter(process => state.filter === ''
        || `${process.name} ${process.command} ${process.pid}`.toLowerCase().includes(state.filter));
    if (visible.length === 0) {
        byId('processes').replaceChildren(createElement('p', {class: 'empty'}, state.processes.length === 0
            ? 'No other Java process is running. Start your app and it shows up here.'
            : 'No process matches the filter.'));
        return;
    }
    byId('processes').replaceChildren(createElement('table', {class: 'hub-table'},
        createElement('colgroup', {},
            createElement('col', {style: {width: 'auto'}}),
            createElement('col', {style: {width: '110px'}}),
            createElement('col', {style: {width: '340px'}})),
        createElement('thead', {}, createElement('tr', {},
            createElement('th', {}, 'Application'),
            createElement('th', {class: 'number'}, 'PID'),
            createElement('th', {}, ''))),
        createElement('tbody', {}, visible.map(createProcessRow))));
}

function createProcessRow(process) {
    const error = state.errors.get(process.pid);
    return createElement('tr', {},
        createElement('td', {},
            createElement('div', {class: 'hub-name'}, process.name),
            createElement('div', {class: 'hub-command', title: process.command}, process.command || '—'),
            error ? createElement('div', {class: 'hub-error'}, error) : null),
        createElement('td', {class: 'number'}, String(process.pid)),
        createElement('td', {}, createElement('div', {class: 'hub-action'}, createAttachOrOpenControls(process))));
}

function createAttachOrOpenControls(process) {
    if (process.panelPort) {
        return [
            createElement('span', {class: 'hub-attached'}, `Attached on port ${process.panelPort}`),
            createElement('a', {class: 'text-button', href: `http://localhost:${process.panelPort}`, target: '_blank', rel: 'noopener'},
                'Open panel'),
        ];
    }
    const busy = state.attaching.has(process.pid);
    const button = createElement('button', {type: 'button', class: 'filled-button', onclick: () => attachTo(process)},
        busy ? 'Attaching…' : 'Attach');
    button.disabled = busy;
    return button;
}

async function attachTo(process) {
    state.attaching.add(process.pid);
    state.errors.delete(process.pid);
    renderProcessTable();
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
        showSnackbar(`QueryScope is attached to ${process.name}. Its panel is on port ${result.panelPort}.`, SNACK_MS);
    } catch (error) {
        state.errors.set(process.pid, error.message);
    } finally {
        state.attaching.delete(process.pid);
        await refreshProcesses();
    }
}

