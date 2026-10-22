// Tiny DOM helpers shared by every panel: element builder, dialogs, toasts, form rows.
var UI = (function () {
  /** el('div.card#id', {onclick: f, value: 1}, [children or text]) */
  function el(spec, attrs, kids) {
    if (Array.isArray(attrs) || typeof attrs === 'string' || typeof attrs === 'number' || attrs instanceof Node) { kids = attrs; attrs = null; }
    var m = spec.match(/^([a-z0-9]+)?((?:[.#][\w-]+)*)$/i);
    var node = document.createElement(m[1] || 'div');
    (m[2].match(/[.#][\w-]+/g) || []).forEach(function (p) {
      if (p[0] === '.') node.classList.add(p.substring(1)); else node.id = p.substring(1);
    });
    if (attrs) for (var k in attrs) {
      var v = attrs[k];
      if (v === undefined || v === null || v === false) continue;
      if (k.indexOf('on') === 0 && typeof v === 'function') node.addEventListener(k.substring(2), v);
      else if (k === 'style' && typeof v === 'object') Object.assign(node.style, v);
      else if (k === 'value' || k === 'checked' || k === 'disabled' || k === 'selected') node[k] = v;
      else node.setAttribute(k, v === true ? '' : v);
    }
    add(node, kids);
    return node;
  }
  function add(node, kids) {
    if (kids === undefined || kids === null) return;
    if (!Array.isArray(kids)) kids = [kids];
    kids.forEach(function (k) {
      if (k === null || k === undefined || k === false) return;
      node.appendChild(typeof k === 'string' || typeof k === 'number' ? document.createTextNode(String(k)) : k);
    });
  }
  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); return node; }

  /** A labelled row: label on top, control below, optional hint under it. */
  function row(label, control, hint) {
    return el('label.row', [el('span.lab', label), control, hint ? el('span.hint', hint) : null]);
  }

  /** Modal dialog. buttons: [{label, value, primary, danger}]; resolves with the clicked value (or null). */
  function dialog(title, body, buttons, opts) {
    opts = opts || {};
    return new Promise(function (resolve) {
      var back = el('div.modal-back');
      function close(v) { document.removeEventListener('keydown', key, true); back.remove(); resolve(v); }
      function key(e) {
        if (e.key === 'Escape') { e.stopPropagation(); close(null); }
      }
      var box = el('div.modal' + (opts.wide ? '.wide' : ''), [
        el('div.modal-head', [el('h2', title), el('button.icon', { title: 'Close', onclick: function () { close(null); } }, '×')]),
        el('div.modal-body', body),
        el('div.modal-foot', (buttons || [{ label: 'OK', value: true, primary: true }]).map(function (b) {
          return el('button' + (b.primary ? '.primary' : '') + (b.danger ? '.danger' : ''), {
            onclick: async function () {
              if (b.check) { var ok = await b.check(); if (!ok) return; }
              close(b.value);
            }
          }, b.label);
        }))
      ]);
      back.appendChild(box);
      back.addEventListener('mousedown', function (e) { if (e.target === back && !opts.sticky) close(null); });
      document.addEventListener('keydown', key, true);
      document.body.appendChild(back);
      var first = box.querySelector('input, textarea, select');
      if (first && !opts.noFocus) first.focus();
    });
  }

  function confirm(title, text, yes, danger) {
    return dialog(title, el('p', text), [{ label: 'Cancel', value: false }, { label: yes || 'OK', value: true, primary: !danger, danger: danger }]);
  }

  var toastTimer = null;
  function toast(text, bad) {
    var t = document.getElementById('toast');
    t.textContent = text;
    t.className = 'show' + (bad ? ' bad' : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.className = ''; }, bad ? 6000 : 3200);
  }

  /** Asks for a file with an <input type=file>; resolves with a File, or null. */
  function pickFile(accept, multiple) {
    return new Promise(function (resolve) {
      var i = el('input', { type: 'file', accept: accept, multiple: multiple, style: { display: 'none' } });
      i.addEventListener('change', function () { resolve(multiple ? Array.from(i.files) : i.files[0] || null); i.remove(); });
      i.addEventListener('cancel', function () { resolve(null); i.remove(); });
      document.body.appendChild(i);
      i.click();
    });
  }

  function download(blob, name) {
    var a = el('a', { href: URL.createObjectURL(blob), download: name });
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(function () { URL.revokeObjectURL(a.href); }, 20000);
  }

  function num(v, def) { var n = parseFloat(v); return isFinite(n) ? n : def; }

  return { el: el, add: add, clear: clear, row: row, dialog: dialog, confirm: confirm, toast: toast, pickFile: pickFile, download: download, num: num };
})();
