// Recipes for spots: reading and writing the app's .praline files, and the quick recipe form.
// A .praline file is a zip with praline.json ({format: "praline-recipes", v: 1, recipes: [...]}) and
// photos/*.jpg, written by RecipeFiles.kt; the recipe fields follow Recipe.toJson in Model.kt.
var Recipes = (function () {
  var el = UI.el;
  var FORMAT = 'praline-recipes';
  var CLASSES = [['barbarian', 'Barbarian', 'hearty, big portions'], ['bard', 'Bard', 'a crowd-pleaser'], ['cleric', 'Cleric', 'comfort food that heals'],
    ['druid', 'Druid', 'green, garden-grown'], ['monk', 'Monk', 'simple, few ingredients'], ['paladin', 'Paladin', 'classic, by the book'],
    ['ranger', 'Ranger', 'grilled, smoky, outdoorsy'], ['rogue', 'Rogue', 'quick and sneaky'], ['sorcerer', 'Sorcerer', 'bold, spicy, a little chaotic'],
    ['wizard', 'Wizard', 'technical, precise']];
  var TIERS = ['S', 'A', 'B', 'C', 'D'];
  var EFFORT = ['', 'Trivial', 'Easy', 'Medium', 'Hard', 'Legendary'];

  // ---- ingredient lines, as Qty.parse in Model.kt -------------------------------------------
  var UNITS = ['g', 'kg', 'mg', 'ml', 'cl', 'dl', 'l', 'tsp', 'tbsp', 'cup', 'cups', 'oz', 'lb', 'lbs', 'pinch', 'pinches', 'can', 'cans',
    'clove', 'cloves', 'slice', 'slices', 'sheet', 'sheets', 'bunch', 'handful', 'cas', 'cac', 'càs', 'càc', 'c.à.s', 'c.à.c', 'pincée',
    'pincées', 'boîte', 'boîtes', 'gousse', 'gousses', 'tranche', 'tranches', 'sachet', 'sachets', 'pot', 'pots', 'stick', 'sticks',
    'knob', 'drop', 'drops'];
  var FRACS = { '½': 0.5, '⅓': 1 / 3, '⅔': 2 / 3, '¼': 0.25, '¾': 0.75, '⅛': 0.125 };
  function isDigit(c) { return c >= '0' && c <= '9'; }

  function parseLine(lineIn) {
    var line = lineIn.trim(), i = 0, q = 0, has = false, num = '';
    while (i < line.length && (isDigit(line[i]) || ((line[i] === '.' || line[i] === ',') && num.length))) { num += line[i] === ',' ? '.' : line[i]; i++; }
    if (num.length) { q = parseFloat(num) || 0; has = true; }
    if (i < line.length && line[i] === '/' && has) {
      var den = '', j = i + 1;
      while (j < line.length && isDigit(line[j])) den += line[j++];
      var d = parseFloat(den);
      if (d > 0) { q /= d; i = j; }
    } else if (has && i + 1 < line.length && line[i] === ' ' && isDigit(line[i + 1])) {
      var j2 = i + 1, a = '';
      while (j2 < line.length && isDigit(line[j2])) a += line[j2++];
      if (j2 < line.length && line[j2] === '/') {
        var b = '', k = j2 + 1;
        while (k < line.length && isDigit(line[k])) b += line[k++];
        var d2 = parseFloat(b);
        if (d2 > 0) { q += parseFloat(a) / d2; i = k; }
      }
    }
    if (has && i + 1 < line.length && line[i] === ' ' && FRACS[line[i + 1]] !== undefined) i++;
    if (i < line.length && FRACS[line[i]] !== undefined) { q += FRACS[line[i]]; has = true; i++; }
    if (!has) return { u: '', n: line };
    var rest = line.substring(i).trim(), unit = '';
    var word = rest.split(/\s+/);
    if (word.length > 1) {
      var w = word[0].toLowerCase().replace(/\.+$/, '');
      if (UNITS.indexOf(w) >= 0) { unit = word[0].replace(/\.+$/, ''); rest = rest.substring(word[0].length).trim(); }
    }
    return { q: q, u: unit, n: rest };
  }

  function lineOf(ing) {
    if (ing.q === undefined || ing.q === null) return ing.n || '';
    var q = Math.round(ing.q * 100) / 100;
    return q + (ing.u ? ' ' + ing.u : '') + ' ' + (ing.n || '');
  }

  // ---- .praline files ----------------------------------------------------------------------
  var SAFE = /^[A-Za-z0-9._-]{1,80}$/;

  /** Reads a .praline file: {recipe, photos: {name: Uint8Array}, from}. Throws with a readable message. */
  async function read(blob) {
    if (blob.size > 200 * 1024 * 1024) throw new Error('the file is over 200 MB');
    var files = await Zip.read(blob, { maxEntries: 600, maxEntry: 80 * 1024 * 1024 });
    if (!files['praline.json']) throw new Error('it is not a Praliné recipe file');
    var head = JSON.parse(new TextDecoder().decode(files['praline.json']));
    if (head.format !== FORMAT || !Array.isArray(head.recipes) || !head.recipes.length) throw new Error('it is not a Praliné recipe file');
    var r = head.recipes[0];
    if (!r || typeof r.title !== 'string') throw new Error('its recipe is damaged');
    var photos = {};
    for (var name in files) {
      if (name.indexOf('photos/') !== 0) continue;
      var n = name.substring(7);
      if (SAFE.test(n) && n[0] !== '.' && /\.(jpg|mp4)$/.test(n)) photos[n] = files[name];
    }
    return { recipe: r, photos: photos, from: head.from || '', count: head.recipes.length };
  }

  /** Writes one recipe and its pictures as a .praline file (Blob). */
  function write(r, photos) {
    var head = { format: FORMAT, v: 1, from: 'Map editor', recipes: [r] };
    var entries = [{ name: 'praline.json', data: JSON.stringify(head) }];
    var used = {};
    [r.photo, r.orig].forEach(function (n) { if (n) used[n] = true; });
    (r.variants || []).forEach(function (v) { (v.steps || []).forEach(function (s) { if (s.h) used[s.h] = true; if (s.vd) { used[s.vd] = true; used[s.vd + '.jpg'] = true; } }); });
    for (var n in photos) if (used[n]) entries.push({ name: 'photos/' + n, data: photos[n] });
    return Zip.write(entries, 'application/octet-stream');
  }

  /** {title, cover (object URL or ''), error} for the spot panel and the preview. */
  async function describe(blob) {
    try {
      var x = await read(blob);
      var cover = x.recipe.photo && x.photos[x.recipe.photo] ? URL.createObjectURL(new Blob([x.photos[x.recipe.photo]], { type: 'image/jpeg' })) : '';
      return { title: x.recipe.title || 'Untitled recipe', cover: cover, error: '', tier: x.recipe.tier, cls: x.recipe.cls, time: x.recipe.time, more: x.count > 1 };
    } catch (e) {
      return { title: '', cover: '', error: e.message || String(e) };
    }
  }

  function photoName() {
    var a = new Uint8Array(8); crypto.getRandomValues(a);
    return Array.from(a, function (b) { return ('0' + b.toString(16)).slice(-2); }).join('') + '.jpg';
  }

  /** A picture file -> JPEG no longer than [max] px on its long side. */
  async function toJpeg(file, max) {
    var bmp = await createImageBitmap(file);
    var k = Math.min(1, max / Math.max(bmp.width, bmp.height));
    var c = new OffscreenCanvas(Math.round(bmp.width * k), Math.round(bmp.height * k));
    var g = c.getContext('2d');
    g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height);
    g.drawImage(bmp, 0, 0, c.width, c.height);
    bmp.close();
    return await c.convertToBlob({ type: 'image/jpeg', quality: 0.88 });
  }

  function clock(sec) {
    if (!sec) return '';
    var h = Math.floor(sec / 3600), m = Math.floor(sec % 3600 / 60), s = sec % 60;
    return (h ? h + ':' + ('0' + m).slice(-2) : m) + ':' + ('0' + s).slice(-2);
  }
  /** "5" (minutes), "4:30", "1:05:00" -> seconds; '' -> 0; NaN when unreadable. */
  function seconds(text) {
    text = text.trim();
    if (!text) return 0;
    if (/^\d+$/.test(text)) return parseInt(text, 10) * 60;
    var p = text.split(':');
    if (p.length < 2 || p.length > 3 || p.some(function (x) { return !/^\d+$/.test(x); })) return NaN;
    var n = p.map(Number);
    return p.length === 2 ? n[0] * 60 + n[1] : n[0] * 3600 + n[1] * 60 + n[2];
  }

  // ---- the quick form ------------------------------------------------------------------------
  /**
   * Opens the quick recipe form. [existing] is a .praline Blob to edit (or null for a new recipe).
   * Resolves with a new .praline Blob, or null if cancelled. Fields the form doesn't show (other
   * variants, equipment, step pictures and clips, the filter) are kept as they were.
   */
  async function form(existing, worldId, suggestedTitle) {
    var base = null, photos = {};
    if (existing) { var x = await read(existing); base = x.recipe; photos = x.photos; }
    var r = base ? JSON.parse(JSON.stringify(base)) : {
      id: 'editor-' + Date.now(), title: suggestedTitle || '', dessert: false, cls: 'paladin', tier: 'B', effort: 2, time: 30, serv: 4,
      photo: '', orig: '', filter: '', str: 0.8, cooked: 0, created: 0, updated: 0, equip: [], variants: [{ name: 'Classic', ing: [], steps: [] }]
    };
    if (!r.variants || !r.variants.length) r.variants = [{ name: 'Classic', ing: [], steps: [] }];
    var v = r.variants[0];
    var cover = r.photo && photos[r.photo] ? new Blob([photos[r.photo]], { type: 'image/jpeg' }) : null;
    var newCover = null;

    var title = el('input', { type: 'text', value: r.title, maxlength: 60, placeholder: 'Camel Cheesecake' });
    var dessert = el('select', [el('option', { value: 'meal', selected: !r.dessert }, 'Meal'), el('option', { value: 'dessert', selected: !!r.dessert }, 'Dessert')]);
    var cls = el('select', CLASSES.map(function (c) { return el('option', { value: c[0], selected: c[0] === r.cls }, c[1] + ' · ' + c[2]); }));
    var tier = el('select', TIERS.map(function (t) { return el('option', { value: t, selected: t === r.tier }, t + ' tier'); }));
    var effort = el('select', [1, 2, 3, 4, 5].map(function (n) { return el('option', { value: n, selected: n === r.effort }, n + ' · ' + EFFORT[n]); }));
    var time = el('input', { type: 'number', min: 1, max: 1440, value: r.time });
    var serv = el('input', { type: 'number', min: 1, max: 99, value: r.serv });
    var fame = el('input', { type: 'number', min: 0, max: 999, value: r.fame || 0 });
    var ing = el('textarea', { rows: 8, placeholder: '200 g cream cheese\n3 eggs\n1 pinch salt' });
    ing.value = (v.ing || []).map(lineOf).join('\n');

    var coverImg = el('img.cover-pic', { alt: 'cover picture' });
    var coverEmpty = el('div.cover-empty', 'No cover picture yet. Players see it as a hint of what to cook.');
    function showCover() {
      var b = newCover || cover;
      coverImg.style.display = b ? 'block' : 'none'; coverEmpty.style.display = b ? 'none' : 'block';
      if (b) coverImg.src = URL.createObjectURL(b);
    }
    showCover();
    var coverBox = el('div.cover-box', [coverImg, coverEmpty, el('div.cover-btns', [
      el('button', { onclick: async function (e) { e.preventDefault(); var f = await UI.pickFile('image/*'); if (f) { try { newCover = await toJpeg(f, 1600); showCover(); } catch (x) { UI.toast('That picture could not be read.', true); } } } }, 'Choose a picture…'),
      el('button', { onclick: function (e) { e.preventDefault(); newCover = null; cover = null; showCover(); } }, 'Remove')
    ])]);

    var stepsBox = el('div.steps');
    function stepRow(s) {
      var t = el('textarea.step-text', { rows: 2, placeholder: 'What to do' }); t.value = s.t || '';
      var tm = el('input.step-time', { type: 'text', value: clock(s.s || 0), placeholder: 'm:ss', title: 'Timer: minutes, or m:ss, or h:mm:ss. Empty = no timer.' });
      var lb = el('input.step-label', { type: 'text', value: s.l || '', placeholder: 'Timer name', maxlength: 30 });
      var rowEl = el('div.step-row', [el('span.step-n'), t, el('div.step-side', [tm, lb]), el('div.step-btns', [
        el('button.icon', { title: 'Move up', onclick: function (e) { e.preventDefault(); if (rowEl.previousSibling) stepsBox.insertBefore(rowEl, rowEl.previousSibling); number(); } }, '↑'),
        el('button.icon', { title: 'Remove step', onclick: function (e) { e.preventDefault(); rowEl.remove(); number(); } }, '×')])]);
      rowEl._extra = s;
      return rowEl;
    }
    function number() { Array.from(stepsBox.children).forEach(function (c, i) { c.querySelector('.step-n').textContent = (i + 1) + '.'; }); }
    (v.steps || []).forEach(function (s) { stepsBox.appendChild(stepRow(s)); });
    if (!v.steps || !v.steps.length) stepsBox.appendChild(stepRow({}));
    number();

    var body = el('div.recipe-form', [
      el('div.form-cols', [
        el('div', [
          UI.row('Title', title),
          el('div.form-grid', [UI.row('Course', dessert), UI.row('Class', cls), UI.row('Tier', tier), UI.row('Effort', effort),
            UI.row('Time (minutes)', time), UI.row('Servings', serv), UI.row('Fame', fame, '0 = from the effort')]),
          UI.row('Ingredients, one per line', ing)
        ]),
        el('div', [UI.row('Cover picture', coverBox)])
      ]),
      el('h3', 'Steps'),
      stepsBox,
      el('button', { onclick: function (e) { e.preventDefault(); stepsBox.appendChild(stepRow({})); number(); } }, '+ Add a step'),
      base && (r.variants.length > 1 || (r.equip || []).length) ? el('p.hint', 'This recipe also has ' +
        [r.variants.length > 1 ? (r.variants.length - 1) + ' more variant(s)' : '', (r.equip || []).length ? 'equipment' : ''].filter(Boolean).join(' and ') +
        ', made on the phone. They are kept as they are.') : null
    ]);

    function collect() {
      if (!title.value.trim()) { UI.toast('The recipe needs a title.', true); title.focus(); return null; }
      var steps = [];
      var rows = Array.from(stepsBox.children);
      for (var i = 0; i < rows.length; i++) {
        var c = rows[i], text = c.querySelector('.step-text').value.trim(), sec = seconds(c.querySelector('.step-time').value);
        if (!text) continue;
        if (isNaN(sec)) { UI.toast('Step ' + (i + 1) + ': write the timer as minutes, m:ss or h:mm:ss.', true); return null; }
        var s = JSON.parse(JSON.stringify(c._extra || {}));
        s.t = text; s.s = sec; s.l = c.querySelector('.step-label').value.trim(); if (!('h' in s)) s.h = '';
        if (!sec) { s.l = ''; delete s.rn; }
        steps.push(s);
      }
      if (!steps.length) { UI.toast('Add at least one step.', true); return null; }
      r.title = title.value.trim(); r.dessert = dessert.value === 'dessert'; r.cls = cls.value; r.tier = tier.value;
      r.effort = parseInt(effort.value, 10); r.time = Math.max(1, Math.round(UI.num(time.value, 30))); r.serv = Math.max(1, Math.round(UI.num(serv.value, 4)));
      var f = Math.max(0, Math.round(UI.num(fame.value, 0))); if (f > 0) r.fame = f; else delete r.fame;
      v.ing = ing.value.split('\n').map(function (l) { return l.trim(); }).filter(Boolean).map(parseLine);
      v.steps = steps;
      if (worldId) r.world = worldId;
      return r;
    }

    var result = null;
    var ok = await UI.dialog(base ? 'Edit recipe' : 'New recipe', body, [
      { label: 'Cancel', value: false },
      { label: 'Save recipe', value: true, primary: true, check: function () { result = collect(); return !!result; } }
    ], { wide: true, sticky: true });
    if (!ok || !result) return null;
    if (newCover) {
      var a = photoName(), b = photoName();
      photos[a] = newCover; photos[b] = newCover;
      result.photo = a; result.orig = b; result.filter = '';
    } else if (!cover) { result.photo = ''; result.orig = ''; result.filter = ''; }
    return await write(result, photos);
  }

  return { read: read, write: write, describe: describe, form: form, parseLine: parseLine, seconds: seconds, CLASSES: CLASSES };
})();
