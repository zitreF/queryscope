const $ = id => document.getElementById(id);

let snackbarTimer = 0;

function showSnackbar(text, durationMs) {
    $('snackbar-text').textContent = text;
    $('snackbar').hidden = false;
    clearTimeout(snackbarTimer);
    snackbarTimer = setTimeout(() => $('snackbar').hidden = true, durationMs);
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
