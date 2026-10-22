"""Checks a .praline-world pack against WORLD-FORMAT.md. Used by the editor's tests, and handy for the app side.

    python3 check-pack.py my-world.praline-world

Prints a short summary and exits 0 when the pack is valid, or lists every problem and exits 1.
"""
import io, json, math, re, sys, zipfile

PACK_MAX, ENTRY_MAX, ENTRIES_MAX = 25 * 1024 * 1024, 8 * 1024 * 1024, 400
SAFE = re.compile(r'^(slices|recipes|library)/[A-Za-z0-9._-]{1,80}$')
FX = {'rays', 'halo', 'glow', 'glints', 'smoke', 'sparks', 'sand', 'heat', 'beam', 'motes', 'flame', 'ripples'}


def check(path):
    problems, notes = [], []
    with open(path, 'rb') as f:
        data = f.read()
    if len(data) > PACK_MAX:
        problems.append('pack is %.1f MB (max 25)' % (len(data) / 1048576))
    z = zipfile.ZipFile(io.BytesIO(data))
    infos = [i for i in z.infolist() if not i.is_dir()]
    if len(infos) > ENTRIES_MAX:
        problems.append('%d entries (max %d)' % (len(infos), ENTRIES_MAX))
    names = set()
    for i in infos:
        names.add(i.filename)
        if i.file_size > ENTRY_MAX:
            problems.append('%s is %.1f MB (max 8)' % (i.filename, i.file_size / 1048576))
        if i.filename != 'world.json' and not SAFE.match(i.filename):
            problems.append('unexpected entry name: %s' % i.filename)
    if 'world.json' not in names:
        return ['no world.json'], notes
    w = json.loads(z.read('world.json'))

    def need(cond, msg):
        if not cond:
            problems.append(msg)

    need(w.get('format') == 'praline-world', 'format is not praline-world')
    need(w.get('v') == 1, 'v is not 1')
    need(isinstance(w.get('id'), str) and re.match(r'^[a-z0-9-]{1,32}$', w['id']), 'bad world id')
    need(w.get('width') == 412, 'width is not 412')
    need(w.get('scale') in (2, 3), 'scale is not 2 or 3')
    S = w.get('scale') if w.get('scale') in (2, 3) else 3
    end = w.get('ending')
    if end is not None:
        need(isinstance(end, dict) and isinstance(end.get('title', ''), str) and isinstance(end.get('story', ''), str), 'ending is not {title, story}')
        need(isinstance(end, dict) and len(end.get('story', '')) <= 600, 'ending story is longer than 600 characters')
    H = w.get('height', 0)
    need(1000 <= H <= 6000, 'height out of range')
    need(isinstance(w.get('name'), str) and w['name'].strip(), 'no name')

    # slices cover 0..height with no gap, at 412 * scale px wide
    try:
        from PIL import Image
    except ImportError:
        Image = None
    at = 0
    for s in w.get('slices', []):
        need(abs(s['top'] - at) < 0.01, 'slice %s starts at %s, expected %s' % (s['file'], s['top'], at))
        need(s['file'] in names, 'missing slice %s' % s['file'])
        if Image and s['file'] in names:
            im = Image.open(io.BytesIO(z.read(s['file'])))
            need(im.format in ('WEBP', 'JPEG'), '%s is not WebP or JPEG' % s['file'])
            need(im.size[0] == 412 * S, '%s is %d px wide' % (s['file'], im.size[0]))
            need(abs(im.size[1] - round(s['height'] * S)) <= 1, '%s is %d px tall for %s units' % (s['file'], im.size[1], s['height']))
        at = s['top'] + s['height']
    need(abs(at - H) < 0.01, 'slices end at %s, map height is %s' % (at, H))

    # trail
    pts = w.get('trail', {}).get('points', [])
    need(len(pts) >= 2, 'trail has fewer than 2 points')
    for a, b in zip(pts, pts[1:]):
        if math.hypot(b[0] - a[0], b[1] - a[1]) > 8.01:
            problems.append('trail points more than 8 units apart')
            break
    length = sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(pts, pts[1:]))
    need(abs(length - w['trail'].get('length', 0)) <= max(2, length * 0.01), 'trail length %.1f does not match its points (%.1f)' % (w['trail'].get('length', 0), length))

    lib = w.get('library', {})
    for lid, l in lib.items():
        need(l.get('kind') in ('sprite', 'sheet', 'fx'), 'library %s has kind %s' % (lid, l.get('kind')))
        if l.get('kind') == 'fx':
            need(l.get('fx') in FX, 'library %s: unknown fx %s' % (lid, l.get('fx')))
        else:
            need(l.get('file') in names, 'library %s: missing file %s' % (lid, l.get('file')))
            for k in ('ax', 'ay', 'ppu'):
                need(isinstance(l.get(k), (int, float)), 'library %s: no %s' % (lid, k))
        if l.get('kind') == 'sheet':
            for k in ('frameW', 'frameH', 'cols', 'frames', 'fps'):
                need(isinstance(l.get(k), (int, float)) and l[k] > 0, 'library %s: no %s' % (lid, k))
            need(l.get('loop') in ('loop', 'pingpong', 'once'), 'library %s: bad loop' % lid)

    # spots
    ids, prev = set(), -1
    recipes = 0
    for s in w.get('spots', []):
        sid = s.get('id', '')
        need(re.match(r'^[a-z0-9-]{1,24}$', str(sid)) is not None, 'bad spot id %r' % sid)
        need(sid not in ids, 'spot id %s used twice' % sid)
        ids.add(sid)
        need(s.get('kind') in ('recipe', 'story'), 'spot %s: bad kind' % sid)
        need(0 <= s.get('along', -1) <= 1 and s['along'] >= prev, 'spot %s: along out of order' % sid)
        prev = s.get('along', prev)
        need(isinstance(s.get('xp'), int) and s['xp'] > 0, 'spot %s: xp must be a positive whole number' % sid)
        need(len(s.get('story', '')) <= 600, 'spot %s: story over 600 characters' % sid)
        need(s.get('side') in ('left', 'right'), 'spot %s: bad side' % sid)
        if s.get('icon'):
            need(s['icon'] in lib, 'spot %s: icon %s not in library' % (sid, s['icon']))
        if s.get('kind') == 'recipe':
            recipes += 1
            rp = s.get('recipe', '')
            need(rp == 'recipes/%s.praline' % sid, 'spot %s: recipe path %s' % (sid, rp))
            if rp in names:
                try:
                    rz = zipfile.ZipFile(io.BytesIO(z.read(rp)))
                    head = json.loads(rz.read('praline.json'))
                    need(head.get('format') == 'praline-recipes' and head.get('recipes'), 'spot %s: recipe file has no recipe' % sid)
                    r = head['recipes'][0]
                    need(r.get('title'), 'spot %s: recipe has no title' % sid)
                    need(r.get('variants') and r['variants'][0].get('steps'), 'spot %s: recipe has no steps' % sid)
                    photos = set(n[7:] for n in rz.namelist() if n.startswith('photos/'))
                    for key in ('photo', 'orig'):
                        if r.get(key):
                            need(r[key] in photos and r[key].endswith('.jpg'), 'spot %s: %s picture missing' % (sid, key))
                    if not r.get('photo'):
                        notes.append('spot %s: recipe has no cover picture' % sid)
                except (KeyError, zipfile.BadZipFile, ValueError) as e:
                    problems.append('spot %s: recipe file unreadable (%s)' % (sid, e))
            else:
                problems.append('spot %s: missing %s' % (sid, rp))
        else:
            rw = s.get('reward')
            if rw:
                need(rw.get('kind') == 'decoration' and rw.get('name'), 'spot %s: bad reward' % sid)
                if rw.get('item'):
                    need(rw['item'] in lib, 'spot %s: reward picture not in library' % sid)

    for it in w.get('items', []):
        need(it.get('lib') in lib, 'item %s: library %s missing' % (it.get('id'), it.get('lib')))
        need(it.get('layer') in ('under', 'over'), 'item %s: bad layer' % it.get('id'))
        for k in ('x', 'y', 'scale', 'rot', 'z', 'alpha'):
            need(isinstance(it.get(k), (int, float)), 'item %s: no %s' % (it.get('id'), k))

    notes.insert(0, '%s "%s": %d units tall, %d slices, %d spots (%d recipes), %d library entries, %d items, %.1f MB' % (
        w.get('id'), w.get('name'), H, len(w.get('slices', [])), len(w.get('spots', [])), recipes, len(lib), len(w.get('items', [])), len(data) / 1048576))
    return problems, notes


if __name__ == '__main__':
    probs, notes = check(sys.argv[1])
    for n in notes:
        print(n)
    for p in probs:
        print('PROBLEM:', p)
    sys.exit(1 if probs else 0)
