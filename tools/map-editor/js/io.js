// Opening and saving projects (a folder on the PC, or one .praline-map file), exporting the
// .praline-world pack the app plays, and reading a pack back in.
var IO = (function () {
  var S = Model.state;
  var folder = null;          // FileSystemDirectoryHandle of the open project, when the browser allows it
  var savedBlobs = new Map(); // path -> Blob last written to the folder (unchanged files are skipped)
  var PACK_MAX = 25 * 1024 * 1024, ENTRY_MAX = 8 * 1024 * 1024, ENTRIES_MAX = 400;
  var SAFE = /^[A-Za-z0-9._-]{1,80}$/;

  function canUseFolders() { return typeof window.showDirectoryPicker === 'function'; }
  function folderName() { return folder ? folder.name : ''; }

  // ---- pictures --------------------------------------------------------------------------
  async function image(path) {
    if (!path) return null;
    if (S.images.has(path)) return S.images.get(path);
    var b = S.files.get(path);
    if (!b) return null;
    try { var bmp = await createImageBitmap(b); S.images.set(path, bmp); return bmp; } catch (e) { return null; }
  }
  function setFile(path, blob) {
    S.files.set(path, blob);
    var old = S.images.get(path); if (old && old.close) old.close();
    S.images.delete(path);
  }
  function dropFile(path) {
    S.files.delete(path);
    var old = S.images.get(path); if (old && old.close) old.close();
    S.images.delete(path);
  }

  /** Decodes every picture and reads every recipe the project uses (after opening one). */
  async function warm() {
    var paths = [];
    if (S.p.parchment.file) paths.push(S.p.parchment.file);
    if (S.p.art) paths.push(S.p.art.file);
    for (var id in S.p.library) if (S.p.library[id].file) paths.push(S.p.library[id].file);
    await Promise.all(paths.map(image));
    await Promise.all(S.p.spots.map(refreshRecipe));
  }

  async function refreshRecipe(spot) {
    var old = S.recipes.get(spot.id);
    if (old && old.cover) URL.revokeObjectURL(old.cover);
    if (!spot.recipe || !S.files.get(spot.recipe)) { S.recipes.delete(spot.id); return; }
    S.recipes.set(spot.id, await Recipes.describe(S.files.get(spot.recipe)));
  }

  // ---- project files ---------------------------------------------------------------------
  function projectEntries() {
    var out = [{ name: 'project.json', data: JSON.stringify(S.p, null, 1) }];
    S.files.forEach(function (blob, path) { if (isUsed(path)) out.push({ name: path, data: blob }); });
    return out;
  }

  /** Files no longer referenced (a replaced picture, a removed recipe) are left out of saves. */
  function isUsed(path) {
    var p = S.p;
    if (p.parchment.file === path || (p.art && p.art.file === path)) return true;
    for (var id in p.library) if (p.library[id].file === path) return true;
    for (var i = 0; i < p.spots.length; i++) if (p.spots[i].recipe === path) return true;
    return false;
  }

  function loadProject(json, files) {
    var p = JSON.parse(json);
    if (p.format !== 'praline-map-project') throw new Error('this is not a map editor project');
    Model.fresh(p);
    for (var name in files) if (name !== 'project.json') S.files.set(name, files[name] instanceof Blob ? files[name] : new Blob([files[name]]));
  }

  async function openFolder() {
    var dir = await window.showDirectoryPicker({ id: 'praline-map', mode: 'readwrite' });
    var files = {};
    async function walk(d, prefix) {
      for await (var entry of d.values()) {
        if (entry.kind === 'directory') { if (prefix === '' && (entry.name === 'library' || entry.name === 'recipes')) await walk(entry, entry.name + '/'); }
        else files[prefix + entry.name] = await entry.getFile();
      }
    }
    await walk(dir, '');
    if (files['project.json']) {
      loadProject(await files['project.json'].text(), files);
    } else {
      var empty = Object.keys(files).length === 0;
      var ok = await UI.confirm('Start a world here?', empty ? 'This folder is empty. A new world will be started from the template and saved here.' :
        'This folder has no project.json. A new world will be started from the template and saved here, next to the files already in it.', 'Start a world');
      if (!ok) return false;
      Model.fresh(Model.template());
    }
    folder = dir; savedBlobs = new Map();
    S.files.forEach(function (b, path) { savedBlobs.set(path, b); });
    await warm();
    if (!files['project.json']) await save();
    S.dirty = false;
    return true;
  }

  async function openProjectFile(file) {
    var files = await Zip.read(file);
    if (!files['project.json']) throw new Error('this file has no project inside');
    var blobs = {};
    for (var n in files) blobs[n] = new Blob([files[n]]);
    loadProject(new TextDecoder().decode(files['project.json']), blobs);
    folder = null;
    await warm();
    S.dirty = false;
  }

  async function writeFile(dir, path, blob) {
    var parts = path.split('/'), d = dir;
    for (var i = 0; i < parts.length - 1; i++) d = await d.getDirectoryHandle(parts[i], { create: true });
    var fh = await d.getFileHandle(parts[parts.length - 1], { create: true });
    var w = await fh.createWritable();
    await w.write(blob); await w.close();
  }

  /** Saves into the open folder (asking for permission again if the browser dropped it), else downloads a project file. */
  async function save() {
    if (folder) {
      if ((await folder.queryPermission({ mode: 'readwrite' })) !== 'granted' && (await folder.requestPermission({ mode: 'readwrite' })) !== 'granted') {
        throw new Error('the browser did not allow writing to the folder');
      }
      var entries = projectEntries();
      for (var i = 0; i < entries.length; i++) {
        var e = entries[i];
        if (e.name !== 'project.json' && savedBlobs.get(e.name) === e.data) continue;
        await writeFile(folder, e.name, typeof e.data === 'string' ? new Blob([e.data], { type: 'application/json' }) : e.data);
        if (e.name !== 'project.json') savedBlobs.set(e.name, e.data);
      }
      S.dirty = false;
      return 'Saved in the folder "' + folder.name + '".';
    }
    UI.download(await Zip.write(projectEntries()), S.p.world.id + '.praline-map');
    S.dirty = false;
    return 'Project downloaded as ' + S.p.world.id + '.praline-map.';
  }

  async function saveAsFolder() {
    var dir = await window.showDirectoryPicker({ id: 'praline-map', mode: 'readwrite' });
    folder = dir; savedBlobs = new Map();
    return await save();
  }

  function newProject() { Model.fresh(Model.template()); folder = null; savedBlobs = new Map(); }

  // ---- autosave in the browser -------------------------------------------------------------
  function idb() {
    return new Promise(function (res, rej) {
      var r = indexedDB.open('praline-map-editor', 1);
      r.onupgradeneeded = function () { r.result.createObjectStore('drafts'); };
      r.onsuccess = function () { res(r.result); }; r.onerror = function () { rej(r.error); };
    });
  }
  async function autosave() {
    try {
      var db = await idb(), zip = await Zip.write(projectEntries());
      await new Promise(function (res) {
        var tx = db.transaction('drafts', 'readwrite');
        tx.objectStore('drafts').put({ when: Date.now(), name: S.p.world.name, zip: zip }, 'last');
        tx.oncomplete = res; tx.onerror = res;
      });
    } catch (e) { /* private window or storage full: autosave is only a safety net */ }
  }
  async function lastDraft() {
    try {
      var db = await idb();
      return await new Promise(function (res) {
        var r = db.transaction('drafts').objectStore('drafts').get('last');
        r.onsuccess = function () { res(r.result || null); }; r.onerror = function () { res(null); };
      });
    } catch (e) { return null; }
  }
  async function recover(draft) { await openProjectFile(draft.zip); S.dirty = true; }

  // ---- export --------------------------------------------------------------------------------
  /** Flattens parchment + art + baked items into slices and zips the world pack. onStep(text) reports progress. */
  async function exportPack(onStep) {
    var p = S.p, W = Model.W, PPU = Model.PPU, H = p.world.height;
    var parch = p.parchment.file ? await image(p.parchment.file) : await Draw.loadParchment();
    var art = p.art ? await image(p.art.file) : null;
    var baked = p.items.filter(function (it) { return it.bake; });
    await Promise.all(baked.map(function (it) { var l = Model.lib(it.lib); return l && l.file ? image(l.file) : null; }));
    var entries = [], slices = [];
    var n = Math.ceil(H / Model.SLICE);
    for (var k = 0; k < n; k++) {
      onStep && onStep('Painting slice ' + (k + 1) + ' of ' + n + '…');
      var top = k * Model.SLICE, h = Math.min(Model.SLICE, H - top);
      var c = new OffscreenCanvas(W * PPU, Math.round(h * PPU)), g = c.getContext('2d');
      g.fillStyle = '#EEDDBE'; g.fillRect(0, 0, c.width, c.height);
      g.setTransform(PPU, 0, 0, PPU, 0, -top * PPU);
      Draw.drawParchment(g, parch, p.parchment.mode, H, top, top + h);
      if (art) g.drawImage(art, 0, 0, W, art.height * W / art.width);
      ['under', 'over'].forEach(function (layer) {
        Draw.ordered(baked, layer).forEach(function (it) {
          var l = Model.lib(it.lib);
          if (l && l.kind === 'sprite') Draw.drawItem(g, it, l, S.images.get(l.file), 0);
        });
      });
      var blob = await c.convertToBlob({ type: 'image/webp', quality: 0.85 });
      var name = 'slices/s' + ('0' + k).slice(-2) + '.webp';
      entries.push({ name: name, data: blob });
      slices.push({ file: name, top: top, height: h });
    }
    onStep && onStep('Packing the world…');
    var tr = Model.trail(), points = tr.resample(4);
    var world = {
      format: 'praline-world', v: 1, id: p.world.id, kind: p.world.kind, name: p.world.name.trim(), intro: p.world.intro || '',
      width: W, height: H, scale: PPU, title: p.world.title, slices: slices,
      trail: { points: points, length: Math.round(tr.total * 10) / 10, bezier: p.trail },
      spots: [], library: {}, items: [],
      editor: { nextSpot: p.nextSpot, nextItem: p.nextItem }
    };
    p.spots.forEach(function (s) {
      var at = tr.at(s.along);
      var o = { id: s.id, kind: s.kind, name: s.name.trim(), along: round(s.along, 4), x: round(at.x, 1), y: round(at.y, 1), side: s.side, xp: s.xp | 0, story: s.story };
      if (s.kind === 'recipe') {
        o.recipe = 'recipes/' + s.id + '.praline';
        entries.push({ name: o.recipe, data: S.files.get(s.recipe) });
      }
      if (s.icon) o.icon = s.icon;
      if (s.kind === 'story' && s.reward && s.reward.name) o.reward = { kind: s.reward.kind || 'decoration', name: s.reward.name, item: s.reward.item || '' };
      world.spots.push(o);
    });
    Model.usedLibrary().forEach(function (id) {
      var l = Model.lib(id), o = {};
      for (var k in l) if (k !== 'name' && k !== 'file' && k !== 'bake') o[k] = l[k];
      if (l.file) {
        var ext = l.file.split('.').pop().toLowerCase();
        o.file = 'library/' + id + '.' + ext;
        entries.push({ name: o.file, data: S.files.get(l.file) });
      }
      world.library[id] = o;
    });
    p.items.filter(function (it) { return !it.bake; }).forEach(function (it) {
      var o = { id: it.id, lib: it.lib, x: round(it.x, 1), y: round(it.y, 1), scale: it.scale || 1, rot: it.rot || 0, flip: !!it.flip,
        layer: it.layer || 'under', z: it.z || 0, alpha: it.alpha === undefined ? 1 : it.alpha };
      if (it.bob && it.bob.amp) o.bob = it.bob;
      if (it.params && Object.keys(it.params).length) o.params = it.params;
      world.items.push(o);
    });
    entries.unshift({ name: 'world.json', data: JSON.stringify(world) });
    var problems = [];
    for (var i = 0; i < entries.length; i++) {
      var size = entries[i].data.size !== undefined ? entries[i].data.size : new Blob([entries[i].data]).size;
      if (size > ENTRY_MAX) problems.push(entries[i].name + ' is ' + mb(size) + ' (the app takes up to 8 MB per file).');
    }
    if (entries.length > ENTRIES_MAX) problems.push('The pack has ' + entries.length + ' files (the app takes up to ' + ENTRIES_MAX + ').');
    var zip = await Zip.write(entries, 'application/octet-stream');
    if (zip.size > PACK_MAX) problems.push('The pack is ' + mb(zip.size) + '; the app takes up to 25 MB. Use smaller library pictures or a shorter map.');
    return { blob: zip, name: p.world.id + '.praline-world', size: zip.size, slices: slices.length, problems: problems };
  }

  function round(v, d) { var k = Math.pow(10, d); return Math.round(v * k) / k; }
  function mb(n) { return (n / 1048576).toFixed(1) + ' MB'; }

  // ---- reading a pack back in ------------------------------------------------------------------
  /** Opens a .praline-world as a project. The slices become the map art (parchment included). */
  async function importPack(file) {
    if (file.size > PACK_MAX * 2) throw new Error('the file is bigger than any world pack');
    var files = await Zip.read(file, { maxEntries: ENTRIES_MAX, maxEntry: ENTRY_MAX });
    if (!files['world.json']) throw new Error('it is not a world pack');
    var w = JSON.parse(new TextDecoder().decode(files['world.json']));
    if (w.format !== 'praline-world') throw new Error('it is not a world pack');
    if (w.v > 1) throw new Error('it was made by a newer map editor');
    var p = Model.template();
    p.world = { id: w.id, kind: w.kind || 'mission', name: w.name || w.id, intro: w.intro || '', height: w.height,
      title: w.title || { text: '', x: 206, y: 150, size: 40, color: '#3B2414', rotate: 0, font: 'title' } };
    p.trail = w.trail && w.trail.bezier && w.trail.bezier.length > 1 ? w.trail.bezier : Geom.fromPoints(w.trail.points, 12);
    p.parchment = { mode: 'none', file: '' };
    p.items = []; p.library = {}; p.spots = [];
    var out = new Map();   // the project's files, handed over once the whole world is read
    var scale = w.scale || 3;
    // stitch the slices back into one picture
    var c = new OffscreenCanvas(Math.round((w.width || 412) * scale), Math.round(w.height * scale)), g = c.getContext('2d');
    for (var i = 0; i < (w.slices || []).length; i++) {
      var sl = w.slices[i], data = files[sl.file];
      if (!data) continue;
      var bmp = await createImageBitmap(new Blob([data]));
      g.drawImage(bmp, 0, Math.round(sl.top * scale), c.width, Math.round(sl.height * scale));
      bmp.close();
    }
    out.set('art.png', await c.convertToBlob({ type: 'image/png' }));
    p.art = { file: 'art.png' };
    for (var id in (w.library || {})) {
      var l = JSON.parse(JSON.stringify(w.library[id]));
      if (l.file) {
        var base = l.file.split('/').pop();
        if (!SAFE.test(base) || !files[l.file]) continue;
        out.set('library/' + base, new Blob([files[l.file]]));
        l.file = 'library/' + base;
      }
      if (id.indexOf('fx-') === 0) continue;
      l.name = l.name || id;
      p.library[id] = l;
    }
    var maxId = 0;
    (w.spots || []).forEach(function (s) {
      var o = { id: String(s.id), kind: s.kind === 'story' ? 'story' : 'recipe', name: s.name || '', along: s.along, side: s.side === 'right' ? 'right' : 'left',
        xp: s.xp | 0, story: s.story || '', recipe: '', icon: s.icon || '', reward: s.reward || null };
      if (s.recipe && files[s.recipe]) { o.recipe = 'recipes/' + o.id + '.praline'; out.set(o.recipe, new Blob([files[s.recipe]])); }
      p.spots.push(o);
      if (/^\d+$/.test(o.id)) maxId = Math.max(maxId, parseInt(o.id, 10));
    });
    p.nextSpot = Math.max(maxId + 1, (w.editor && w.editor.nextSpot) | 0);
    var maxItem = 0;
    (w.items || []).forEach(function (it) {
      var o = JSON.parse(JSON.stringify(it)); o.bake = false;
      p.items.push(o);
      var m = /^i(\d+)$/.exec(o.id || ''); if (m) maxItem = Math.max(maxItem, parseInt(m[1], 10));
    });
    p.nextItem = Math.max(maxItem + 1, (w.editor && w.editor.nextItem) | 0);
    p.released = true;
    Model.fresh(p);
    out.forEach(function (b, n) { S.files.set(n, b); });
    folder = null;
    await warm();
    S.dirty = true;
  }

  return { canUseFolders: canUseFolders, folderName: folderName, image: image, setFile: setFile, dropFile: dropFile, refreshRecipe: refreshRecipe,
    openFolder: openFolder, openProjectFile: openProjectFile, save: save, saveAsFolder: saveAsFolder, newProject: newProject,
    autosave: autosave, lastDraft: lastDraft, recover: recover, exportPack: exportPack, importPack: importPack, warm: warm };
})();
