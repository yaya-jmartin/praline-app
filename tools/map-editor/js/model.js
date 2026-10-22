// The world being edited: the project (saved as project.json), its files, undo, the starter template
// and the checks run before export. Positions are in map units (412 wide), see WORLD-FORMAT.md.
var Model = (function () {
  var W = 412, PPU = 3, SLICE = 525;
  var STORY_MAX = 600;
  var FX = ['rays', 'halo', 'glow', 'glints', 'smoke', 'sparks', 'sand', 'heat', 'beam', 'motes', 'flame', 'ripples'];
  var FX_NAMES = { rays: 'God rays', halo: 'Sun halo', glow: 'Glow', glints: 'Water glints', smoke: 'Smoke',
    sparks: 'Sparks', sand: 'Sand plume', heat: 'Heat shimmer', beam: 'Light beam', motes: 'Dust motes', flame: 'Campfire flame', ripples: 'Water ripples' };
  var FX_PARAMS = {
    rays: { from: 92, to: 172, length: 1500, count: 7, color: '#FFE7A6', alpha: 0.35 },
    halo: { r: 34, halo: 110, color: '#FFF1C2' },
    glow: { r: 22, halo: 44, color: '#FFB347', pulse: 2.2 },
    glints: { rx: 60, ry: 24, count: 6, color: '#FFFFFF' },
    smoke: { color: '#B8A898', rate: 1 },
    sparks: { color: '#FFC266', rate: 1 },
    sand: { color: '#E8C890', rate: 1 },
    heat: { w: 40, h: 60 },
    beam: { tx: 120, ty: 200, width: 14, color: '#FFE7A6', flare: 30, sparks: true },
    motes: { count: 70, color: '#FFE9B8' },
    flame: { w: 12, h: 28, color: '#FF8A2A', core: '#FFD25A' },
    ripples: { rx: 30, ry: 10, period: 3.2 }
  };
  var GAPS = [80, 120, 140, 160, 180, 200, 220, 240, 260, 300];

  var state = {
    p: null,            // the project
    files: new Map(),   // path -> Blob (parchment, art, library pictures, recipes)
    images: new Map(),  // path -> ImageBitmap, decoded from files
    recipes: new Map(), // spot id -> {title, cover (object URL), error}
    undo: [], redo: [],
    dirty: false,
    trail: null
  };
  var listeners = [];

  function onChange(f) { listeners.push(f); }
  function changed(what) {
    state.trail = null;
    state.dirty = true;
    for (var i = 0; i < listeners.length; i++) listeners[i](what || 'all');
  }

  function trail() {
    if (!state.trail) state.trail = new Geom.Trail(state.p.trail);
    return state.trail;
  }

  /** Saves the project for undo before a change. */
  function snapshot() {
    state.undo.push(JSON.stringify(state.p));
    if (state.undo.length > 120) state.undo.shift();
    state.redo.length = 0;
  }
  function undo() {
    if (!state.undo.length) return false;
    state.redo.push(JSON.stringify(state.p));
    state.p = JSON.parse(state.undo.pop());
    changed('undo'); return true;
  }
  function redo() {
    if (!state.redo.length) return false;
    state.undo.push(JSON.stringify(state.p));
    state.p = JSON.parse(state.redo.pop());
    changed('undo'); return true;
  }

  // ---- the starter template ----------------------------------------------------------
  function template() {
    var h = 3150;
    var pts = [[206, h - 300], [300, h - 620], [120, h - 1050], [290, h - 1500], [110, h - 1950], [300, h - 2400], [206, 420]];
    var anchors = [];
    for (var i = 0; i < pts.length; i++) {
      var prev = pts[Math.max(0, i - 1)], next = pts[Math.min(pts.length - 1, i + 1)];
      var tx = (next[0] - prev[0]) / 5, ty = (next[1] - prev[1]) / 5;
      anchors.push({ x: pts[i][0], y: pts[i][1], ix: pts[i][0] - tx, iy: pts[i][1] - ty, ox: pts[i][0] + tx, oy: pts[i][1] + ty, smooth: true });
    }
    var spots = [];
    var story = { 3: true, 7: true };
    for (var k = 0; k < 10; k++) {
      var kind = story[k] ? 'story' : 'recipe';
      spots.push({ id: String(k + 1), kind: kind, name: kind === 'story' ? 'Story stop ' + (k + 1) : 'Spot ' + (k + 1),
        along: 0.06 + k * 0.094, side: k % 2 ? 'right' : 'left', xp: GAPS[k], story: '', recipe: '', icon: '', reward: null });
    }
    return {
      format: 'praline-map-project', v: 1,
      world: { id: 'new-world', kind: 'mission', name: 'A New World', intro: '', height: h, ending: { title: '', story: '' },
        title: { text: 'A New World', x: 206, y: 150, size: 40, color: '#3B2414', rotate: -2, font: 'title' } },
      trail: anchors,
      spots: spots,
      library: {},
      items: [],
      parchment: { mode: 'tile', file: '' },
      art: null,
      nextSpot: 11, nextItem: 1,
      released: false
    };
  }

  function fresh(p) {
    state.p = p; state.files = new Map(); state.images = new Map(); state.recipes = new Map();
    state.undo = []; state.redo = []; state.trail = null; state.dirty = false;
  }

  // ---- lookups ------------------------------------------------------------------------
  function spot(id) { var s = state.p.spots; for (var i = 0; i < s.length; i++) if (s[i].id === id) return s[i]; return null; }
  function item(id) { var s = state.p.items; for (var i = 0; i < s.length; i++) if (s[i].id === id) return s[i]; return null; }
  function sortSpots() { state.p.spots.sort(function (a, b) { return a.along - b.along; }); }

  /** The library entry for an id: the project's own, or a built-in effect ("fx-rays"). */
  function lib(id) {
    if (state.p.library[id]) return state.p.library[id];
    if (id && id.indexOf('fx-') === 0 && FX.indexOf(id.substring(3)) >= 0) return { kind: 'fx', fx: id.substring(3), name: FX_NAMES[id.substring(3)] };
    return null;
  }

  /** A library id from a file name: "Palm Tree 2.png" -> "palm-tree-2" (unique in the project). */
  function libId(name) {
    var base = name.replace(/\.[^.]+$/, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').substring(0, 28) || 'item';
    var id = base, n = 2;
    while (state.p.library[id] || id.indexOf('fx-') === 0) id = base + '-' + (n++);
    return id;
  }

  /** Party fame needed to reach each spot (cumulative xp). */
  function thresholds() {
    var out = [], at = 0;
    for (var i = 0; i < state.p.spots.length; i++) { at += Math.max(0, state.p.spots[i].xp | 0); out.push(at); }
    return out;
  }

  /** Library ids the world uses live (not baked), plus icons and rewards. */
  function usedLibrary() {
    var used = {};
    state.p.items.forEach(function (it) { if (!it.bake) used[it.lib] = true; });
    state.p.spots.forEach(function (s) {
      if (s.icon) used[s.icon] = true;
      if (s.reward && s.reward.item) used[s.reward.item] = true;
    });
    return Object.keys(used).filter(function (id) { return lib(id); });
  }

  // ---- checks before export -----------------------------------------------------------
  /** {errors: [...], warnings: [...]}; export is blocked while there are errors. */
  function check() {
    var p = state.p, errors = [], warnings = [];
    if (!/^[a-z0-9-]{1,32}$/.test(p.world.id)) errors.push('The world id must be 1 to 32 lowercase letters, digits or dashes (it is "' + p.world.id + '").');
    if (!p.world.name.trim()) errors.push('The world needs a name.');
    if (p.world.height < 1000 || p.world.height > 6000) errors.push('The map height must be between 1,000 and 6,000 units.');
    else if (p.world.height < 2400 || p.world.height > 4800) warnings.push('Maps between 2,400 and 4,800 units tall scroll best.');
    if (!trail().ok()) errors.push('Draw the trail first (at least two points).');
    if (!p.spots.length) errors.push('Add at least one spot on the trail.');
    var end = p.world.ending || {};
    if ((end.story || '').length > STORY_MAX) errors.push('The ending story is longer than ' + STORY_MAX + ' characters.');
    var ids = {};
    p.spots.forEach(function (s, i) {
      var label = '"' + (s.name || 'Spot ' + (i + 1)) + '"';
      if (!/^[a-z0-9-]{1,24}$/.test(s.id) || ids[s.id]) errors.push('Spot ' + label + ' has a broken id.');
      ids[s.id] = true;
      if (!s.name.trim()) errors.push('Spot ' + (i + 1) + ' needs a name.');
      if (s.story.length > STORY_MAX) errors.push('The story of ' + label + ' is longer than ' + STORY_MAX + ' characters.');
      if (!s.story.trim()) warnings.push(label + ' has no story yet.');
      if (s.kind === 'recipe' && !s.recipe) errors.push(label + ' is a recipe spot without a recipe.');
      if (s.kind === 'recipe' && s.recipe && state.recipes.get(s.id) && state.recipes.get(s.id).error) errors.push('The recipe of ' + label + ' cannot be read: ' + state.recipes.get(s.id).error);
      if (s.kind === 'recipe' && s.recipe && state.recipes.get(s.id) && !state.recipes.get(s.id).cover) warnings.push(label + "'s recipe has no cover picture, so players get no hint of what to cook.");
      if (!(s.xp > 0)) errors.push(label + ' needs some fame (xp) to reach it.');
      if (s.icon && !lib(s.icon)) errors.push(label + ' uses an icon that is no longer in the library.');
      if (s.reward && s.reward.item && !lib(s.reward.item)) errors.push(label + "'s reward uses a picture that is no longer in the library.");
    });
    for (var i = 1; i < p.spots.length; i++) if (p.spots[i].along - p.spots[i - 1].along < 0.01) warnings.push('"' + p.spots[i - 1].name + '" and "' + p.spots[i].name + '" sit almost on top of each other.');
    if (!p.art) warnings.push('No map art yet: the export will only have the parchment.');
    var live = p.items.filter(function (it) { return !it.bake; });
    if (live.length > 40) warnings.push(live.length + ' live items: phones stay smooth up to about 40 per world. Bake still pictures where you can.');
    var worst = busiestScreen();
    if (worst > 12) warnings.push('One screen has ' + worst + ' animations at once; keep it to 12 or fewer.');
    p.items.forEach(function (it) { if (!lib(it.lib)) errors.push('An item uses "' + it.lib + '", which is no longer in the library.'); });
    if (!p.world.title.text.trim()) warnings.push('The map has no title painted on it.');
    return { errors: errors, warnings: warnings };
  }

  /** The most animated items (sheets and effects) inside any one phone screen (about 900 units tall). */
  function busiestScreen() {
    var ys = state.p.items.filter(function (it) { var l = lib(it.lib); return l && !it.bake && (l.kind === 'sheet' || l.kind === 'fx'); })
      .map(function (it) { return it.y; }).sort(function (a, b) { return a - b; });
    var best = 0, j = 0;
    for (var i = 0; i < ys.length; i++) { while (ys[i] - ys[j] > 900) j++; best = Math.max(best, i - j + 1); }
    return best;
  }

  return {
    W: W, PPU: PPU, SLICE: SLICE, STORY_MAX: STORY_MAX, FX: FX, FX_NAMES: FX_NAMES, FX_PARAMS: FX_PARAMS, GAPS: GAPS,
    state: state, onChange: onChange, changed: changed, trail: trail, snapshot: snapshot, undo: undo, redo: redo,
    template: template, fresh: fresh, spot: spot, item: item, sortSpots: sortSpots, lib: lib, libId: libId,
    thresholds: thresholds, usedLibrary: usedLibrary, check: check
  };
})();
