// The side panels: World (name, title, size, parchment, art), the inspector for whatever is selected
// (spot, item, title, trail point) and the Library (effects and your own pictures and animations).
var Panels = (function () {
  var S = Model.state, el = UI.el;
  var root, tabs, bodies = {}, current = 'world';
  var parchmentDefault = null;

  function defaultParchment() { return parchmentDefault; }

  function init(container) {
    root = container;
    Draw.loadParchment().then(function (b) { parchmentDefault = b; MapView.redraw(); });
    tabs = el('div.tabs', [['world', 'World'], ['inspect', 'Selected'], ['library', 'Library']].map(function (t) {
      return el('button.tab', { 'data-tab': t[0], onclick: function () { showTab(t[0]); } }, t[1]);
    }));
    root.appendChild(tabs);
    ['world', 'inspect', 'library'].forEach(function (k) { bodies[k] = el('div.panel-body'); root.appendChild(bodies[k]); });
    showTab('world');
  }

  function showTab(k) {
    current = k;
    Array.from(tabs.children).forEach(function (b) { b.classList.toggle('on', b.dataset.tab === k); });
    for (var n in bodies) bodies[n].style.display = n === k ? '' : 'none';
    refresh();
  }

  function refresh() {
    if (!S.p) return;
    if (current === 'world') world();
    if (current === 'inspect') inspect(MapView.selection());
    if (current === 'library') library();
  }

  function status(text) { document.getElementById('status').textContent = text; }

  /** Applies an edit with undo, then redraws the map. */
  function edit(f, what) { Model.snapshot(); f(); Model.changed(what || 'all'); }

  // small controls bound to an object field --------------------------------------------------
  function text(obj, key, opts) {
    opts = opts || {};
    var i = el(opts.area ? 'textarea' : 'input', { type: 'text', value: obj[key] || '', maxlength: opts.max, placeholder: opts.placeholder, rows: opts.rows });
    var before = null;
    i.addEventListener('focus', function () { before = JSON.stringify(S.p); });
    i.addEventListener('input', function () {
      var v = opts.clean ? opts.clean(i.value) : i.value;
      obj[key] = v; if (opts.onInput) opts.onInput(v);
      S.dirty = true; Model.changed(opts.what || 'text');
    });
    i.addEventListener('change', function () {
      if (before && before !== JSON.stringify(S.p)) { S.undo.push(before); S.redo.length = 0; }
      before = JSON.stringify(S.p);
      if (opts.onChange) opts.onChange(obj[key]);
    });
    return i;
  }
  function number(obj, key, opts) {
    opts = opts || {};
    var i = el('input', { type: 'number', value: obj[key] === undefined ? '' : obj[key], step: opts.step || 1, min: opts.min, max: opts.max });
    i.addEventListener('change', function () {
      var v = UI.num(i.value, opts.def !== undefined ? opts.def : 0);
      if (opts.min !== undefined) v = Math.max(opts.min, v);
      if (opts.max !== undefined) v = Math.min(opts.max, v);
      if (opts.int) v = Math.round(v);
      i.value = v;
      edit(function () { obj[key] = v; }, opts.what);
      if (opts.onChange) opts.onChange(v);
    });
    return i;
  }
  function select(obj, key, options, opts) {
    opts = opts || {};
    var s = el('select', options.map(function (o) { return el('option', { value: o[0], selected: String(obj[key]) === String(o[0]) }, o[1]); }));
    s.addEventListener('change', function () { edit(function () { obj[key] = opts.num ? Number(s.value) : s.value; }, opts.what); if (opts.onChange) opts.onChange(s.value); });
    return s;
  }
  function check(obj, key, label, opts) {
    opts = opts || {};
    var c = el('input', { type: 'checkbox', checked: !!obj[key] });
    c.addEventListener('change', function () { edit(function () { obj[key] = c.checked; }, opts.what); if (opts.onChange) opts.onChange(c.checked); });
    return el('label.check', [c, ' ' + label]);
  }
  function color(obj, key, opts) {
    var hex = (obj[key] || '#000000').replace(/^#([0-9a-f]{2})([0-9a-f]{6})$/i, '#$2');
    var c = el('input', { type: 'color', value: hex.length === 7 ? hex : '#000000' });
    c.addEventListener('change', function () { edit(function () { obj[key] = c.value.toUpperCase(); }, opts && opts.what); });
    return c;
  }
  function section(title, kids, open) {
    return el('details.sec', { open: open !== false }, [el('summary', title)].concat(kids));
  }

  // ---- World -------------------------------------------------------------------------------
  function world() {
    var p = S.p, w = p.world, b = UI.clear(bodies.world);
    var counts = { recipe: 0, story: 0 };
    p.spots.forEach(function (s) { counts[s.kind]++; });
    var th = Model.thresholds();
    b.appendChild(section('World', [
      UI.row('Name', text(w, 'name', { max: 40, onChange: function () { refreshHeader(); } })),
      UI.row('World id', text(w, 'id', { max: 32, clean: function (v) { return v.toLowerCase().replace(/[^a-z0-9-]/g, ''); } }),
        'Lowercase letters, digits and dashes. Mission progress is kept under this id, so never change it after the world ships.'),
      UI.row('Opening story (optional)', text(w, 'intro', { area: true, rows: 3, max: Model.STORY_MAX, placeholder: 'Shown when the party first opens this world.' })),
      el('p.summary', counts.recipe + ' recipe spots · ' + counts.story + ' story stops · ' + (th[th.length - 1] || 0) + ' fame to the end · ' +
        p.items.filter(function (i) { return !i.bake; }).length + ' live items')
    ]));
    b.appendChild(section('Title over the map', [
      UI.row('Text', text(w.title, 'text', { max: 40, what: 'title' })),
      el('div.grid2', [
        UI.row('Lettering', select(w.title, 'font', [['title', 'Mea Culpa (script)'], ['body', 'Fraunces (serif)']], { what: 'title' })),
        UI.row('Colour', color(w.title, 'color', { what: 'title' })),
        UI.row('Size', number(w.title, 'size', { min: 10, max: 120, what: 'title' })),
        UI.row('Tilt (°)', number(w.title, 'rotate', { min: -45, max: 45, what: 'title' }))
      ]),
      el('p.hint', 'Drag the title on the map to move it. The app letters it live, so you can rename the world without repainting.')
    ], false));
    b.appendChild(section('Map size', [
      UI.row('Height (map units)', number(w, 'height', { min: 1000, max: 6000, int: true, what: 'size', onChange: function () { MapView.redraw(); world(); } }),
        Math.round(w.height * Model.PPU) + ' px tall at 3×, ' + Math.ceil(w.height / Model.SLICE) + ' slices of 1236 × 1575 px. 2,400 to 4,800 scrolls best.'),
      el('p.hint', 'The map is always 412 units wide: 1236 px of art.')
    ], false));
    b.appendChild(section('Parchment', parchmentControls(), false));
    b.appendChild(section('Map art', artControls(), true));
    b.appendChild(section('Show', ['parchment', 'art', 'items', 'trail', 'spots', 'title', 'slices', 'animate'].map(function (k) {
      var labels = { parchment: 'Parchment', art: 'Map art', items: 'Items and effects', trail: 'Trail', spots: 'Spots', title: 'Title', slices: 'Slice lines', animate: 'Animate' };
      var c = el('input', { type: 'checkbox', checked: MapView.show[k] });
      c.addEventListener('change', function () { MapView.show[k] = c.checked; MapView.redraw(); });
      return el('label.check', [c, ' ' + labels[k]]);
    }), false));
    b.appendChild(section('After shipping', [
      check(p, 'released', 'This world has shipped', { onChange: function () { world(); } }),
      el('p.hint', 'Once a world is on the phones, removing or reordering spots moves parties that are past them. The editor asks before those changes.')
    ], false));
  }

  function parchmentControls() {
    var p = S.p;
    var thumb = el('canvas.thumb', { width: 120, height: 150 });
    var img = p.parchment.file ? S.images.get(p.parchment.file) : parchmentDefault;
    if (img) thumb.getContext('2d').drawImage(img, 0, 0, 120, 150);
    return [
      el('div.thumb-row', [thumb, el('div', [
        el('p.hint', p.parchment.file ? 'Your parchment.' : 'The default parchment.'),
        UI.row('Use it as', select(p.parchment, 'mode', [['tile', 'A tile repeated down the map'], ['full', 'One picture for the whole map'], ['none', 'Nothing (the art has its own)']], { what: 'parchment' }))
      ])]),
      el('div.btns', [
        el('button', { onclick: replaceParchment }, 'Replace parchment…'),
        p.parchment.file ? el('button', { onclick: function () { edit(function () { p.parchment.file = ''; }, 'parchment'); world(); } }, 'Back to the default') : null
      ]),
      el('p.hint', 'A tile is 1236 px wide and seamless from top to bottom (the default is 1236 × 1575). Every slice of the map sits on it.')
    ];
  }

  async function replaceParchment() {
    var f = await UI.pickFile('image/png,image/webp,image/jpeg');
    if (!f) return;
    var bmp;
    try { bmp = await createImageBitmap(f); } catch (e) { UI.toast('That picture could not be read.', true); return; }
    if (bmp.width !== 1236) UI.toast('This parchment is ' + bmp.width + ' px wide; it will be stretched to 1236. Paint at 1236 px for the sharpest result.', true);
    var ext = f.type === 'image/png' ? 'png' : f.type === 'image/jpeg' ? 'jpg' : 'webp';
    var path = 'parchment-' + Date.now().toString(36) + '.' + ext;
    IO.setFile(path, f);
    await IO.image(path);
    edit(function () { S.p.parchment.file = path; if (S.p.parchment.mode === 'none') S.p.parchment.mode = 'tile'; if (bmp.height * 412 / bmp.width >= S.p.world.height * 0.9) S.p.parchment.mode = 'full'; }, 'parchment');
    bmp.close();
    world();
  }

  function artControls() {
    var p = S.p, out = [];
    var art = p.art ? S.images.get(p.art.file) : null;
    if (art) {
      out.push(el('p.hint', 'Your art: ' + art.width + ' × ' + art.height + ' px, ' + Math.round(art.height * 412 / art.width) + ' units tall.'));
      var op = el('input', { type: 'range', min: 0, max: 1, step: 0.05, value: 1 });
      op.addEventListener('input', function () { MapView.setArtOpacity(Number(op.value)); });
      out.push(UI.row('See-through while editing', op));
    } else {
      out.push(el('p.hint', 'No art yet. Paint one tall picture, 1236 px wide and as tall as the map, with a transparent background so the parchment shows through.'));
    }
    out.push(el('div.btns', [
      el('button.primary', { onclick: importArt }, p.art ? 'Replace art…' : 'Import art…'),
      p.art ? el('button', { onclick: function () { edit(function () { p.art = null; }, 'art'); world(); } }, 'Remove art') : null,
      el('button', { onclick: paintingGuide, title: 'A 1236 px wide PNG with the parchment, trail, spots, title and slice lines, to paint over.' }, 'Download painting guide')
    ]));
    return out;
  }

  async function importArt() {
    var f = await UI.pickFile('image/png,image/webp,image/jpeg');
    if (!f) return;
    var bmp;
    try { bmp = await createImageBitmap(f); } catch (e) { UI.toast('That picture could not be read (it may be too big for the browser).', true); return; }
    var blob = f, w = bmp.width, h = bmp.height;
    if (w !== 1236) {
      var ok = await UI.confirm('Resize the art?', 'This picture is ' + w + ' px wide. Map art is 1236 px wide. Resize it to 1236 px (keeping its shape)?', 'Resize');
      if (!ok) { bmp.close(); return; }
      var k = 1236 / w, c = new OffscreenCanvas(1236, Math.round(h * k));
      c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
      blob = await c.convertToBlob({ type: 'image/png' }); w = c.width; h = c.height;
    }
    bmp.close();
    var units = Math.round(h / Model.PPU);
    var path = 'art-' + Date.now().toString(36) + '.' + (blob.type === 'image/jpeg' ? 'jpg' : blob.type === 'image/webp' ? 'webp' : 'png');
    IO.setFile(path, blob);
    await IO.image(path);
    var setHeight = false;
    if (Math.abs(units - S.p.world.height) > 3) {
      setHeight = await UI.confirm('Match the map height?', 'The art is ' + units + ' units tall and the map is ' + S.p.world.height + '. Set the map height to ' + units + '?', 'Set height');
    }
    edit(function () { S.p.art = { file: path }; if (setHeight) S.p.world.height = Math.max(1000, Math.min(6000, units)); }, 'art');
    world();
  }

  async function paintingGuide() {
    UI.toast('Drawing the painting guide…');
    var p = S.p, H = p.world.height, PPU = Model.PPU;
    var c = new OffscreenCanvas(Model.W * PPU, H * PPU), g = c.getContext('2d');
    g.setTransform(PPU, 0, 0, PPU, 0, 0);
    Draw.drawParchment(g, p.parchment.file ? S.images.get(p.parchment.file) : parchmentDefault, p.parchment.mode === 'none' ? 'tile' : p.parchment.mode, H, 0, H);
    var tr = Model.trail();
    g.lineCap = 'round'; g.lineJoin = 'round';
    g.beginPath(); tr.pts.forEach(function (q, i) { if (i) g.lineTo(q.x, q.y); else g.moveTo(q.x, q.y); });
    g.strokeStyle = 'rgba(160,40,110,0.55)'; g.lineWidth = 14; g.stroke();
    g.font = '600 10px PralineBold, serif'; g.textAlign = 'center';
    p.spots.forEach(function (s) {
      var at = tr.at(s.along);
      g.fillStyle = 'rgba(160,40,110,0.25)'; g.beginPath(); g.ellipse(at.x, at.y, 34, 18, 0, 0, Math.PI * 2); g.fill();
      g.strokeStyle = 'rgba(160,40,110,0.7)'; g.lineWidth = 1; g.stroke();
      g.fillStyle = 'rgba(60,20,40,0.85)'; g.fillText(s.name + ' (keep clear)', at.x, at.y - 24);
    });
    Draw.drawTitle(g, p.world.title);
    g.setLineDash([6, 6]); g.strokeStyle = 'rgba(30,90,200,0.7)'; g.lineWidth = 1;
    for (var y = Model.SLICE; y < H; y += Model.SLICE) { g.beginPath(); g.moveTo(0, y); g.lineTo(Model.W, y); g.stroke(); }
    UI.download(await c.convertToBlob({ type: 'image/png' }), p.world.id + '-painting-guide.png');
  }

  function refreshHeader() { var n = document.getElementById('world-name'); if (n) n.textContent = S.p.world.name; }

  // ---- Inspector ---------------------------------------------------------------------------
  function inspect(sel) {
    var b = UI.clear(bodies.inspect);
    if (!sel) {
      b.appendChild(el('div.empty', [
        el('p', 'Nothing selected.'),
        el('ul.tips', [
          el('li', 'Double click on the trail to add a spot. Double click a spot to remove it.'),
          el('li', 'Drag a spot to slide it along the trail.'),
          el('li', 'Pick the trail pen (P) to draw or reshape the trail.'),
          el('li', 'Drag items from the Library onto the map.'),
          el('li', 'Scroll to move up and down; Ctrl + scroll to zoom.')
        ])
      ]));
      return;
    }
    if (sel.type === 'spot') spotPanel(b, Model.spot(sel.id));
    else if (sel.type === 'item') itemPanel(b, Model.item(sel.id));
    else if (sel.type === 'title') { b.appendChild(el('h3', 'Map title')); b.appendChild(el('p.hint', 'Drag it on the map to move it. Its text, lettering, colour and size are under World, in "Title over the map".')); }
    else if (sel.type === 'anchor') anchorPanel(b, sel.index);
  }

  function spotPanel(b, s) {
    if (!s) return;
    var i = S.p.spots.indexOf(s), th = Model.thresholds();
    var kind = el('div.seg', [['recipe', 'Recipe spot'], ['story', 'Story stop']].map(function (k) {
      return el('button' + (s.kind === k[0] ? '.on' : ''), { onclick: function () { if (s.kind !== k[0]) { edit(function () { s.kind = k[0]; }, 'spots'); inspect({ type: 'spot', id: s.id }); } } }, k[1]);
    }));
    var counter = el('span.counter', s.story.length + ' / ' + Model.STORY_MAX);
    var story = text(s, 'story', { area: true, rows: 7, max: Model.STORY_MAX, what: 'spots',
      placeholder: s.kind === 'recipe' ? 'You can spot strange silhouettes in the distance… there is a recipe on the counter.' : 'Half buried in the sand, a skeleton…',
      onInput: function (v) { counter.textContent = v.length + ' / ' + Model.STORY_MAX; } });
    b.appendChild(el('div.spot-head', [el('h3', (s.kind === 'recipe' ? 'Spot ' : 'Story stop ') + (i + 1) + ' of ' + S.p.spots.length), el('span.id', 'id ' + s.id)]));
    b.appendChild(kind);
    b.appendChild(el('p.hint', s.kind === 'recipe' ? 'The story plays when the party arrives, then the recipe joins the grimoire. The trail stays locked past it until a chef cooks it.'
      : 'The story plays when the party arrives, with its reward if it has one. Nothing to cook: the party walks straight on.'));
    b.appendChild(UI.row('Name on the map', text(s, 'name', { max: 40, what: 'spots' })));
    b.appendChild(el('div.grid2', [
      UI.row('Label side', select(s, 'side', [['left', 'Left'], ['right', 'Right']], { what: 'spots' })),
      UI.row('Fame to reach it', number(s, 'xp', { min: 1, max: 5000, int: true, what: 'spots', onChange: function () { inspect({ type: 'spot', id: s.id }); } }),
        'from the previous spot · ' + th[i] + ' in total')
    ]));
    b.appendChild(el('div.story-row', [UI.row('Story', story), counter]));
    if (s.kind === 'recipe') b.appendChild(recipeBlock(s));
    else b.appendChild(rewardBlock(s));
    b.appendChild(UI.row('Medallion picture', select(s, 'icon', [['', s.kind === 'recipe' ? 'The recipe cover' : 'None']].concat(sprites()), { what: 'spots' }),
      'A library picture, about 128 × 128 px.'));
    var pos = el('input', { type: 'range', min: 0, max: 1000, value: Math.round(s.along * 1000) });
    var before = null;
    pos.addEventListener('pointerdown', function () { before = JSON.stringify(S.p); });
    pos.addEventListener('input', function () { s.along = Number(pos.value) / 1000; Model.changed('spots'); });
    pos.addEventListener('change', function () { if (before) { S.undo.push(before); S.redo.length = 0; } Model.sortSpots(); Model.changed('spots'); inspect({ type: 'spot', id: s.id }); });
    b.appendChild(UI.row('Place on the trail', pos));
    b.appendChild(el('div.btns', [
      el('button', { onclick: function () { MapView.scrollTo(Model.trail().at(s.along).y); } }, 'Show on map'),
      el('button.danger', { onclick: function () { MapView.removeSpot(s.id); } }, 'Remove spot')
    ]));
  }

  function sprites() {
    var out = [];
    for (var id in S.p.library) if (S.p.library[id].kind === 'sprite') out.push([id, S.p.library[id].name || id]);
    return out;
  }

  function recipeBlock(s) {
    var info = S.recipes.get(s.id);
    var box = el('div.recipe-box');
    if (s.recipe && info && !info.error) {
      box.appendChild(el('div.recipe-card', [
        info.cover ? el('img', { src: info.cover, alt: '' }) : el('div.nocover', 'no cover'),
        el('div', [el('strong', info.title), el('div.hint', [info.tier ? info.tier + ' tier' : '', info.time ? info.time + ' min' : ''].filter(Boolean).join(' · ')),
          info.cover ? null : el('div.warn', 'Add a cover picture: it is the players\' hint.'),
          info.more ? el('div.hint', 'The file has more recipes; only the first one is used.') : null])
      ]));
    } else if (s.recipe && info && info.error) {
      box.appendChild(el('div.warn', 'This recipe file cannot be read: ' + info.error));
    } else {
      box.appendChild(el('p.warn', 'No recipe yet. Upload one made on the phone, or create it here.'));
    }
    box.appendChild(el('div.btns', [
      el('button', { onclick: function () { uploadRecipe(s); } }, 'Upload .praline…'),
      el('button.primary', { onclick: function () { editRecipe(s); } }, s.recipe ? 'Edit recipe…' : 'Create recipe…'),
      s.recipe ? el('button', { onclick: function () { UI.download(S.files.get(s.recipe), (info && info.title ? info.title.replace(/[^A-Za-z0-9]+/g, '-') : 'recipe') + '.praline'); } }, 'Download') : null,
      s.recipe ? el('button.danger', { onclick: async function () { if (await UI.confirm('Remove the recipe?', 'The spot keeps its story.', 'Remove', true)) { edit(function () { s.recipe = ''; }, 'spots'); await IO.refreshRecipe(s); inspect({ type: 'spot', id: s.id }); } } }, 'Remove') : null
    ]));
    return el('div', [el('h4', 'Recipe'), box]);
  }

  async function setRecipe(s, blob) {
    var path = 'recipes/' + s.id + '-' + Date.now().toString(36) + '.praline';
    IO.setFile(path, blob);
    edit(function () { s.recipe = path; }, 'spots');
    await IO.refreshRecipe(s);
    Model.changed('spots');
    inspect({ type: 'spot', id: s.id });
  }

  async function uploadRecipe(s) {
    var f = await UI.pickFile('.praline,application/octet-stream,application/zip');
    if (!f) return;
    try {
      var x = await Recipes.read(f);
      await setRecipe(s, f);
      if (/^Spot \d+$/.test(s.name)) { edit(function () { s.name = x.recipe.title.substring(0, 40); }, 'spots'); inspect({ type: 'spot', id: s.id }); }
      UI.toast('"' + x.recipe.title + '" is now this spot\'s recipe.');
    } catch (e) { UI.toast('That file cannot be used: ' + (e.message || e), true); }
  }

  async function editRecipe(s) {
    try {
      var blob = await Recipes.form(s.recipe ? S.files.get(s.recipe) : null, S.p.world.id, /^Spot \d+$/.test(s.name) ? '' : s.name);
      if (!blob) return;
      await setRecipe(s, blob);
      var info = S.recipes.get(s.id);
      if (/^Spot \d+$/.test(s.name) && info && info.title) { edit(function () { s.name = info.title.substring(0, 40); }, 'spots'); inspect({ type: 'spot', id: s.id }); }
    } catch (e) { UI.toast('The recipe could not be opened: ' + (e.message || e), true); }
  }

  function rewardBlock(s) {
    var has = !!s.reward;
    var box = el('div.recipe-box');
    var c = el('input', { type: 'checkbox', checked: has });
    c.addEventListener('change', function () {
      edit(function () { s.reward = c.checked ? { kind: 'decoration', name: '', item: '' } : null; }, 'spots');
      inspect({ type: 'spot', id: s.id });
    });
    box.appendChild(el('label.check', [c, ' This stop gives a reward']));
    if (has) {
      box.appendChild(UI.row('Reward name', text(s.reward, 'name', { max: 40, what: 'spots', placeholder: 'Brass spoon' })));
      box.appendChild(UI.row('Kind', select(s.reward, 'kind', [['decoration', 'Profile decoration']], { what: 'spots' })));
      box.appendChild(UI.row('Picture', select(s.reward, 'item', [['', 'Choose a library picture']].concat(sprites()), { what: 'spots' })));
      box.appendChild(el('p.hint', 'Profile decorations need the rewards shelf, which comes with the app version that reads world packs.'));
    }
    return el('div', [el('h4', 'Reward'), box]);
  }

  function itemPanel(b, it) {
    if (!it) return;
    var l = Model.lib(it.lib) || { kind: '?', name: it.lib };
    b.appendChild(el('h3', (l.name || it.lib)));
    b.appendChild(el('p.hint', { sprite: 'Still picture', sheet: 'Animation (sprite sheet)', fx: 'Effect' }[l.kind] + ' · ' + it.id));
    b.appendChild(el('div.grid2', [
      UI.row('x', number(it, 'x', { step: 0.5, what: 'items' })), UI.row('y', number(it, 'y', { step: 0.5, what: 'items' })),
      UI.row('Scale', number(it, 'scale', { step: 0.05, min: 0.05, max: 10, def: 1, what: 'items' })), UI.row('Tilt (°)', number(it, 'rot', { min: -180, max: 180, what: 'items' })),
      UI.row('Layer', select(it, 'layer', [['under', 'Under the trail'], ['over', 'Over the trail']], { what: 'items' })),
      UI.row('Order', number(it, 'z', { min: -50, max: 50, int: true, what: 'items' }), 'higher = in front'),
      UI.row('Opacity', number(it, 'alpha', { step: 0.05, min: 0, max: 1, def: 1, what: 'items' }))
    ]));
    if (l.kind !== 'fx') b.appendChild(check(it, 'flip', 'Mirror left-right', { what: 'items' }));
    if (l.kind === 'sprite') b.appendChild(el('div', [check(it, 'bake', 'Bake into the map (costs the phone nothing, cannot move)', { what: 'items' })]));
    if (!it.bake && l.kind !== 'fx') {
      if (!it.bob) it.bob = { amp: 0, period: 4 };
      b.appendChild(el('div.grid2', [UI.row('Float (units)', number(it.bob, 'amp', { step: 0.5, min: 0, max: 40, what: 'items' })), UI.row('Every (s)', number(it.bob, 'period', { step: 0.5, min: 0.5, max: 30, def: 4, what: 'items' }))]));
    }
    if (l.kind === 'fx') {
      if (!it.params) it.params = {};
      var d = Model.FX_PARAMS[l.fx] || {};
      var rows = Object.keys(d).map(function (k) {
        if (it.params[k] === undefined) it.params[k] = d[k];
        var label = { from: 'From angle (°)', to: 'To angle (°)', length: 'Length', count: 'How many', color: 'Colour', alpha: 'Strength', r: 'Radius', halo: 'Halo radius',
          pulse: 'Pulse (s)', rx: 'Width', ry: 'Height', rate: 'Amount', w: 'Width', h: 'Height', tx: 'Ends at x', ty: 'Ends at y', width: 'Thickness' }[k] || k;
        return UI.row(label, k === 'color' ? color(it.params, k, { what: 'items' }) : number(it.params, k, { step: k === 'alpha' || k === 'rate' ? 0.05 : 1, what: 'items' }));
      });
      b.appendChild(el('div.grid2', rows));
      if (l.fx === 'motes') b.appendChild(el('p.hint', 'Dust motes float over the whole map; where you put this item does not matter.'));
    }
    b.appendChild(el('div.btns', [
      el('button', { onclick: function () { MapView.duplicate(); } }, 'Duplicate (Ctrl+D)'),
      el('button.danger', { onclick: function () { MapView.deleteSelection(); } }, 'Delete')
    ]));
    b.appendChild(el('p.hint', 'Drag it on the map, use the arrow keys to nudge, Alt + scroll to resize.'));
  }

  function anchorPanel(b, index) {
    var a = S.p.trail[index]; if (!a) return;
    b.appendChild(el('h3', 'Trail point ' + (index + 1) + ' of ' + S.p.trail.length));
    b.appendChild(el('p.hint', index === 0 ? 'The start: where the party sets off.' : index === S.p.trail.length - 1 ? 'The end of the world.' : 'A bend in the trail.'));
    b.appendChild(el('div.seg', [['smooth', 'Smooth'], ['corner', 'Corner']].map(function (k) {
      var on = (a.smooth !== false) === (k[0] === 'smooth');
      return el('button' + (on ? '.on' : ''), { onclick: function () { if (!on) { edit(function () { toggleSmooth(index); }, 'trail'); inspect({ type: 'anchor', index: index }); } } }, k[1]);
    })));
    b.appendChild(el('ul.tips', [
      el('li', 'Drag the square to move it, the round handles to bend the curve.'),
      el('li', 'Alt + drag a handle to make a sharp corner. Double click the square to switch smooth and corner.'),
      el('li', 'Click on the trail to add a point; click on empty map to extend the end (Shift: the start).'),
      el('li', 'Delete removes this point.')
    ]));
    b.appendChild(el('div.btns', [el('button.danger', { onclick: function () { MapView.deleteSelection(); } }, 'Remove point')]));
  }

  function toggleSmooth(i) {
    var a = S.p.trail, p = a[i];
    if (p.smooth === false) {
      var prev = a[Math.max(0, i - 1)], next = a[Math.min(a.length - 1, i + 1)], tx = (next.x - prev.x) / 6, ty = (next.y - prev.y) / 6;
      p.ix = p.x - tx; p.iy = p.y - ty; p.ox = p.x + tx; p.oy = p.y + ty; p.smooth = true;
    } else { p.ix = p.x; p.iy = p.y; p.ox = p.x; p.oy = p.y; p.smooth = false; }
  }

  // ---- Library -------------------------------------------------------------------------------
  function library() {
    var b = UI.clear(bodies.library);
    b.appendChild(el('p.hint', 'Drag anything onto the map, or click it to change its settings.'));
    b.appendChild(el('h4', 'Effects'));
    b.appendChild(el('div.lib-grid', Model.FX.map(function (fx) { return tile('fx-' + fx, { kind: 'fx', fx: fx, name: Model.FX_NAMES[fx] }); })));
    b.appendChild(el('h4', 'Your pictures and animations'));
    var mine = Object.keys(S.p.library);
    var grid = el('div.lib-grid.drop', mine.length ? mine.map(function (id) { return tile(id, S.p.library[id]); }) : [el('p.hint', 'Nothing yet. Import PNG or WebP files: still pictures, or sprite sheets for animations.')]);
    grid.addEventListener('dragover', function (e) { if (e.dataTransfer.types.indexOf('Files') >= 0) { e.preventDefault(); grid.classList.add('over'); } });
    grid.addEventListener('dragleave', function () { grid.classList.remove('over'); });
    grid.addEventListener('drop', function (e) { e.preventDefault(); grid.classList.remove('over'); importLib(Array.from(e.dataTransfer.files)); });
    b.appendChild(grid);
    b.appendChild(el('div.btns', [el('button.primary', { onclick: async function () { var f = await UI.pickFile('image/png,image/webp', true); if (f && f.length) importLib(f); } }, 'Import pictures…')]));
    b.appendChild(el('details.sec', [el('summary', 'What to paint'), el('ul.tips', [
      el('li', 'Paint at 3× (1 map unit = 3 px). A palm 70 units tall is 210 px.'),
      el('li', 'Still pictures: PNG or WebP with transparency, up to 1024 × 1024 px.'),
      el('li', 'Animations: a sprite sheet, equal frames in a grid read left to right, up to 2048 × 2048 px and 48 frames (Aseprite, Photoshop timeline or TexturePacker export this).'),
      el('li', 'Medallion icons and reward pictures: about 128 × 128 px.'),
      el('li', 'Glow, fire and magic look best with the blend set to Add.')
    ])]));
  }

  var thumbs = new Map();
  function tile(id, l) {
    var c = el('canvas', { width: 72, height: 72 });
    var g = c.getContext('2d');
    if (l.kind === 'fx') {
      g.fillStyle = '#3A2E26'; g.fillRect(0, 0, 72, 72);
      g.save(); g.translate(36, l.fx === 'rays' ? 8 : 44); g.scale(l.fx === 'rays' ? 0.06 : 0.6, l.fx === 'rays' ? 0.06 : 0.6);
      Draw.drawFx(g, l.fx, {}, { id: id, x: 0, y: 0 }, 1.3); g.restore();
    } else {
      var img = S.images.get(l.file);
      g.fillStyle = '#EFE3CC'; g.fillRect(0, 0, 72, 72);
      if (img) {
        var sw = l.kind === 'sheet' ? l.frameW : img.width, sh = l.kind === 'sheet' ? l.frameH : img.height, k = Math.min(64 / sw, 64 / sh);
        g.drawImage(img, 0, 0, sw, sh, 36 - sw * k / 2, 36 - sh * k / 2, sw * k, sh * k);
      }
    }
    var t = el('div.lib-tile', { draggable: 'true', title: (l.name || id) + ' · drag onto the map', onclick: function () { if (l.kind === 'fx') MapView.placeItem(id); else libDialog(id); } },
      [c, el('span', l.name || id), l.kind === 'sheet' ? el('em', l.frames + ' frames') : null]);
    t.addEventListener('dragstart', function (e) { e.dataTransfer.setData('text/x-praline-lib', id); e.dataTransfer.effectAllowed = 'copy'; });
    return t;
  }

  async function importLib(files) {
    for (var i = 0; i < files.length; i++) {
      var f = files[i];
      if (!/^image\/(png|webp)$/.test(f.type)) { UI.toast(f.name + ': use PNG or WebP (with transparency).', true); continue; }
      var bmp;
      try { bmp = await createImageBitmap(f); } catch (e) { UI.toast(f.name + ' could not be read.', true); continue; }
      var id = Model.libId(f.name), ext = f.type === 'image/png' ? 'png' : 'webp', path = 'library/' + id + '.' + ext;
      var w = bmp.width, h = bmp.height;
      var guessSheet = /sheet|anim|frames?/i.test(f.name) || (w >= 2 * h && w % h === 0);
      var entry = { kind: 'sprite', name: f.name.replace(/\.[^.]+$/, ''), file: path, w: w, h: h, ax: Math.round(w / 2), ay: h, ppu: 3 };
      if (guessSheet) {
        var n = w % h === 0 ? w / h : 8;
        entry = { kind: 'sheet', name: entry.name, file: path, w: w, h: h, frameW: Math.floor(w / n), frameH: h, cols: n, frames: n, fps: 12, loop: 'loop', blend: 'normal', ax: Math.round(w / n / 2), ay: h, ppu: 3 };
      }
      bmp.close();
      IO.setFile(path, f);
      await IO.image(path);
      edit(function () { S.p.library[id] = entry; }, 'library');
      if (w > 2048 || h > 2048) UI.toast(f.name + ' is ' + w + ' × ' + h + ' px; keep pictures under 1024 (sheets under 2048) so phones stay light.', true);
      if (files.length === 1) await libDialog(id);
    }
    library();
  }

  /** Settings of one library entry: kind, anchor (click on the picture), frames for animations. */
  async function libDialog(id) {
    var l = S.p.library[id]; if (!l) return;
    var c = JSON.parse(JSON.stringify(l));
    var img = S.images.get(l.file) || await IO.image(l.file);
    var pv = el('canvas.anchor-pick', { width: 260, height: 260 });
    var timer = null;
    function paint() {
      var g = pv.getContext('2d'); g.clearRect(0, 0, 260, 260);
      g.fillStyle = '#EFE3CC'; g.fillRect(0, 0, 260, 260);
      if (!img) return;
      var fw = c.kind === 'sheet' ? c.frameW : img.width, fh = c.kind === 'sheet' ? c.frameH : img.height, k = Math.min(240 / fw, 240 / fh);
      var ox = 130 - fw * k / 2, oy = 130 - fh * k / 2;
      var f = c.kind === 'sheet' ? Draw.frameAt(c, performance.now() / 1000) : 0, cols = Math.max(1, c.cols || 1);
      var sx = c.kind === 'sheet' ? (f % cols) * c.frameW : 0, sy = c.kind === 'sheet' ? Math.floor(f / cols) * c.frameH : 0;
      if (c.blend === 'add') { g.fillStyle = '#2B2420'; g.fillRect(0, 0, 260, 260); g.globalCompositeOperation = 'lighter'; }
      g.drawImage(img, sx, sy, fw, fh, ox, oy, fw * k, fh * k);
      g.globalCompositeOperation = 'source-over';
      g.strokeStyle = 'rgba(30,100,210,0.5)'; g.strokeRect(ox, oy, fw * k, fh * k);
      var ax = ox + c.ax * k, ay = oy + c.ay * k;
      g.strokeStyle = '#E0479E'; g.lineWidth = 2; g.beginPath(); g.moveTo(ax - 9, ay); g.lineTo(ax + 9, ay); g.moveTo(ax, ay - 9); g.lineTo(ax, ay + 9); g.stroke();
      pv._map = { ox: ox, oy: oy, k: k };
    }
    pv.addEventListener('click', function (e) {
      var r = pv.getBoundingClientRect(), m = pv._map; if (!m) return;
      c.ax = Math.round((e.clientX - r.left - m.ox) / m.k); c.ay = Math.round((e.clientY - r.top - m.oy) / m.k); paint(); sync();
    });
    var f = {};
    function inp(key, opts) { var i = el('input', { type: opts && opts.text ? 'text' : 'number', value: c[key], min: opts && opts.min }); i.addEventListener('input', function () { c[key] = opts && opts.text ? i.value : UI.num(i.value, c[key]); if (opts && opts.after) opts.after(); paint(); }); f[key] = i; return i; }
    function sync() { if (f.ax) f.ax.value = c.ax; if (f.ay) f.ay.value = c.ay; }
    var kind = el('select', [el('option', { value: 'sprite', selected: c.kind === 'sprite' }, 'Still picture'), el('option', { value: 'sheet', selected: c.kind === 'sheet' }, 'Animation (sprite sheet)')]);
    var sheetBox = el('div');
    function fillSheet() {
      UI.clear(sheetBox);
      if (c.kind !== 'sheet') return;
      if (!c.frameW) { c.frameW = c.w; c.frameH = c.h; c.cols = 1; c.frames = 1; c.fps = 12; c.loop = 'loop'; c.blend = 'normal'; }
      var loop = el('select', [['loop', 'Loop'], ['pingpong', 'Back and forth'], ['once', 'Once']].map(function (o) { return el('option', { value: o[0], selected: c.loop === o[0] }, o[1]); }));
      loop.addEventListener('change', function () { c.loop = loop.value; });
      var blend = el('select', [['normal', 'Normal'], ['add', 'Add (glow, fire, magic)']].map(function (o) { return el('option', { value: o[0], selected: c.blend === o[0] }, o[1]); }));
      blend.addEventListener('change', function () { c.blend = blend.value; paint(); });
      UI.add(sheetBox, [el('div.grid2', [
        UI.row('Frames across', inp('cols', { min: 1, after: function () { c.frameW = Math.floor(c.w / Math.max(1, c.cols)); } })),
        UI.row('Frames in all', inp('frames', { min: 1 })),
        UI.row('Frame width (px)', inp('frameW', { min: 1 })), UI.row('Frame height (px)', inp('frameH', { min: 1 })),
        UI.row('Frames per second', inp('fps', { min: 1 })), UI.row('Plays', loop), UI.row('Blend', blend)
      ])]);
    }
    kind.addEventListener('change', function () { c.kind = kind.value; fillSheet(); paint(); });
    fillSheet();
    var used = S.p.items.some(function (it) { return it.lib === id; }) || S.p.spots.some(function (s) { return s.icon === id || (s.reward && s.reward.item === id); });
    timer = setInterval(function () { if (c.kind === 'sheet') paint(); }, 60);
    paint();
    var body = el('div.lib-dialog', [
      el('div', [pv, el('p.hint', 'Click where it touches the ground: that point sits on the map position.')]),
      el('div', [
        UI.row('Name', inp('name', { text: true })),
        el('p.hint', l.w + ' × ' + l.h + ' px · ' + Math.round(l.w / (c.ppu || 3)) + ' × ' + Math.round(l.h / (c.ppu || 3)) + ' map units'),
        UI.row('Kind', kind), sheetBox,
        el('div.grid2', [UI.row('Anchor x (px)', inp('ax')), UI.row('Anchor y (px)', inp('ay')), UI.row('Painted at (px per unit)', inp('ppu', { min: 1 }))])
      ])
    ]);
    var r = await UI.dialog('Library: ' + (l.name || id), body, [
      used ? null : { label: 'Remove from library', value: 'remove', danger: true },
      { label: 'Place on map', value: 'place' },
      { label: 'Cancel', value: false },
      { label: 'Save', value: 'save', primary: true }
    ].filter(Boolean), { wide: true });
    clearInterval(timer);
    if (r === 'remove') { edit(function () { delete S.p.library[id]; }, 'library'); IO.dropFile(l.file); library(); return; }
    if (r === 'save' || r === 'place') {
      if (c.kind === 'sheet' && (c.frameW * Math.min(c.frames, c.cols) > c.w + 0.5 || c.frameH * Math.ceil(c.frames / Math.max(1, c.cols)) > c.h + 0.5)) {
        UI.toast('Those frames do not fit in the picture: check the frame size and counts.', true); return;
      }
      if (c.kind === 'sheet' && c.frames > 48) UI.toast('Animations over 48 frames get heavy on phones.', true);
      edit(function () { S.p.library[id] = c; }, 'library');
      library();
      if (r === 'place') MapView.placeItem(id);
    }
  }

  return { init: init, showTab: showTab, refresh: refresh, status: status, defaultParchment: defaultParchment, current: function () { return current; } };
})();
