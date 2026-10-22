// Trail geometry: a chain of cubic Bézier segments, measured along its length.
// Anchors: {x, y, ix, iy, ox, oy, smooth}; (ix, iy) is the handle coming in, (ox, oy) the one going out.
var Geom = (function () {
  function bez(p0, p1, p2, p3, t) {
    var u = 1 - t;
    return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3;
  }

  /** Fine polyline through every segment, with the segment and t each point came from. */
  function flatten(anchors, per) {
    per = per || 48;
    var pts = [];
    if (anchors.length === 0) return pts;
    pts.push({ x: anchors[0].x, y: anchors[0].y, seg: 0, t: 0 });
    for (var i = 0; i + 1 < anchors.length; i++) {
      var a = anchors[i], b = anchors[i + 1];
      for (var k = 1; k <= per; k++) {
        var t = k / per;
        pts.push({ x: bez(a.x, a.ox, b.ix, b.x, t), y: bez(a.y, a.oy, b.iy, b.y, t), seg: i, t: t });
      }
    }
    return pts;
  }

  /** A measured trail: positions by fraction of length, nearest point, resampling. */
  function Trail(anchors) {
    this.anchors = anchors;
    this.pts = flatten(anchors);
    this.len = [0];
    for (var i = 1; i < this.pts.length; i++) {
      var p = this.pts[i], q = this.pts[i - 1];
      this.len.push(this.len[i - 1] + Math.hypot(p.x - q.x, p.y - q.y));
    }
    this.total = this.len[this.len.length - 1] || 0;
  }

  Trail.prototype.ok = function () { return this.pts.length >= 2 && this.total > 1; };

  /** Point at fraction t (0..1) of the length. */
  Trail.prototype.at = function (t) {
    var n = this.pts.length;
    if (n === 0) return { x: 206, y: 400, angle: -90 };
    if (n === 1) return { x: this.pts[0].x, y: this.pts[0].y, angle: -90 };
    var want = Math.max(0, Math.min(1, t)) * this.total;
    var lo = 0, hi = n - 1;
    while (hi - lo > 1) { var mid = (lo + hi) >> 1; if (this.len[mid] < want) lo = mid; else hi = mid; }
    var seg = Math.max(1e-6, this.len[hi] - this.len[lo]);
    var f = Math.max(0, Math.min(1, (want - this.len[lo]) / seg));
    var a = this.pts[lo], b = this.pts[hi];
    return { x: a.x + (b.x - a.x) * f, y: a.y + (b.y - a.y) * f, angle: Math.atan2(b.y - a.y, b.x - a.x) * 180 / Math.PI, seg: a.seg };
  };

  /** Closest point on the trail to (x, y): {t (0..1), d (distance), seg, segT}. */
  Trail.prototype.nearest = function (x, y) {
    var best = { t: 0, d: Infinity, seg: 0, segT: 0 };
    for (var i = 1; i < this.pts.length; i++) {
      var a = this.pts[i - 1], b = this.pts[i];
      var dx = b.x - a.x, dy = b.y - a.y, l2 = dx * dx + dy * dy;
      var f = l2 > 0 ? Math.max(0, Math.min(1, ((x - a.x) * dx + (y - a.y) * dy) / l2)) : 0;
      var px = a.x + dx * f, py = a.y + dy * f, d = Math.hypot(x - px, y - py);
      if (d < best.d) {
        var segT = b.seg === a.seg ? a.t + (b.t - a.t) * f : b.t * f;
        best = { t: this.total > 0 ? (this.len[i - 1] + (this.len[i] - this.len[i - 1]) * f) / this.total : 0, d: d, seg: b.seg, segT: segT };
      }
    }
    return best;
  };

  /** The trail as points no more than [step] units apart, start to end (what the app reads). */
  Trail.prototype.resample = function (step) {
    step = step || 4;
    var out = [];
    if (!this.ok()) return out;
    var n = Math.max(2, Math.ceil(this.total / step) + 1);
    for (var i = 0; i < n; i++) { var p = this.at(i / (n - 1)); out.push([round1(p.x), round1(p.y)]); }
    return out;
  };

  function round1(v) { return Math.round(v * 10) / 10; }

  /** Splits segment [seg] at [t] with a new anchor (de Casteljau), keeping the curve's shape. */
  function split(anchors, seg, t) {
    var a = anchors[seg], b = anchors[seg + 1];
    function lerp(p, q) { return { x: p.x + (q.x - p.x) * t, y: p.y + (q.y - p.y) * t }; }
    var p0 = { x: a.x, y: a.y }, p1 = { x: a.ox, y: a.oy }, p2 = { x: b.ix, y: b.iy }, p3 = { x: b.x, y: b.y };
    var q0 = lerp(p0, p1), q1 = lerp(p1, p2), q2 = lerp(p2, p3);
    var r0 = lerp(q0, q1), r1 = lerp(q1, q2), m = lerp(r0, r1);
    a.ox = q0.x; a.oy = q0.y; b.ix = q2.x; b.iy = q2.y;
    var mid = { x: m.x, y: m.y, ix: r0.x, iy: r0.y, ox: r1.x, oy: r1.y, smooth: true };
    anchors.splice(seg + 1, 0, mid);
    return seg + 1;
  }

  /** Smooth Bézier anchors through a polyline (Catmull-Rom), keeping one anchor every [every] points. */
  function fromPoints(points, every) {
    every = every || 12;
    var keep = [];
    for (var i = 0; i < points.length; i += every) keep.push(points[i]);
    if (keep[keep.length - 1] !== points[points.length - 1]) keep.push(points[points.length - 1]);
    var out = [];
    for (var k = 0; k < keep.length; k++) {
      var p = keep[k], prev = keep[Math.max(0, k - 1)], next = keep[Math.min(keep.length - 1, k + 1)];
      var tx = (next[0] - prev[0]) / 6, ty = (next[1] - prev[1]) / 6;
      out.push({ x: p[0], y: p[1], ix: p[0] - tx, iy: p[1] - ty, ox: p[0] + tx, oy: p[1] + ty, smooth: true });
    }
    return out;
  }

  /** A new anchor at (x, y) with handles pointing along the direction from [from]. */
  function anchorAt(x, y, from) {
    var dx = from ? (x - from.x) / 3 : 0, dy = from ? (y - from.y) / 3 : -40;
    return { x: x, y: y, ix: x - dx, iy: y - dy, ox: x + dx, oy: y + dy, smooth: true };
  }

  return { Trail: Trail, split: split, fromPoints: fromPoints, anchorAt: anchorAt };
})();
