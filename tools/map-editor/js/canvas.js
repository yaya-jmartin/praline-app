// The map canvas: drawing the world being edited, scrolling and zooming, and every mouse gesture
// (trail pen, spots on the trail, dragging items and the title, dropping library items).
var MapView = (function () {
  var S = Model.state;
  var cv, g, wrap;
  var view = { k: 1.6, top: 0, left: 0 };   // k = screen px per map unit, top/left = map units at the canvas corner
  var tool = 'select';
  var sel = null;                            // {type: 'spot'|'item'|'title'|'anchor', id, index}
  var drag = null;
  var hover = null;
  var show = { parchment: true, art: true, items: true, trail: true, spots: true, title: true, slices: false, animate: true };
  var artOpacity = 1;
  var covers = new Map();                    // object URL -> HTMLImageElement (recipe covers on spot markers)
  var onSelect = function () {};
  var t0 = performance.now();
  var needs = true;

  function init(canvas, container, selectCb) {
    cv = canvas; wrap = container; g = cv.getContext('2d'); onSelect = selectCb;
    new ResizeObserver(resize).observe(wrap);
    cv.addEventListener('mousedown', down);
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', up);
    cv.addEventListener('dblclick', dbl);
    cv.addEventListener('wheel', wheel, { passive: false });
    cv.addEventListener('dragover', function (e) { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; });
    cv.addEventListener('drop', dropLib);
    cv.addEventListener('mouseleave', function () { hover = null; Panels.status(''); });
    resize();
    requestAnimationFrame(frame);
  }

  function resize() {
    var r = wrap.getBoundingClientRect(), dpr = window.devicePixelRatio || 1;
    cv.width = Math.round(r.width * dpr); cv.height = Math.round(r.height * dpr);
    cv.style.width = r.width + 'px'; cv.style.height = r.height + 'px';
    clampView(); redraw();
  }
  function size() { var r = wrap.getBoundingClientRect(); return { w: r.width, h: r.height }; }

  function clampView() {
    if (!S.p) return;
    var s = size(), mapW = Model.W * view.k;
    view.left = mapW + 80 < s.w ? -(s.w - mapW) / 2 / view.k : Math.max(-40 / view.k, Math.min(Model.W + 40 / view.k - s.w / view.k, view.left));
    var maxTop = S.p.world.height - s.h / view.k + 60 / view.k;
    view.top = Math.max(-60 / view.k, Math.min(Math.max(-60 / view.k, maxTop), view.top));
  }

  function fitWidth() { var s = size(); view.k = Math.max(0.3, Math.min(1.8, (s.w - 80) / Model.W)); clampView(); redraw(); }
  function zoomBy(f, cx, cy) {
    var s = size();
    if (cx === undefined) { cx = s.w / 2; cy = s.h / 2; }
    var m = toMap(cx, cy);
    view.k = Math.max(0.25, Math.min(8, view.k * f));
    view.left = m.x - cx / view.k; view.top = m.y - cy / view.k;
    clampView(); redraw();
  }
  function scrollTo(y) { view.top = y - size().h / view.k / 2; clampView(); redraw(); }
  /** Shows where the party sets off (the trail's start), or the bottom of the map. */
  function scrollToBottom() { var tr = Model.trail(); if (tr.ok()) scrollTo(tr.at(0).y - size().h * 0.25 / view.k); else { view.top = S.p.world.height; clampView(); redraw(); } }

  function toMap(cx, cy) { return { x: cx / view.k + view.left, y: cy / view.k + view.top }; }
  function local(e) { var r = cv.getBoundingClientRect(); return { cx: e.clientX - r.left, cy: e.clientY - r.top }; }

  function redraw() { needs = true; }
  function setTool(t) { tool = t; cv.dataset.tool = t; if (t !== 'trail' && sel && sel.type === 'anchor') select(null); redraw(); }
  function select(s) { sel = s; onSelect(s); redraw(); }

  function animated() {
    if (!show.animate || !show.items) return false;
    for (var i = 0; i < S.p.items.length; i++) {
      var it = S.p.items[i], l = Model.lib(it.lib);
      if (l && !it.bake && (l.kind === 'sheet' || l.kind === 'fx' || (it.bob && it.bob.amp))) return true;
    }
    return false;
  }

  function frame() {
    if (S.p && (needs || animated())) { needs = false; paint(); }
    requestAnimationFrame(frame);
  }

  // ---- drawing -------------------------------------------------------------------------------
  function paint() {
    var dpr = window.devicePixelRatio || 1, s = size(), p = S.p, H = p.world.height;
    var time = (performance.now() - t0) / 1000;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.fillStyle = '#2B2420'; g.fillRect(0, 0, s.w, s.h);
    var k = view.k;
    g.setTransform(dpr * k, 0, 0, dpr * k, -view.left * k * dpr, -view.top * k * dpr);
    var y0 = view.top, y1 = view.top + s.h / k;
    // page shadow + parchment
    g.fillStyle = 'rgba(0,0,0,0.35)'; g.fillRect(4, 6, Model.W, H);
    g.fillStyle = '#E9D8B6'; g.fillRect(0, 0, Model.W, H);
    g.save(); g.beginPath(); g.rect(0, 0, Model.W, H); g.clip();
    if (show.parchment) {
      var parch = p.parchment.file ? S.images.get(p.parchment.file) : Panels.defaultParchment();
      Draw.drawParchment(g, parch, p.parchment.mode, H, y0, y1);
    }
    if (show.art && p.art) {
      var art = S.images.get(p.art.file);
      if (art) { g.globalAlpha = artOpacity; g.drawImage(art, 0, 0, Model.W, art.height * Model.W / art.width); g.globalAlpha = 1; }
    }
    if (show.items) items('under', time, y0, y1);
    if (show.trail) drawTrail();
    if (show.spots) drawSpots();
    if (show.items) items('over', time, y0, y1);
    if (show.items) motes(time, y0, y1);
    if (show.title) Draw.drawTitle(g, p.world.title);
    if (show.slices) sliceGuides();
    g.restore();
    drawSelection();
    if (tool === 'trail') drawAnchors();
  }

  function items(layer, time, y0, y1) {
    Draw.ordered(S.p.items, layer).forEach(function (it) {
      var l = Model.lib(it.lib);
      if (l && l.kind === 'fx' && l.fx === 'motes') return;
      var b = Draw.bounds(it, l);
      var reach = l && l.kind === 'fx' && (l.fx === 'rays' || l.fx === 'beam');
      if (!reach && (it.y + b.y + b.h < y0 - 40 || it.y + b.y > y1 + 40)) return;
      Draw.drawItem(g, it, l, l && l.file ? S.images.get(l.file) : null, show.animate ? time : 0);
    });
  }
  function motes(time, y0, y1) {
    S.p.items.forEach(function (it) { var l = Model.lib(it.lib); if (l && l.kind === 'fx' && l.fx === 'motes') Draw.drawMotes(g, it.params || {}, S.p.world.height, y0, y1, show.animate ? time : 0); });
  }

  function drawTrail() {
    var tr = Model.trail();
    if (tr.pts.length < 2) return;
    g.lineCap = 'round'; g.lineJoin = 'round';
    g.beginPath(); tr.pts.forEach(function (p, i) { if (i) g.lineTo(p.x, p.y); else g.moveTo(p.x, p.y); });
    g.strokeStyle = 'rgba(43,28,16,0.85)'; g.lineWidth = 9 / Math.max(1, view.k / 1.6); g.stroke();
    g.strokeStyle = '#F2C14E'; g.lineWidth = 5 / Math.max(1, view.k / 1.6); g.setLineDash([10, 7]); g.stroke(); g.setLineDash([]);
    var a = tr.at(0), b = tr.at(1);
    flag(a.x, a.y, 'Start', '#5ED3B2'); flag(b.x, b.y, 'End', '#E0479E');
  }
  function flag(x, y, text, col) {
    g.fillStyle = col; g.strokeStyle = '#2B1C10'; g.lineWidth = 1.2;
    g.beginPath(); g.arc(x, y, 5, 0, Math.PI * 2); g.fill(); g.stroke();
    g.font = '600 9px PralineBold, serif'; g.textAlign = 'left'; g.textBaseline = 'middle';
    g.lineWidth = 3; g.strokeStyle = 'rgba(255,248,232,0.9)'; g.strokeText(text, x + 8, y); g.fillStyle = '#2B1C10'; g.fillText(text, x + 8, y);
  }

  function cover(url) {
    if (!url) return null;
    var im = covers.get(url);
    if (!im) { im = new Image(); im.onload = redraw; im.src = url; covers.set(url, im); }
    return im.complete && im.naturalWidth ? im : null;
  }

  function drawSpots() {
    var tr = Model.trail(), th = Model.thresholds();
    S.p.spots.forEach(function (s, i) {
      var at = tr.at(s.along), x = at.x, y = at.y;
      var col = s.kind === 'recipe' ? '#E0479E' : '#3FB8A0';
      var selected = sel && sel.type === 'spot' && sel.id === s.id;
      g.fillStyle = 'rgba(43,28,16,0.25)'; g.beginPath(); g.ellipse(x + 1.5, y + 3, 15, 7, 0, 0, Math.PI * 2); g.fill();
      g.fillStyle = '#FFF8EC'; g.strokeStyle = col; g.lineWidth = selected ? 4 : 3;
      g.beginPath(); g.arc(x, y, 13, 0, Math.PI * 2); g.fill(); g.stroke();
      g.strokeStyle = '#2B1C10'; g.lineWidth = 1.1; g.beginPath(); g.arc(x, y, 14.6, 0, Math.PI * 2); g.stroke();
      var info = S.recipes.get(s.id), icon = s.icon && Model.lib(s.icon), img = null;
      if (icon && icon.file) img = S.images.get(icon.file);
      else if (info && info.cover) img = cover(info.cover);
      if (img) {
        g.save(); g.beginPath(); g.arc(x, y, 11.5, 0, Math.PI * 2); g.clip();
        var w = img.width || img.naturalWidth, h = img.height || img.naturalHeight, m = Math.min(w, h);
        g.drawImage(img, (w - m) / 2, (h - m) / 2, m, m, x - 11.5, y - 11.5, 23, 23); g.restore();
      } else {
        g.fillStyle = col; g.font = '600 11px PralineBold, serif'; g.textAlign = 'center'; g.textBaseline = 'middle';
        g.fillText(s.kind === 'recipe' ? String(i + 1) : '✦', x, y + 0.5);
      }
      // label
      var name = s.name || '(no name)', sub = (s.kind === 'recipe' ? (info && info.title ? (info.title === s.name ? 'recipe' : info.title) : 'no recipe yet') : (s.reward && s.reward.name ? 'reward: ' + s.reward.name : 'story stop')) + ' · ' + th[i] + ' fame';
      g.font = '600 9.5px PralineBold, serif'; var w1 = g.measureText(name).width;
      g.font = '7.5px PralineBody, serif'; var w2 = g.measureText(sub).width;
      var bw = Math.max(w1, w2) + 12, bh = 24, lx = s.side === 'left' ? x - 22 - bw : x + 22, ly = y - bh / 2;
      g.fillStyle = s.kind === 'recipe' ? '#F7B6D8' : '#A7E3D5'; roundRect(lx + 2, ly + 2, bw, bh, 6); g.fill();
      g.fillStyle = selected ? '#FFFDF6' : '#FFF6E6'; roundRect(lx, ly, bw, bh, 6); g.fill();
      g.strokeStyle = '#2B1C10'; g.lineWidth = selected ? 1.6 : 1; g.stroke();
      g.textAlign = 'left'; g.textBaseline = 'alphabetic'; g.fillStyle = '#2B1C10';
      g.font = '600 9.5px PralineBold, serif'; g.fillText(name, lx + 6, ly + 10.5);
      g.font = '7.5px PralineBody, serif'; g.fillStyle = (s.kind === 'recipe' && !s.recipe) ? '#B3261E' : '#6A5A50'; g.fillText(sub, lx + 6, ly + 19.5);
    });
  }
  function roundRect(x, y, w, h, r) { g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath(); }

  function sliceGuides() {
    g.save(); g.setLineDash([6, 6]); g.strokeStyle = 'rgba(30,90,200,0.7)'; g.lineWidth = 1 / view.k * 1.2;
    g.font = (11 / view.k) + 'px sans-serif'; g.fillStyle = 'rgba(30,90,200,0.9)'; g.textAlign = 'left';
    for (var y = Model.SLICE, n = 1; y < S.p.world.height; y += Model.SLICE, n++) {
      g.beginPath(); g.moveTo(0, y); g.lineTo(Model.W, y); g.stroke();
      g.fillText('slice ' + (n + 1) + ' · ' + Math.round(y * Model.PPU) + ' px', 4, y + 13 / view.k);
    }
    g.restore();
  }

  function drawSelection() {
    if (!sel) return;
    var b = selBox(sel);
    if (!b) return;
    g.save(); g.setLineDash([4 / view.k, 3 / view.k]); g.strokeStyle = '#1E64D2'; g.lineWidth = 1.5 / view.k;
    g.strokeRect(b.x, b.y, b.w, b.h); g.restore();
  }
  function selBox(s) {
    if (s.type === 'item') {
      var it = Model.item(s.id); if (!it) return null;
      var b = Draw.bounds(it, Model.lib(it.lib));
      return { x: it.x + b.x, y: it.y + b.y, w: b.w, h: b.h };
    }
    if (s.type === 'title') return titleBox();
    return null;
  }
  function titleBox() {
    var t = S.p.world.title; if (!t.text) return null;
    g.save();
    g.font = (t.font === 'body' ? '600 ' : '') + t.size + 'px ' + (t.font === 'body' ? 'PralineBold' : 'PralineTitle') + ', serif';
    var w = g.measureText(t.text).width; g.restore();
    return { x: t.x - w / 2 - 6, y: t.y - t.size * 0.65, w: w + 12, h: t.size * 1.3 };
  }

  function drawAnchors() {
    var a = S.p.trail, r = 4.5 / view.k, hr = 3.5 / view.k;
    g.lineWidth = 1 / view.k;
    var si = sel && sel.type === 'anchor' ? sel.index : -1;
    a.forEach(function (p, i) {
      var near = Math.abs(i - si) <= 1;
      if (near && si >= 0) {
        g.strokeStyle = '#1E64D2';
        if (i > 0) { g.beginPath(); g.moveTo(p.x, p.y); g.lineTo(p.ix, p.iy); g.stroke(); circle(p.ix, p.iy, hr); }
        if (i < a.length - 1) { g.beginPath(); g.moveTo(p.x, p.y); g.lineTo(p.ox, p.oy); g.stroke(); circle(p.ox, p.oy, hr); }
      }
      g.fillStyle = i === si ? '#1E64D2' : '#FFFFFF'; g.strokeStyle = '#1E64D2';
      if (p.smooth === false) { g.save(); g.translate(p.x, p.y); g.rotate(Math.PI / 4); g.fillRect(-r, -r, 2 * r, 2 * r); g.strokeRect(-r, -r, 2 * r, 2 * r); g.restore(); }
      else { g.fillRect(p.x - r, p.y - r, 2 * r, 2 * r); g.strokeRect(p.x - r, p.y - r, 2 * r, 2 * r); }
    });
  }
  function circle(x, y, r) { g.fillStyle = '#FFFFFF'; g.beginPath(); g.arc(x, y, r, 0, Math.PI * 2); g.fill(); g.stroke(); }

  // ---- hit tests -------------------------------------------------------------------------------
  function spotAt(m) {
    var tr = Model.trail(), best = null, bd = 16;
    S.p.spots.forEach(function (s) { var at = tr.at(s.along), d = Math.hypot(m.x - at.x, m.y - at.y); if (d < bd) { bd = d; best = s; } });
    if (best) return best;
    // labels
    for (var i = S.p.spots.length - 1; i >= 0; i--) {
      var s = S.p.spots[i], at2 = tr.at(s.along);
      var lx0 = s.side === 'left' ? at2.x - 22 - 110 : at2.x + 22, lx1 = s.side === 'left' ? at2.x - 22 : at2.x + 22 + 110;
      if (m.x >= lx0 && m.x <= lx1 && Math.abs(m.y - at2.y) <= 12) return s;
    }
    return null;
  }
  function itemAt(m) {
    var layers = ['over', 'under'];
    for (var L = 0; L < layers.length; L++) {
      var list = Draw.ordered(S.p.items, layers[L]).reverse();
      for (var i = 0; i < list.length; i++) {
        var it = list[i], b = Draw.bounds(it, Model.lib(it.lib));
        if (m.x >= it.x + b.x && m.x <= it.x + b.x + b.w && m.y >= it.y + b.y && m.y <= it.y + b.y + b.h) return it;
      }
    }
    return null;
  }
  function inBox(m, b) { return b && m.x >= b.x && m.x <= b.x + b.w && m.y >= b.y && m.y <= b.y + b.h; }
  function onTrail(m) { var n = Model.trail().nearest(m.x, m.y); return n.d <= 9 / Math.min(1, view.k / 1.6) ? n : null; }

  function anchorHit(m) {
    var a = S.p.trail, r = 7 / view.k, si = sel && sel.type === 'anchor' ? sel.index : -1;
    for (var i = 0; i < a.length; i++) {
      if (Math.abs(i - si) <= 1 && si >= 0) {
        if (i > 0 && Math.hypot(m.x - a[i].ix, m.y - a[i].iy) <= r) return { index: i, part: 'in' };
        if (i < a.length - 1 && Math.hypot(m.x - a[i].ox, m.y - a[i].oy) <= r) return { index: i, part: 'out' };
      }
    }
    for (var j = 0; j < a.length; j++) if (Math.hypot(m.x - a[j].x, m.y - a[j].y) <= r) return { index: j, part: 'anchor' };
    return null;
  }

  // ---- mouse ------------------------------------------------------------------------------------
  var spaceDown = false;
  function setSpace(v) { spaceDown = v; cv.style.cursor = v ? 'grab' : ''; }

  function down(e) {
    if (!S.p) return;
    var l = local(e), m = toMap(l.cx, l.cy);
    if (e.button === 1 || e.button === 2 || spaceDown || tool === 'pan') {
      e.preventDefault();
      drag = { kind: 'pan', cx: l.cx, cy: l.cy, left: view.left, top: view.top }; return;
    }
    if (e.button !== 0) return;
    if (tool === 'trail') return trailDown(e, m);
    var s = show.spots ? spotAt(m) : null;
    if (s) { select({ type: 'spot', id: s.id }); drag = { kind: 'spot', id: s.id, started: false, x: m.x, y: m.y }; return; }
    if (show.title && inBox(m, titleBox())) { select({ type: 'title' }); drag = { kind: 'title', dx: m.x - S.p.world.title.x, dy: m.y - S.p.world.title.y, started: false, x: m.x, y: m.y }; return; }
    var it = show.items ? itemAt(m) : null;
    if (it) { select({ type: 'item', id: it.id }); drag = { kind: 'item', id: it.id, dx: m.x - it.x, dy: m.y - it.y, started: false, x: m.x, y: m.y }; return; }
    select(null);
    drag = { kind: 'pan', cx: l.cx, cy: l.cy, left: view.left, top: view.top };
  }

  function trailDown(e, m) {
    var a = S.p.trail, hit = anchorHit(m);
    if (hit) {
      select({ type: 'anchor', index: hit.index });
      drag = { kind: 'anchor', index: hit.index, part: hit.part, alt: e.altKey, started: false, x: m.x, y: m.y };
      return;
    }
    var n = a.length >= 2 ? onTrail(m) : null;
    if (n) {
      Model.snapshot();
      var idx = Geom.split(a, n.seg, n.segT);
      Model.changed('trail'); select({ type: 'anchor', index: idx });
      drag = { kind: 'anchor', index: idx, part: 'anchor', started: true, x: m.x, y: m.y, snap: true };
      return;
    }
    Model.snapshot();
    var atStart = e.shiftKey && a.length > 0;
    var from = a.length ? (atStart ? a[0] : a[a.length - 1]) : null;
    var p = Geom.anchorAt(m.x, m.y, from);
    if (atStart) { var t = p.ix; p.ix = p.ox; p.ox = t; t = p.iy; p.iy = p.oy; p.oy = t; a.unshift(p); } else a.push(p);
    var index = atStart ? 0 : a.length - 1;
    Model.changed('trail'); select({ type: 'anchor', index: index });
    drag = { kind: 'pull', index: index, start: atStart, started: true, x: m.x, y: m.y };
  }

  function move(e) {
    if (!S.p) return;
    var l = local(e), m = toMap(l.cx, l.cy);
    if (!drag) {
      if (e.target === cv) {
        Panels.status(Math.round(m.x) + ', ' + Math.round(m.y) + ' units · ' + Math.round(m.x * Model.PPU) + ', ' + Math.round(m.y * Model.PPU) + ' px');
        var hv = tool === 'trail' ? (anchorHit(m) ? 'move' : onTrail(m) ? 'copy' : 'crosshair') : (spotAt(m) ? 'grab' : itemAt(m) || inBox(m, titleBox()) ? 'move' : onTrail(m) ? 'copy' : '');
        if (!spaceDown) cv.style.cursor = hv;
      }
      return;
    }
    if (drag.kind === 'pan') {
      view.left = drag.left - (l.cx - drag.cx) / view.k; view.top = drag.top - (l.cy - drag.cy) / view.k;
      clampView(); redraw(); return;
    }
    if (!drag.started) {
      if (Math.hypot(m.x - drag.x, m.y - drag.y) * view.k < 3) return;
      drag.started = true; Model.snapshot();
    }
    if (drag.kind === 'spot') {
      var s = Model.spot(drag.id); s.along = Math.max(0, Math.min(1, Model.trail().nearest(m.x, m.y).t));
      Model.changed('spots');
    } else if (drag.kind === 'item') {
      var it = Model.item(drag.id); it.x = round(m.x - drag.dx); it.y = round(m.y - drag.dy); Model.changed('items');
    } else if (drag.kind === 'title') {
      var t = S.p.world.title; t.x = round(m.x - drag.dx); t.y = round(m.y - drag.dy); Model.changed('title');
    } else if (drag.kind === 'anchor') {
      var p = S.p.trail[drag.index];
      if (drag.part === 'anchor') {
        var dx = m.x - p.x, dy = m.y - p.y;
        p.x += dx; p.y += dy; p.ix += dx; p.iy += dy; p.ox += dx; p.oy += dy;
      } else {
        var mine = drag.part, other = mine === 'in' ? 'out' : 'in';
        setHandle(p, mine, m.x, m.y);
        if (e.altKey) p.smooth = false;
        if (p.smooth !== false) mirror(p, mine, other);
      }
      Model.changed('trail');
    } else if (drag.kind === 'pull') {
      var q = S.p.trail[drag.index];
      setHandle(q, drag.start ? 'in' : 'out', m.x, m.y);
      mirror(q, drag.start ? 'in' : 'out', drag.start ? 'out' : 'in');
      Model.changed('trail');
    }
    redraw();
  }

  function setHandle(p, part, x, y) { if (part === 'in') { p.ix = x; p.iy = y; } else { p.ox = x; p.oy = y; } }
  /** Keeps a smooth anchor's handles in a straight line, each keeping its own length. */
  function mirror(p, from, to) {
    var fx = from === 'in' ? p.ix : p.ox, fy = from === 'in' ? p.iy : p.oy;
    var tx = to === 'in' ? p.ix : p.ox, ty = to === 'in' ? p.iy : p.oy;
    var len = Math.hypot(tx - p.x, ty - p.y), dx = p.x - fx, dy = p.y - fy, d = Math.hypot(dx, dy);
    if (d < 1e-6) return;
    if (len < 1e-3) len = d;
    setHandle(p, to, p.x + dx / d * len, p.y + dy / d * len);
  }

  function up() {
    if (!drag) return;
    var d = drag; drag = null;
    if (d.kind === 'spot' && d.started) { Model.sortSpots(); Model.changed('spots'); }
    if (d.started || d.kind === 'pull') onSelect(sel);
  }

  async function dbl(e) {
    var l = local(e), m = toMap(l.cx, l.cy);
    if (tool === 'trail') {
      var hit = anchorHit(m);
      if (hit && hit.part === 'anchor') {
        Model.snapshot();
        var a = S.p.trail, p = a[hit.index];
        if (p.smooth === false) {
          var prev = a[Math.max(0, hit.index - 1)], next = a[Math.min(a.length - 1, hit.index + 1)];
          var tx = (next.x - prev.x) / 6, ty = (next.y - prev.y) / 6;
          p.ix = p.x - tx; p.iy = p.y - ty; p.ox = p.x + tx; p.oy = p.y + ty; p.smooth = true;
        } else { p.ix = p.x; p.iy = p.y; p.ox = p.x; p.oy = p.y; p.smooth = false; }
        Model.changed('trail');
      }
      return;
    }
    var s = spotAt(m);
    if (s) { await removeSpot(s.id); return; }
    var n = Model.trail().ok() ? onTrail(m) : null;
    if (n) addSpot(n.t);
  }

  function addSpot(t) {
    Model.snapshot();
    var p = S.p, id = String(p.nextSpot++);
    var prev = p.spots.filter(function (s) { return s.along < t; }).length;
    var s = { id: id, kind: 'recipe', name: 'Spot ' + id, along: t, side: Model.trail().at(t).x > Model.W / 2 ? 'left' : 'right',
      xp: Model.GAPS[Math.min(prev, Model.GAPS.length - 1)], story: '', recipe: '', icon: '', reward: null };
    p.spots.push(s); Model.sortSpots(); Model.changed('spots');
    select({ type: 'spot', id: id });
    UI.toast('Spot added. Double click it to remove it.');
  }

  async function removeSpot(id) {
    var s = Model.spot(id); if (!s) return;
    var warn = S.p.released ? ' This world has already shipped: removing a spot moves every party that is past it.' : '';
    var has = s.story.trim() || s.recipe;
    if (has || S.p.released) {
      var ok = await UI.confirm('Remove "' + s.name + '"?', (has ? 'Its story' + (s.recipe ? ' and recipe' : '') + ' will be removed too.' : '') + warn, 'Remove', true);
      if (!ok) return;
    }
    Model.snapshot();
    S.p.spots = S.p.spots.filter(function (x) { return x.id !== id; });
    if (sel && sel.id === id) select(null);
    Model.changed('spots');
  }

  function wheel(e) {
    e.preventDefault();
    var l = local(e);
    if (e.ctrlKey || e.metaKey) { zoomBy(Math.exp(-e.deltaY * 0.0022), l.cx, l.cy); return; }
    if (e.altKey && sel && sel.type === 'item') {
      var it = Model.item(sel.id);
      if (!drag) Model.snapshot();
      it.scale = Math.max(0.05, Math.min(10, round2((it.scale || 1) * Math.exp(-e.deltaY * 0.0015))));
      Model.changed('items'); onSelect(sel); return;
    }
    view.top += (e.shiftKey ? 0 : e.deltaY) / view.k;
    view.left += (e.shiftKey ? e.deltaY : e.deltaX) / view.k;
    clampView(); redraw();
  }

  function dropLib(e) {
    e.preventDefault();
    var id = e.dataTransfer.getData('text/x-praline-lib');
    if (!id || !Model.lib(id)) return;
    var l = local(e), m = toMap(l.cx, l.cy);
    placeItem(id, m.x, m.y);
  }

  function placeItem(id, x, y) {
    var lib = Model.lib(id), p = S.p;
    if (x === undefined) { var s = size(); x = Model.W / 2; y = view.top + s.h / view.k / 2; }
    Model.snapshot();
    var it = { id: 'i' + p.nextItem++, lib: id, x: round(x), y: round(y), scale: 1, rot: 0, flip: false,
      layer: lib.kind === 'fx' && /^(rays|halo|glow|beam|motes|sparks)$/.test(lib.fx) ? 'over' : 'under', z: 0, alpha: 1, bake: false };
    if (lib.kind === 'fx') {
      it.params = JSON.parse(JSON.stringify(Model.FX_PARAMS[lib.fx] || {}));
      if (lib.fx === 'beam') { it.params.tx = round(x - 80); it.params.ty = round(y + 120); }
    }
    p.items.push(it); Model.changed('items');
    select({ type: 'item', id: it.id });
  }

  function round(v) { return Math.round(v * 10) / 10; }
  function round2(v) { return Math.round(v * 100) / 100; }

  function nudge(dx, dy) {
    if (!sel || (sel.type !== 'item' && sel.type !== 'title')) return false;
    Model.snapshot();
    var o = sel.type === 'item' ? Model.item(sel.id) : S.p.world.title;
    o.x = round(o.x + dx); o.y = round(o.y + dy); Model.changed(sel.type === 'item' ? 'items' : 'title'); onSelect(sel);
    return true;
  }

  function deleteSelection() {
    if (!sel) return false;
    if (sel.type === 'spot') { removeSpot(sel.id); return true; }
    if (sel.type === 'item') { Model.snapshot(); S.p.items = S.p.items.filter(function (x) { return x.id !== sel.id; }); select(null); Model.changed('items'); return true; }
    if (sel.type === 'anchor') {
      if (S.p.trail.length <= 2) { UI.toast('A trail needs at least two points.', true); return true; }
      Model.snapshot(); S.p.trail.splice(sel.index, 1); select(null); Model.changed('trail'); return true;
    }
    return false;
  }

  function duplicate() {
    if (!sel || sel.type !== 'item') return;
    var it = Model.item(sel.id); Model.snapshot();
    var c = JSON.parse(JSON.stringify(it)); c.id = 'i' + S.p.nextItem++; c.x += 12; c.y += 12;
    S.p.items.push(c); Model.changed('items'); select({ type: 'item', id: c.id });
  }

  return { init: init, redraw: redraw, setTool: setTool, getTool: function () { return tool; }, select: select, selection: function () { return sel; },
    fitWidth: fitWidth, zoomBy: zoomBy, scrollTo: scrollTo, scrollToBottom: scrollToBottom, show: show,
    setArtOpacity: function (v) { artOpacity = v; redraw(); }, placeItem: placeItem, addSpot: addSpot, removeSpot: removeSpot,
    nudge: nudge, deleteSelection: deleteSelection, duplicate: duplicate, setSpace: setSpace, view: view };
})();
