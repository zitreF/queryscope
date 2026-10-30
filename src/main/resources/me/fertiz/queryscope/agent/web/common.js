const byId = id => document.getElementById(id);

let snackbarTimer = 0;

function showSnackbar(text, durationMs) {
    byId('snackbar-text').textContent = text;
    byId('snackbar').hidden = false;
    clearTimeout(snackbarTimer);
    snackbarTimer = setTimeout(() => byId('snackbar').hidden = true, durationMs);
}

function countWithNoun(count, singular, plural) {
    return `${count} ${count === 1 ? singular : plural}`;
}

function createElement(tag, attributes, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(attributes)) {
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
