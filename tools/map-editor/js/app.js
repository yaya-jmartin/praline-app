// Starts the editor: header buttons, keyboard shortcuts, autosave, export and help.
(function () {
  var S = Model.state, el = UI.el;

  async function boot() {
    await Draw.loadFonts();
    MapView.init(document.getElementById('map'), document.getElementById('map-wrap'), function () { if (Panels.current() !== 'library') Panels.showTab(MapView.selection() ? 'inspect' : Panels.current()); else Panels.refresh(); });
    Panels.init(document.getElementById('side'));
    Model.onChange(function (what) {
      MapView.redraw();
      if (what === 'undo' || what === 'library' || what === 'art' || what === 'parchment' || what === 'size') Panels.refresh();
      updateTitle();
    });
    wireHeader();
    document.addEventListener('keydown', keys);
    document.addEventListener('keyup', function (e) { if (e.code === 'Space') MapView.setSpace(false); });
    window.addEventListener('beforeunload', function (e) { if (S.dirty) { e.preventDefault(); e.returnValue = ''; } });
    var draft = await IO.lastDraft();
    if (draft && Date.now() - draft.when < 30 * 24 * 3600 * 1000) {
      var when = new Date(draft.when).toLocaleString();
      var r = await UI.dialog('Pick up where you left off?', el('p', 'The editor kept a copy of "' + (draft.name || 'your world') + '" from ' + when + '.'),
        [{ label: 'Start fresh', value: false }, { label: 'Recover it', value: true, primary: true }]);
      if (r) { await IO.recover(draft); return started('Recovered. Save it to a folder to keep it safe.'); }
    }
    IO.newProject();
    started();
  }

  function started(msg) {
    Model.changed('undo');
    S.dirty = msg ? true : false;
    MapView.fitWidth();
    MapView.scrollToBottom();
    MapView.select(null);
    Panels.showTab('world');
    updateTitle();
    if (msg) UI.toast(msg);
    setInterval(function () { if (S.dirty) IO.autosave(); }, 20000);
    if (!IO.canUseFolders()) UI.toast('This browser cannot save into folders: projects download as .praline-map files. Chrome or Edge can save into a folder.', true);
  }

  function updateTitle() {
    document.getElementById('world-name').textContent = S.p.world.name || 'Untitled world';
    document.getElementById('where').textContent = IO.folderName() ? 'folder: ' + IO.folderName() : 'not saved to a folder yet';
    document.getElementById('dirty').style.visibility = S.dirty ? 'visible' : 'hidden';
    document.title = (S.dirty ? '• ' : '') + (S.p.world.name || 'World') + ' · Praliné map editor';
    document.getElementById('undo').disabled = !S.undo.length;
    document.getElementById('redo').disabled = !S.redo.length;
  }

  async function guard() {
    if (!S.dirty) return true;
    var r = await UI.dialog('Unsaved changes', el('p', 'Save "' + S.p.world.name + '" first?'),
      [{ label: 'Cancel', value: 'cancel' }, { label: "Don't save", value: 'drop', danger: true }, { label: 'Save', value: 'save', primary: true }]);
    if (r === 'save') { await save(); return !S.dirty; }
    return r === 'drop';
  }

  async function run(f, okText) {
    try { var r = await f(); if (r !== false) { Model.changed('undo'); S.dirty = false; updateTitle(); MapView.fitWidth(); MapView.scrollToBottom(); MapView.select(null); Panels.showTab('world'); if (okText) UI.toast(okText); } }
    catch (e) { if (e && e.name === 'AbortError') return; UI.toast('That did not work: ' + (e.message || e), true); }
  }

  async function save() {
    try {
      var msg = IO.folderName() || !IO.canUseFolders() ? await IO.save() : await IO.saveAsFolder();
      updateTitle(); UI.toast(msg);
    } catch (e) { if (e && e.name === 'AbortError') return; UI.toast('Saving did not work: ' + (e.message || e), true); }
  }

  function wireHeader() {
    on('new', async function () { if (await guard()) run(function () { IO.newProject(); }, 'A new world from the template.'); });
    on('open-folder', async function () {
      if (!IO.canUseFolders()) { UI.toast('This browser cannot open folders. Use "Open file" with a .praline-map file, or use Chrome or Edge.', true); return; }
      if (await guard()) run(IO.openFolder, 'Opened.');
    });
    on('open-file', async function () {
      if (!(await guard())) return;
      var f = await UI.pickFile('.praline-map,.praline-world,.zip');
      if (!f) return;
      if (/\.praline-world$/i.test(f.name)) run(function () { return IO.importPack(f); }, 'World pack opened. Its slices are now the map art; save it as a project to keep editing.');
      else run(function () { return IO.openProjectFile(f); }, 'Opened.');
    });
    on('save', save);
    on('export', exportPack);
    on('preview', function () { Preview.open(); });
    on('undo', function () { Model.undo(); });
    on('redo', function () { Model.redo(); });
    on('help', help);
    on('tool-select', function () { tool('select'); });
    on('tool-trail', function () { tool('trail'); });
    on('tool-pan', function () { tool('pan'); });
    on('zoom-in', function () { MapView.zoomBy(1.25); });
    on('zoom-out', function () { MapView.zoomBy(0.8); });
    on('zoom-fit', function () { MapView.fitWidth(); });
    tool('select');
  }
  function on(id, f) { document.getElementById(id).addEventListener('click', f); }

  function tool(t) {
    MapView.setTool(t);
    ['select', 'trail', 'pan'].forEach(function (k) { document.getElementById('tool-' + k).classList.toggle('on', k === t); });
    Panels.status(t === 'trail' ? 'Trail pen: click to extend the trail, drag points and handles, double click a point for a corner.' : t === 'pan' ? 'Hand: drag to move around the map.' : '');
  }

  function typing(e) { var t = e.target; return t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable); }

  function keys(e) {
    var mod = e.ctrlKey || e.metaKey;
    if (mod && e.key.toLowerCase() === 's') { e.preventDefault(); save(); return; }
    if (document.querySelector('.modal-back') || document.querySelector('.preview-back')) return;
    if (mod && e.key.toLowerCase() === 'z' && !typing(e)) { e.preventDefault(); if (e.shiftKey) Model.redo(); else Model.undo(); return; }
    if (mod && e.key.toLowerCase() === 'y' && !typing(e)) { e.preventDefault(); Model.redo(); return; }
    if (typing(e)) return;
    if (mod && e.key.toLowerCase() === 'd') { e.preventDefault(); MapView.duplicate(); return; }
    if (e.code === 'Space') { MapView.setSpace(true); e.preventDefault(); return; }
    var step = e.shiftKey ? 10 : 1;
    switch (e.key) {
      case 'v': case 'V': tool('select'); break;
      case 'p': case 'P': tool('trail'); break;
      case 'h': case 'H': tool('pan'); break;
      case '+': case '=': MapView.zoomBy(1.25); break;
      case '-': MapView.zoomBy(0.8); break;
      case '0': MapView.fitWidth(); break;
      case 'Delete': case 'Backspace': if (MapView.deleteSelection()) e.preventDefault(); break;
      case 'Escape': MapView.select(null); break;
      case 'ArrowLeft': if (MapView.nudge(-step, 0)) e.preventDefault(); break;
      case 'ArrowRight': if (MapView.nudge(step, 0)) e.preventDefault(); break;
      case 'ArrowUp': if (MapView.nudge(0, -step)) e.preventDefault(); break;
      case 'ArrowDown': if (MapView.nudge(0, step)) e.preventDefault(); break;
    }
  }

  async function exportPack() {
    var c = Model.check();
    var list = el('div.check-list', [
      c.errors.length ? el('div.errors', [el('h4', 'To fix before exporting')].concat([el('ul', c.errors.map(function (x) { return el('li', x); }))])) : null,
      c.warnings.length ? el('div.warnings', [el('h4', 'Worth a look')].concat([el('ul', c.warnings.map(function (x) { return el('li', x); }))])) : null,
      !c.errors.length && !c.warnings.length ? el('p', 'Everything looks ready.') : null,
      el('p.hint', 'The pack holds the map slices (parchment, art and baked pictures flattened together), the trail, every spot with its story, recipe and reward, and the library items the map uses.')
    ]);
    var r = await UI.dialog('Export the world pack', list, c.errors.length ? [{ label: 'Close', value: false, primary: true }]
      : [{ label: 'Cancel', value: false }, { label: 'Export ' + S.p.world.id + '.praline-world', value: true, primary: true }], { noFocus: true });
    if (!r) return;
    var prog = el('p', 'Starting…');
    var back = el('div.modal-back', [el('div.modal', [el('div.modal-head', [el('h2', 'Exporting')]), el('div.modal-body', [prog])])]);
    document.body.appendChild(back);
    try {
      var out = await IO.exportPack(function (t) { prog.textContent = t; });
      back.remove();
      if (out.problems.length) {
        await UI.dialog('The pack is too heavy', el('ul', out.problems.map(function (x) { return el('li', x); })), [{ label: 'OK', value: true, primary: true }]);
        return;
      }
      UI.download(out.blob, out.name);
      UI.toast(out.name + ' exported: ' + out.slices + ' slices, ' + (out.size / 1048576).toFixed(1) + ' MB.');
    } catch (e) {
      back.remove();
      UI.toast('The export did not work: ' + (e.message || e), true);
    }
  }

  function help() {
    UI.dialog('How the editor works', el('div.help', [
      el('h4', 'The map'),
      el('ul', [
        el('li', 'Scroll to move up and down, Ctrl + scroll (or + and -) to zoom, 0 to fit the width. Hold Space and drag to move around.'),
        el('li', 'The map is 412 units wide; art is painted at 3 px per unit, so 1236 px wide.')
      ]),
      el('h4', 'The trail (P)'),
      el('ul', [
        el('li', 'Click on empty map to extend the trail from its end (Shift + click: from its start). Drag while clicking to pull out a curve.'),
        el('li', 'Drag the square points to move them and the round handles to bend the curve. Alt + drag a handle for a sharp corner.'),
        el('li', 'Click on the trail to add a point; Delete removes the selected point. Double click a point to switch smooth and corner.'),
        el('li', 'The party walks from the start (green) to the end (pink).')
      ]),
      el('h4', 'Spots (V)'),
      el('ul', [
        el('li', 'Double click on the trail to add a spot; double click a spot to remove it. Drag a spot to slide it along the trail.'),
        el('li', 'A recipe spot tells its story when the party arrives, then its recipe joins the grimoire; the trail stays locked until it is cooked.'),
        el('li', 'A story stop tells its story and can give a reward; nothing to cook.'),
        el('li', 'Recipes: upload a .praline made on the phone, or create one with the quick form (add a cover picture: it is the players\' hint).')
      ]),
      el('h4', 'Library'),
      el('ul', [
        el('li', 'Import still pictures and sprite sheets (PNG or WebP), then drag them onto the map. Effects (rays, glow, smoke…) need no art.'),
        el('li', 'Bake still pictures that never move: they are painted into the map and cost the phone nothing.')
      ]),
      el('h4', 'Saving and exporting'),
      el('ul', [
        el('li', 'Save (Ctrl + S) keeps the project in a folder you choose, with every picture and recipe beside it. The editor also keeps a safety copy in the browser.'),
        el('li', 'Preview shows the map as the phone will. Export makes the .praline-world pack Praliné reads.'),
        el('li', 'Undo with Ctrl + Z, redo with Ctrl + Shift + Z.')
      ])
    ]), [{ label: 'Got it', value: true, primary: true }], { wide: true });
  }

  window.addEventListener('load', boot);
})();
