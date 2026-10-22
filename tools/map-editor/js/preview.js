// The phone preview: the world as a player sees it, with the lit trail behind the party, dusk ahead,
// spots in their done / next / locked states, live effects, and the story box when a spot is tapped.
// It follows MissionMap.kt and Missions.kt closely but is not pixel-exact: the app draws with Android code.
var Preview = (function () {
  var S = Model.state, el = UI.el;
  var back, cv, g, flat, flatK = 2, raf = 0, t0 = 0;
  var top = 0, k = 1, viewH = 860, xp = 0, autoCook = true, walking = false;
  var covers = new Map();

  async function open() {
    if (!Model.trail().ok()) { UI.toast('Draw the trail first.', true); return; }
    await flatten();
    var total = Model.thresholds().slice(-1)[0] || 0;
    xp = Math.min(xp, total + 50);
    k = Math.max(0.5, Math.min(1.1, (window.innerHeight - 70) / viewH));
    cv = el('canvas.phone-screen');
    var dpr = window.devicePixelRatio || 1;
    cv.width = Math.round(412 * k * dpr); cv.height = Math.round(viewH * k * dpr);
    cv.style.width = Math.round(412 * k) + 'px'; cv.style.height = Math.round(viewH * k) + 'px';
    g = cv.getContext('2d');
    var slider = el('input', { type: 'range', min: 0, max: total + 50, value: xp, step: 1 });
    var fameText = el('strong');
    var where = el('p.hint');
    function upd() { fameText.textContent = xp + ' fame'; where.textContent = describe(progress()); }
    slider.addEventListener('input', function () { xp = Number(slider.value); walking = false; upd(); });
    var cook = el('input', { type: 'checkbox', checked: autoCook });
    cook.addEventListener('change', function () { autoCook = cook.checked; upd(); });
    var walk = el('button', { onclick: function () { walking = !walking; walk.textContent = walking ? 'Stop' : 'Walk the trail'; if (walking && xp >= total) xp = 0; } }, 'Walk the trail');
    var side = el('div.preview-side', [
      el('h2', 'Phone preview'),
      el('p.hint', 'Scroll the map with the mouse wheel or by dragging. Tap a spot to read its story.'),
      UI.row('Party fame', slider), fameText, where,
      el('label.check', [cook, ' The chefs cook each recipe as soon as they reach it']),
      el('div.btns', [walk, el('button', { onclick: function () { scrollToParty(); } }, 'Find the party')]),
      el('p.hint', 'Close with Esc. Fame between spots comes from each spot\'s "Fame to reach it".'),
      el('button.primary', { onclick: close }, 'Back to editing')
    ]);
    back = el('div.preview-back', [el('div.phone', [cv]), side]);
    document.body.appendChild(back);
    document.addEventListener('keydown', key, true);
    cv.addEventListener('wheel', function (e) { e.preventDefault(); top += e.deltaY / k * 0.8; clamp(); }, { passive: false });
    var dragY = null, moved = 0;
    cv.addEventListener('pointerdown', function (e) { dragY = e.clientY; moved = 0; cv.setPointerCapture(e.pointerId); });
    cv.addEventListener('pointermove', function (e) { if (dragY === null) return; var d = e.clientY - dragY; moved += Math.abs(d); top -= d / k; dragY = e.clientY; clamp(); });
    cv.addEventListener('pointerup', function (e) {
      if (moved < 6) tap(e);
      dragY = null;
    });
    upd();
    scrollToParty();
    t0 = performance.now();
    var last = performance.now();
    function loop(now) {
      if (!back) return;
      if (walking) {
        xp = Math.min(total + 50, xp + (now - last) / 1000 * Math.max(60, total / 25));
        xp = Math.round(xp); slider.value = xp; upd();
        var pp = Model.trail().at(progress().along);
        if (pp.y - top < viewH * 0.3 || pp.y - top > viewH * 0.75) top += ((pp.y - viewH * 0.55) - top) * 0.08;
        clamp();
        if (xp >= total + 50) { walking = false; walk.textContent = 'Walk the trail'; }
      }
      last = now;
      paint((now - t0) / 1000);
      raf = requestAnimationFrame(loop);
    }
    raf = requestAnimationFrame(loop);
  }

  function close() {
    cancelAnimationFrame(raf);
    document.removeEventListener('keydown', key, true);
    if (back) back.remove();
    back = null; walking = false;
  }
  function key(e) { if (e.key === 'Escape' && !document.querySelector('.modal-back')) { e.stopPropagation(); close(); } }

  function clamp() { top = Math.max(0, Math.min(S.p.world.height - viewH, top)); }
  function scrollToParty() { top = Model.trail().at(progress().along).y - viewH * 0.55; clamp(); }

  /** The flattened scenery (parchment, art, baked pictures), like the exported slices. */
  async function flatten() {
    var p = S.p, H = p.world.height;
    var c = new OffscreenCanvas(Model.W * flatK, Math.round(H * flatK)), fg = c.getContext('2d');
    fg.setTransform(flatK, 0, 0, flatK, 0, 0);
    fg.fillStyle = '#EEDDBE'; fg.fillRect(0, 0, Model.W, H);
    Draw.drawParchment(fg, p.parchment.file ? S.images.get(p.parchment.file) : Panels.defaultParchment(), p.parchment.mode, H, 0, H);
    var art = p.art ? S.images.get(p.art.file) : null;
    if (art) fg.drawImage(art, 0, 0, Model.W, art.height * Model.W / art.width);
    ['under', 'over'].forEach(function (layer) {
      Draw.ordered(p.items.filter(function (it) { return it.bake; }), layer).forEach(function (it) {
        var l = Model.lib(it.lib); if (l && l.kind === 'sprite') Draw.drawItem(fg, it, l, S.images.get(l.file), 0);
      });
    });
    flat = c;
  }

  // ---- party progress, as Missions.progress -------------------------------------------------
  function progress() {
    var spots = S.p.spots, th = Model.thresholds(), done = 0, waiting = false;
    while (done < spots.length && xp >= th[done]) {
      if (spots[done].kind === 'recipe' && !autoCook) { waiting = true; break; }
      done++;
    }
    var complete = done >= spots.length;
    var along;
    if (complete) along = 1;
    else {
      var prevTh = done === 0 ? 0 : th[done - 1], a0 = done === 0 ? 0 : spots[done - 1].along, a1 = spots[done].along;
      var f = waiting ? 1 : Math.max(0, Math.min(1, (xp - prevTh) / Math.max(1, th[done] - prevTh)));
      along = a0 + (a1 - a0) * f;
    }
    return { done: done, waiting: waiting, complete: complete, along: along, next: complete ? null : spots[done], toNext: complete || waiting ? 0 : th[done] - xp };
  }

  function describe(pr) {
    if (pr.complete) return 'The party has reached the end of the world.';
    if (pr.waiting) return 'The party waits at "' + pr.next.name + '" until a chef cooks its recipe.';
    return pr.toNext + ' fame to "' + pr.next.name + '".';
  }

  // ---- drawing -------------------------------------------------------------------------------
  function paint(time) {
    var dpr = window.devicePixelRatio || 1, p = S.p, tr = Model.trail(), pr = progress();
    g.setTransform(dpr * k, 0, 0, dpr * k, 0, -top * dpr * k);
    g.drawImage(flat, 0, top * flatK, Model.W * flatK, viewH * flatK, 0, top, Model.W, viewH);
    var y0 = top, y1 = top + viewH;
    items('under', time, y0, y1);
    var party = tr.at(pr.along);
    trail(tr, pr.along);
    fog(party, time);
    spots(pr, time);
    lock(pr);
    items('over', time, y0, y1);
    p.items.forEach(function (it) { var l = Model.lib(it.lib); if (l && l.kind === 'fx' && l.fx === 'motes') Draw.drawMotes(g, it.params || {}, p.world.height, y0, y1, time); });
    var stand = pr.waiting || pr.complete ? tr.at(Math.max(0, pr.along - 0.022)) : party;
    partyTokens(stand, time);
    Draw.drawTitle(g, p.world.title);
    // header bar, like the mission screen's
    g.setTransform(dpr * k, 0, 0, dpr * k, 0, 0);
    g.fillStyle = 'rgba(255,248,236,0.92)'; g.fillRect(0, 0, 412, 46);
    g.strokeStyle = '#2B1C10'; g.lineWidth = 1.2; g.beginPath(); g.moveTo(0, 46); g.lineTo(412, 46); g.stroke();
    g.fillStyle = '#2B1C10'; g.font = '22px PralineTitle, serif'; g.textAlign = 'left'; g.textBaseline = 'middle';
    g.fillText(p.world.name, 14, 24);
    g.font = '600 10px PralineBold, serif'; g.textAlign = 'right';
    g.fillText(pr.done + ' / ' + p.spots.length + ' spots · ' + xp + ' fame', 398, 24);
  }

  function items(layer, time, y0, y1) {
    Draw.ordered(S.p.items.filter(function (it) { return !it.bake; }), layer).forEach(function (it) {
      var l = Model.lib(it.lib);
      if (!l || (l.kind === 'fx' && l.fx === 'motes')) return;
      var b = Draw.bounds(it, l), reach = l.kind === 'fx' && (l.fx === 'rays' || l.fx === 'beam');
      if (!reach && (it.y + b.y + b.h < y0 - 40 || it.y + b.y > y1 + 40)) return;
      Draw.drawItem(g, it, l, l.file ? S.images.get(l.file) : null, time);
    });
  }

  function path(tr, from, to) {
    var n = 120;
    g.beginPath();
    for (var i = 0; i <= n; i++) { var q = tr.at(from + (to - from) * i / n); if (i) g.lineTo(q.x, q.y); else g.moveTo(q.x, q.y); }
  }

  function trail(tr, along) {
    g.lineCap = 'round'; g.lineJoin = 'round';
    if (along < 1) {
      path(tr, along, 1);
      g.strokeStyle = 'rgba(40,30,60,0.55)'; g.lineWidth = 5; g.setLineDash([2, 7]); g.stroke(); g.setLineDash([]);
    }
    if (along > 0) {
      path(tr, 0, along);
      g.strokeStyle = 'rgba(255,200,90,0.35)'; g.lineWidth = 16; g.stroke();
      g.strokeStyle = '#7A4A1E'; g.lineWidth = 7; g.stroke();
      g.strokeStyle = '#FFD25A'; g.lineWidth = 4; g.stroke();
    }
  }

  function fog(party, time) {
    var y = party.y - 120;
    var gr = g.createLinearGradient(0, y, 0, y - 520);
    gr.addColorStop(0, 'rgba(36,22,58,0)'); gr.addColorStop(0.5, 'rgba(36,22,58,0.32)'); gr.addColorStop(1, 'rgba(24,14,40,0.5)');
    g.fillStyle = gr; g.fillRect(0, Math.max(top, 0) - 10, 412, Math.max(0, y - top + 10));
    if (y - 520 > top) { g.fillStyle = 'rgba(24,14,40,0.5)'; g.fillRect(0, top, 412, y - 520 - top); }
    void time;
  }

  function cover(url) {
    if (!url) return null;
    var im = covers.get(url);
    if (!im) { im = new Image(); im.src = url; covers.set(url, im); }
    return im.complete && im.naturalWidth ? im : null;
  }

  function spots(pr, time) {
    var tr = Model.trail();
    S.p.spots.forEach(function (s, i) {
      var at = tr.at(s.along), x = at.x, y = at.y;
      if (y < top - 140 || y > top + viewH + 60) return;
      var state = i < pr.done ? 0 : i === pr.done ? 1 : 2;
      var accent = state === 0 ? '#FFD25A' : state === 1 ? '#E0479E' : '#8C7AA8';
      if (state === 1) {
        var rr = 60 * (0.85 + 0.27 * (0.5 + 0.5 * Math.sin(time / 2.6 * Math.PI * 2)));
        var gr = g.createRadialGradient(x, y, 0, x, y, rr);
        gr.addColorStop(0, 'rgba(255,150,220,0.75)'); gr.addColorStop(0.35, 'rgba(224,71,158,0.35)'); gr.addColorStop(0.7, 'rgba(224,71,158,0)');
        g.fillStyle = gr; g.beginPath(); g.arc(x, y, rr, 0, Math.PI * 2); g.fill();
      }
      // dais
      g.fillStyle = 'rgba(43,28,16,0.35)'; g.beginPath(); g.ellipse(x, y + 5, 26, 10, 0, 0, Math.PI * 2); g.fill();
      g.fillStyle = state === 2 ? '#B9AC9A' : '#E8D3A8'; g.strokeStyle = '#2B1C10'; g.lineWidth = 1.2;
      g.beginPath(); g.ellipse(x, y, 24, 9, 0, 0, Math.PI * 2); g.fill(); g.stroke();
      g.strokeStyle = accent; g.lineWidth = 2; g.beginPath(); g.ellipse(x, y, 19, 6.5, 0, 0, Math.PI * 2); g.stroke();
      // pennant
      var px = x - 14, ptop = y - 64;
      g.strokeStyle = '#2B1C10'; g.lineWidth = 3.2; g.beginPath(); g.moveTo(px, ptop); g.lineTo(px, y); g.stroke();
      g.strokeStyle = '#8A5432'; g.lineWidth = 1.8; g.stroke();
      var wave = 2 * Math.sin(time * 2 + i);
      g.beginPath(); g.moveTo(px, ptop); g.lineTo(px + 24, ptop + 4 + wave); g.lineTo(px + 17, ptop + 9); g.lineTo(px + 24, ptop + 14 + wave); g.lineTo(px, ptop + 15); g.closePath();
      g.fillStyle = accent; g.fill(); g.strokeStyle = '#2B1C10'; g.lineWidth = 1.2; g.stroke();
      // medallion
      var mx = x, my = y - 34, r = 17;
      g.fillStyle = state === 2 ? '#EFE6D8' : '#FFF8EC'; g.beginPath(); g.arc(mx, my, r, 0, Math.PI * 2); g.fill();
      var icon = s.icon && Model.lib(s.icon), info = S.recipes.get(s.id), img = icon && icon.file ? S.images.get(icon.file) : info ? cover(info.cover) : null;
      if (img) {
        g.save(); g.globalAlpha = state === 2 ? 0.55 : 1; g.beginPath(); g.arc(mx, my, r - 2, 0, Math.PI * 2); g.clip();
        var w = img.width || img.naturalWidth, h = img.height || img.naturalHeight, m = Math.min(w, h);
        g.drawImage(img, (w - m) / 2, (h - m) / 2, m, m, mx - r + 2, my - r + 2, 2 * r - 4, 2 * r - 4); g.restore();
      } else if (s.kind === 'story') {
        g.fillStyle = accent; g.font = '16px serif'; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText('✦', mx, my + 1);
      }
      g.strokeStyle = accent; g.lineWidth = 3; g.beginPath(); g.arc(mx, my, r, 0, Math.PI * 2); g.stroke();
      g.strokeStyle = '#2B1C10'; g.lineWidth = 1.3; g.beginPath(); g.arc(mx, my, r + 1.6, 0, Math.PI * 2); g.stroke();
      if (state === 0) { g.fillStyle = '#5ED3B2'; g.beginPath(); g.arc(mx + 13, my - 13, 6, 0, Math.PI * 2); g.fill(); g.strokeStyle = '#fff'; g.lineWidth = 1.8; g.beginPath(); g.moveTo(mx + 10, my - 13); g.lineTo(mx + 12.5, my - 10.5); g.lineTo(mx + 16.5, my - 15.5); g.stroke(); }
      // label
      var name = s.name || '?';
      g.font = '600 10px PralineBold, serif'; var tw = g.measureText(name).width + 14, lh = 17;
      var lx = s.side === 'left' ? x - 24 - tw : x + 24, ly = y - 6.5;
      g.fillStyle = state === 0 ? '#FFD25A' : state === 1 ? '#E0479E' : '#A898C0'; rr2(lx + 2.5, ly + 2.5, tw, lh, 7); g.fill();
      g.fillStyle = state === 0 ? '#FFF3D0' : state === 1 ? '#FFF0F7' : '#EFE6D8'; rr2(lx, ly, tw, lh, 7); g.fill();
      g.strokeStyle = '#2B1C10'; g.lineWidth = 1.5; g.stroke();
      g.fillStyle = state === 2 ? '#6A5A70' : '#2B1C10'; g.textAlign = 'center'; g.textBaseline = 'alphabetic'; g.fillText(name, lx + tw / 2, ly + 12.2);
      if (state === 1 && pr.waiting && s.kind === 'recipe') {
        var cy = my - 28; rr2(mx - 27, cy - 7.5, 54, 15, 7); g.fillStyle = '#E0479E'; g.fill(); g.strokeStyle = '#2B1C10'; g.lineWidth = 1.2; g.stroke();
        g.fillStyle = '#fff'; g.font = '900 7.5px PralineBold, sans-serif'; g.fillText('MUST COOK', mx, cy + 2.7);
      }
    });
  }
  function rr2(x, y, w, h, r) { g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath(); }

  function lock(pr) {
    if (pr.complete || !pr.waiting) return;
    var t = Math.min(1, pr.next.along + 0.045);
    if (t >= 0.999) return;
    var q = Model.trail().at(t);
    g.fillStyle = '#6A5A70'; g.strokeStyle = '#2B1C10'; g.lineWidth = 1.4;
    rr2(q.x - 9, q.y - 6, 18, 14, 3); g.fill(); g.stroke();
    g.beginPath(); g.arc(q.x, q.y - 6, 6, Math.PI, 0); g.lineWidth = 3; g.strokeStyle = '#6A5A70'; g.stroke();
    g.strokeStyle = '#8C7AA8'; g.lineWidth = 2.2; g.setLineDash([3, 3]);
    g.beginPath(); g.moveTo(q.x - 40, q.y + 2); g.lineTo(q.x - 10, q.y + 2); g.moveTo(q.x + 10, q.y + 2); g.lineTo(q.x + 40, q.y + 2); g.stroke(); g.setLineDash([]);
  }

  function partyTokens(p, time) {
    var ph = (time / 1.8) % 1;
    g.strokeStyle = 'rgba(255,210,90,' + (0.8 * (1 - ph)) + ')'; g.lineWidth = 2;
    g.beginPath(); g.ellipse(p.x, p.y, 22 + 20 * ph, 8 + 7 * ph, 0, 0, Math.PI * 2); g.stroke();
    [[-16, 0, '#E0479E', 0], [16, 6, '#3FB8A0', 1.4]].forEach(function (c) {
      var x = p.x + c[0], y = p.y + c[1] + 2.5 * Math.sin(time / 2.2 * Math.PI * 2 + c[3]);
      g.fillStyle = 'rgba(43,28,16,0.35)'; g.beginPath(); g.ellipse(x, y + 2, 10, 4, 0, 0, Math.PI * 2); g.fill();
      g.fillStyle = c[2]; g.strokeStyle = '#2B1C10'; g.lineWidth = 1.3;
      g.beginPath(); g.moveTo(x - 8, y); g.quadraticCurveTo(x - 9, y - 22, x, y - 24); g.quadraticCurveTo(x + 9, y - 22, x + 8, y); g.closePath(); g.fill(); g.stroke();
      g.fillStyle = '#F6D7B8'; g.beginPath(); g.arc(x, y - 29, 7, 0, Math.PI * 2); g.fill(); g.stroke();
      g.fillStyle = '#FFFFFF'; g.beginPath(); g.ellipse(x, y - 37, 8, 4.5, 0, 0, Math.PI * 2); g.fill(); g.stroke();
    });
  }

  // ---- story box -------------------------------------------------------------------------------
  function tap(e) {
    var r = cv.getBoundingClientRect(), x = (e.clientX - r.left) / k, y = (e.clientY - r.top) / k + top;
    var tr = Model.trail(), best = null, bd = 40;
    S.p.spots.forEach(function (s) {
      var at = tr.at(s.along), d = Math.min(Math.hypot(x - at.x, y - at.y), Math.hypot(x - at.x, y - (at.y - 34)));
      if (d < bd) { bd = d; best = s; }
    });
    if (best) storyBox(best);
  }

  function storyBox(s) {
    var pr = progress(), i = S.p.spots.indexOf(s), state = i < pr.done ? 'done' : i === pr.done ? (pr.waiting || s.kind === 'story' ? 'here' : 'ahead') : 'ahead';
    var info = S.recipes.get(s.id);
    var kids = [el('div.story-kicker', s.kind === 'recipe' ? 'Recipe spot · ' + (state === 'done' ? 'cooked' : state === 'here' ? 'the party is here' : 'not reached yet')
      : 'Story stop · ' + (state === 'done' ? 'passed' : state === 'here' ? 'the party is here' : 'not reached yet')),
      el('h3', s.name)];
    if (state === 'ahead') kids.push(el('p.story-text.locked', 'The party has not reached this place yet. (In the editor preview you can still read it below.)'));
    (s.story || '(No story yet.)').split('\n').forEach(function (para) { if (para.trim()) kids.push(el('p.story-text', para)); });
    if (s.kind === 'recipe') {
      kids.push(el('div.story-recipe', [
        info && info.cover ? el('img', { src: info.cover, alt: '' }) : el('div.nocover', '?'),
        el('div', [el('div.story-kicker', state === 'done' ? 'In your grimoire' : 'Joins your grimoire'), el('strong', info && info.title ? info.title : 'No recipe yet')])
      ]));
    } else if (s.reward && s.reward.name) {
      var l = s.reward.item && Model.lib(s.reward.item), im = l && l.file ? S.images.get(l.file) : null;
      var c = el('canvas', { width: 56, height: 56 });
      if (im) { var kk = Math.min(56 / im.width, 56 / im.height); c.getContext('2d').drawImage(im, 28 - im.width * kk / 2, 28 - im.height * kk / 2, im.width * kk, im.height * kk); }
      kids.push(el('div.story-recipe', [c, el('div', [el('div.story-kicker', 'You found a profile decoration'), el('strong', s.reward.name)])]));
    }
    var box = el('div.story-box', kids.concat([el('button.primary', { onclick: function () { box.remove(); } }, s.kind === 'recipe' && state !== 'done' ? 'To the kitchen!' : 'Continue')]));
    box.addEventListener('click', function (ev) { if (ev.target === box) box.remove(); });
    cv.parentNode.appendChild(box);
  }

  /** Where the phone shows the map, for tests: {top (map units), k (px per unit)}. */
  function view() { return { top: top, k: k }; }

  return { open: open, close: close, view: view };
})();
