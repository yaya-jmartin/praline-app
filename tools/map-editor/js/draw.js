// Drawing shared by the editor canvas, the phone preview and the export. Every function draws in map
// units: the caller sets the canvas transform so that 1 unit = k pixels. Effects follow MissionMap.kt's look.
var Draw = (function () {
  var fontsReady = null;
  function loadFonts() {
    if (fontsReady) return fontsReady;
    var faces = [new FontFace('PralineTitle', 'url(' + ASSETS.fontTitle + ')'), new FontFace('PralineBody', 'url(' + ASSETS.fontBody + ')'),
      new FontFace('PralineBold', 'url(' + ASSETS.fontBold + ')')];
    fontsReady = Promise.all(faces.map(function (f) { return f.load().then(function (x) { document.fonts.add(x); }); })).catch(function () {});
    return fontsReady;
  }

  var parchment = null;
  function loadParchment() {
    if (parchment) return parchment;
    parchment = fetch(ASSETS.parchment).then(function (r) { return r.blob(); }).then(createImageBitmap);
    return parchment;
  }

  function rand(i, seed) { var x = Math.sin(i * 127.1 + (seed || 0) * 311.7) * 43758.5453; return x - Math.floor(x); }
  function rgba(hex, a) {
    hex = (hex || '#FFFFFF').replace('#', '');
    var al = 1;
    if (hex.length === 8) { al = parseInt(hex.substring(0, 2), 16) / 255; hex = hex.substring(2); }
    var n = parseInt(hex, 16);
    return 'rgba(' + (n >> 16 & 255) + ',' + (n >> 8 & 255) + ',' + (n & 255) + ',' + (al * (a === undefined ? 1 : a)) + ')';
  }

  /** Parchment behind map units y0..y1: a tile repeated down the map, or one full-height picture. */
  function drawParchment(g, img, mode, height, y0, y1) {
    if (!img || mode === 'none') return;
    var w = 412, th = img.height * w / img.width;
    if (mode === 'full') { g.drawImage(img, 0, 0, w, height); return; }
    var start = Math.max(0, Math.floor(y0 / th));
    for (var k = start; k * th < Math.min(y1, height); k++) {
      var h = Math.min(th, height - k * th);
      g.drawImage(img, 0, 0, img.width, img.height * h / th, 0, k * th, w, h);
    }
  }

  /** The map title, lettered over the map. */
  function drawTitle(g, t) {
    if (!t || !t.text) return;
    g.save();
    g.translate(t.x, t.y); g.rotate((t.rotate || 0) * Math.PI / 180);
    g.font = (t.font === 'body' ? '600 ' : '') + t.size + 'px ' + (t.font === 'body' ? 'PralineBold' : 'PralineTitle') + ', serif';
    g.textAlign = 'center'; g.textBaseline = 'middle';
    g.lineJoin = 'round';
    g.strokeStyle = 'rgba(255,248,232,0.85)'; g.lineWidth = t.size * 0.16; g.strokeText(t.text, 0, 0);
    g.fillStyle = t.color || '#3B2414'; g.fillText(t.text, 0, 0);
    g.restore();
  }

  // ---- items ---------------------------------------------------------------------------------
  /** One placed item. [img] is the decoded picture for sprites and sheets; [time] in seconds. */
  function drawItem(g, it, lib, img, time) {
    if (!lib) return;
    var bob = it.bob && it.bob.amp && it.bob.period ? it.bob.amp * Math.sin(time / it.bob.period * Math.PI * 2) : 0;
    g.save();
    g.globalAlpha = it.alpha === undefined ? 1 : it.alpha;
    g.translate(it.x, it.y + bob);
    if (lib.kind === 'fx') { drawFx(g, lib.fx, it.params || {}, it, time); g.restore(); return; }
    g.rotate((it.rot || 0) * Math.PI / 180);
    var sc = (it.scale || 1) / (lib.ppu || 3);
    g.scale(it.flip ? -sc : sc, sc);
    if (lib.blend === 'add') g.globalCompositeOperation = 'lighter';
    if (!img) {
      g.fillStyle = 'rgba(180,60,60,0.35)'; g.fillRect(-30, -60, 60, 60);
    } else if (lib.kind === 'sheet') {
      var f = frameAt(lib, time), cols = Math.max(1, lib.cols || 1);
      var sx = (f % cols) * lib.frameW, sy = Math.floor(f / cols) * lib.frameH;
      g.drawImage(img, sx, sy, lib.frameW, lib.frameH, -lib.ax, -lib.ay, lib.frameW, lib.frameH);
    } else {
      g.drawImage(img, -lib.ax, -lib.ay);
    }
    g.restore();
  }

  function frameAt(lib, time) {
    var n = Math.max(1, lib.frames || 1), i = Math.floor(time * (lib.fps || 12));
    if (lib.loop === 'once') return Math.min(n - 1, i);
    if (lib.loop === 'pingpong' && n > 1) { var p = i % (2 * n - 2); return p < n ? p : 2 * n - 2 - p; }
    return i % n;
  }

  /** Size of an item in map units around its anchor, for selection boxes: {x, y, w, h} relative to (it.x, it.y). */
  function bounds(it, lib) {
    if (!lib) return { x: -15, y: -30, w: 30, h: 30 };
    if (lib.kind === 'fx') {
      var p = it.params || {};
      var r = lib.fx === 'halo' ? (p.halo || 110) * 0.5 : lib.fx === 'glints' ? Math.max(p.rx || 60, 20) : lib.fx === 'glow' ? (p.halo || 2 * (p.r || 22)) : lib.fx === 'ripples' ? Math.max(p.rx || 30, 12) : 18;
      return { x: -r, y: -r, w: 2 * r, h: 2 * r };
    }
    var sc = (it.scale || 1) / (lib.ppu || 3);
    var w = (lib.kind === 'sheet' ? lib.frameW : lib.w) * sc, h = (lib.kind === 'sheet' ? lib.frameH : lib.h) * sc;
    var ax = lib.ax * sc, ay = lib.ay * sc;
    return { x: it.flip ? ax - w : -ax, y: -ay, w: w, h: h };
  }

  // ---- effects -------------------------------------------------------------------------------
  function drawFx(g, kind, p, it, t) {
    var d = Model.FX_PARAMS[kind] || {};
    function v(k) { return p[k] !== undefined && p[k] !== '' ? p[k] : d[k]; }
    var seed = it.id ? it.id.length * 7 + (it.id.charCodeAt(it.id.length - 1) || 0) : 0;
    switch (kind) {
      case 'rays': {
        var n = Math.max(1, v('count') | 0), a0 = v('from') * Math.PI / 180, a1 = v('to') * Math.PI / 180, len = v('length');
        g.globalCompositeOperation = 'lighter';
        for (var i = 0; i < n; i++) {
          var a = a0 + (a1 - a0) * (n === 1 ? 0.5 : i / (n - 1)) + 0.02 * Math.sin(t * 0.3 + i);
          var w = (0.035 + 0.025 * rand(i, seed)) * (1 + 0.15 * Math.sin(t * 0.5 + i * 1.7));
          var gr = g.createLinearGradient(0, 0, Math.cos(a) * len, Math.sin(a) * len);
          var al = v('alpha') * (0.55 + 0.45 * Math.sin(t * 0.4 + i * 2.3));
          gr.addColorStop(0, rgba(v('color'), al)); gr.addColorStop(1, rgba(v('color'), 0));
          g.fillStyle = gr;
          g.beginPath(); g.moveTo(0, 0);
          g.lineTo(Math.cos(a - w) * len, Math.sin(a - w) * len); g.lineTo(Math.cos(a + w) * len, Math.sin(a + w) * len);
          g.closePath(); g.fill();
        }
        break;
      }
      case 'halo': {
        var hr = v('halo'), r = v('r');
        var gh = g.createRadialGradient(0, 0, 0, 0, 0, hr * (1 + 0.04 * Math.sin(t)));
        gh.addColorStop(0, rgba(v('color'), 0.9)); gh.addColorStop(r / hr, rgba(v('color'), 0.55)); gh.addColorStop(1, rgba(v('color'), 0));
        g.globalCompositeOperation = 'lighter'; g.fillStyle = gh; g.beginPath(); g.arc(0, 0, hr * 1.05, 0, Math.PI * 2); g.fill();
        g.globalCompositeOperation = 'source-over'; g.fillStyle = rgba('#FFFBEA', 0.95); g.beginPath(); g.arc(0, 0, r, 0, Math.PI * 2); g.fill();
        break;
      }
      case 'glow': {
        var pf = 1 + 0.18 * Math.sin(t / Math.max(0.2, v('pulse')) * Math.PI * 2);
        var pr = v('r') * pf, ph2 = Math.max(pr, (v('halo') || 2 * v('r')) * pf);
        var gg = g.createRadialGradient(0, 0, 0, 0, 0, ph2);
        gg.addColorStop(0, rgba(v('color'), 0.85)); gg.addColorStop(Math.min(0.95, pr / ph2 * 0.6), rgba(v('color'), 0.35)); gg.addColorStop(1, rgba(v('color'), 0));
        g.globalCompositeOperation = 'lighter'; g.fillStyle = gg; g.beginPath(); g.arc(0, 0, ph2, 0, Math.PI * 2); g.fill();
        break;
      }
      case 'glints': {
        var cnt = v('count') | 0;
        g.globalCompositeOperation = 'lighter';
        for (var k = 0; k < cnt; k++) {
          var gx = (rand(k, seed) * 2 - 1) * v('rx'), gy = (rand(k + 50, seed) * 2 - 1) * v('ry');
          var tw = Math.max(0, Math.sin(t * 2.2 + k * 1.9));
          var s = 1.5 + 3.5 * tw;
          g.fillStyle = rgba(v('color'), 0.85 * tw);
          g.beginPath(); g.moveTo(gx, gy - s); g.lineTo(gx + s * 0.25, gy); g.lineTo(gx, gy + s); g.lineTo(gx - s * 0.25, gy); g.closePath(); g.fill();
          g.beginPath(); g.moveTo(gx - s, gy); g.lineTo(gx, gy + s * 0.25); g.lineTo(gx + s, gy); g.lineTo(gx, gy - s * 0.25); g.closePath(); g.fill();
        }
        break;
      }
      case 'smoke': case 'sparks': case 'sand': {
        var count = Math.round((kind === 'sparks' ? 14 : 10) * Math.max(0.1, v('rate')));
        if (kind === 'sparks') g.globalCompositeOperation = 'lighter';
        for (var j = 0; j < count; j++) {
          var life = kind === 'sparks' ? 1.6 : 5, ph = ((t + rand(j, seed) * life) % life) / life;
          var x, y, rad, al2;
          if (kind === 'smoke') { x = 10 * Math.sin(ph * 4 + j) + ph * 18; y = -ph * 90; rad = 3 + ph * 14; al2 = 0.35 * (1 - ph); }
          else if (kind === 'sparks') { x = (rand(j + 9, seed) - 0.5) * 14 + 5 * Math.sin(ph * 9 + j); y = -ph * 60; rad = 1.2; al2 = 1 - ph; }
          else { x = ph * 90 + (rand(j + 3, seed) - 0.5) * 20; y = -ph * 12 + (rand(j + 7, seed) - 0.5) * 16 + 4 * Math.sin(ph * 6 + j); rad = 1 + 2.5 * rand(j, seed); al2 = 0.5 * Math.sin(ph * Math.PI); }
          g.fillStyle = rgba(v('color'), al2);
          g.beginPath(); g.arc(x, y, rad, 0, Math.PI * 2); g.fill();
        }
        break;
      }
      case 'heat': {
        g.strokeStyle = rgba('#FFFFFF', 0.18); g.lineWidth = 1.2;
        for (var q = 0; q < 4; q++) {
          g.beginPath();
          for (var yy = 0; yy <= v('h'); yy += 4) {
            var xx = (q - 1.5) * v('w') / 4 + 3 * Math.sin(yy / 7 + t * 4 + q);
            if (yy === 0) g.moveTo(xx, -yy); else g.lineTo(xx, -yy);
          }
          g.stroke();
        }
        break;
      }
      case 'beam': {
        var tx = v('tx') - it.x, ty = v('ty') - it.y, bw = v('width') * (1 + 0.12 * Math.sin(t * 2));
        var gb = g.createLinearGradient(0, 0, tx, ty);
        gb.addColorStop(0, rgba(v('color'), 0.2)); gb.addColorStop(1, rgba(v('color'), 0.75));
        g.globalCompositeOperation = 'lighter'; g.strokeStyle = gb; g.lineCap = 'round';
        g.lineWidth = bw; g.beginPath(); g.moveTo(0, 0); g.lineTo(tx, ty); g.stroke();
        g.lineWidth = bw * 0.3; g.strokeStyle = rgba('#FFFFFF', 0.6); g.beginPath(); g.moveTo(0, 0); g.lineTo(tx, ty); g.stroke();
        var fr = v('flare') * (1 + 0.15 * Math.sin(t * 3));
        if (fr > 0) {
          var gf = g.createRadialGradient(tx, ty, 0, tx, ty, fr);
          gf.addColorStop(0, rgba('#FFFFFF', 0.9)); gf.addColorStop(0.3, rgba(v('color'), 0.6)); gf.addColorStop(1, rgba(v('color'), 0));
          g.fillStyle = gf; g.beginPath(); g.arc(tx, ty, fr, 0, Math.PI * 2); g.fill();
        }
        if (v('sparks') !== false && v('sparks') !== 0) {
          for (var bs = 0; bs < 12; bs++) {
            var bph = ((t + rand(bs, seed) * 1.4) % 1.4) / 1.4, ba = rand(bs + 20, seed) * Math.PI * 2;
            g.fillStyle = rgba('#FFE7A6', 1 - bph);
            g.beginPath(); g.arc(tx + Math.cos(ba) * bph * 40, ty + Math.sin(ba) * bph * 40 - bph * 14, 1.3, 0, Math.PI * 2); g.fill();
          }
        }
        break;
      }
      case 'flame': {
        var fw = v('w'), fh = v('h');
        g.globalCompositeOperation = 'lighter';
        var gl = g.createRadialGradient(0, -fh * 0.3, 0, 0, -fh * 0.3, fh * 1.4);
        gl.addColorStop(0, rgba(v('color'), 0.35)); gl.addColorStop(1, rgba(v('color'), 0));
        g.fillStyle = gl; g.beginPath(); g.arc(0, -fh * 0.3, fh * 1.4, 0, Math.PI * 2); g.fill();
        g.globalCompositeOperation = 'source-over';
        [[1, v('color'), 0.95], [0.55, v('core'), 1]].forEach(function (L, li) {
          var sw = fw * L[0] / 2, sh = fh * L[0] * (1 + 0.12 * Math.sin(t * 9 + li * 2)), lean = 3 * Math.sin(t * 5 + li);
          g.fillStyle = rgba(L[1], L[2]);
          g.beginPath(); g.moveTo(-sw, 0);
          g.quadraticCurveTo(-sw * 1.1, -sh * 0.5, lean, -sh);
          g.quadraticCurveTo(sw * 1.1, -sh * 0.5, sw, 0);
          g.quadraticCurveTo(0, sw * 0.6, -sw, 0); g.closePath(); g.fill();
        });
        break;
      }
      case 'ripples': {
        var per = Math.max(0.3, v('period'));
        g.lineWidth = 1.2;
        for (var rq = 0; rq < 3; rq++) {
          var rp = ((t / per + rq / 3) % 1);
          g.strokeStyle = rgba('#FFFFFF', 0.55 * (1 - rp));
          g.beginPath(); g.ellipse(0, 0, Math.max(0.5, v('rx') * rp), Math.max(0.5, v('ry') * rp), 0, 0, Math.PI * 2); g.stroke();
        }
        break;
      }
      case 'motes':
        break; // drawn over the whole screen by drawMotes
    }
  }

  /** Dust motes over the visible part of the map (an item of kind "motes"; its position is ignored). */
  function drawMotes(g, p, height, top, bottom, t) {
    var d = Model.FX_PARAMS.motes, n = p.count || d.count, col = p.color || d.color;
    for (var k = 0; k < n; k++) {
      var bx = (k * 97) % 412, by = (k * 431) % Math.max(1, Math.round(height));
      var x = bx + 18 * Math.sin(t / 3 + k) + (t * 6 + k * 11) % 40 - 20, y = by - (t * 4 + k * 7) % 60;
      if (y < top || y > bottom) continue;
      var a = 0.35 + 0.35 * Math.sin(t * 1.3 + k * 2.1);
      g.fillStyle = rgba(k % 5 === 0 ? '#BFF5FF' : col, Math.max(0, a));
      g.beginPath(); g.arc(x, y, k % 5 === 0 ? 1.6 : 1.1, 0, Math.PI * 2); g.fill();
    }
  }

  /** Items in drawing order for a layer: by z, then from top to bottom of the map. */
  function ordered(items, layer) {
    return items.filter(function (it) { return (it.layer || 'under') === layer; })
      .sort(function (a, b) { return (a.z || 0) - (b.z || 0) || a.y - b.y; });
  }

  return { loadFonts: loadFonts, loadParchment: loadParchment, drawParchment: drawParchment, drawTitle: drawTitle, drawItem: drawItem,
    drawFx: drawFx, drawMotes: drawMotes, bounds: bounds, ordered: ordered, rgba: rgba, rand: rand, frameAt: frameAt };
})();
