"""Turns today's built-in world (The Sunforge Sands: app/assets/missions/calice + the Templates in
Missions.kt) into a .praline-world pack, so the map scene can be tested on a real world and the editor
can open it. Run from anywhere:

    python3 tools/map-editor/convert-sunforge.py [out.praline-world]

The 2x slices are upscaled to 3x (1236 px), so they are a little soft; the painterly rebrand will replace them.
Spot ids are 1..10, so the recipe ids stay mission-sunforge-1..10 and mission progress carries over.
The dais, pennant, lock and chef tokens are drawn by the scene itself, so they are not in the pack.
"""
import io, json, math, os, re, sys, zipfile
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
ASSETS = os.path.join(ROOT, 'app', 'assets', 'missions', 'calice')
MISSIONS = os.path.join(ROOT, 'src', 'main', 'kotlin', 'com', 'yaya', 'praline', 'Missions.kt')
GAPS = [80, 120, 140, 160, 180, 200, 220, 240, 260, 300]

STORIES = [
    "Heat shimmers over the first dunes. A baker's oven, half buried, still holds warm stones, and a scrap of paper flutters under one of them.",
    "At the edge of a caravan camp, an old woman pours tea from high above the glasses and laughs at your sandy boots.",
    "The oasis is greener than any dream. Palm shadows, date clusters, and a clay pot simmering all by itself on the embers.",
    "You can spot strange silhouettes in the distance: horses, but with bumps on their backs and even fur. Malik welcomes you at the Camel Farm.",
    "A saffron merchant's stall stands alone at a crossroads, its awning snapping in the wind. The merchant is nowhere to be seen.",
    "The caravan road narrows between red cliffs. Spice sacks lie forgotten by the well, still fragrant.",
    "In the shadow of a crumbling pyramid, bees hum in a hive the size of a door.",
    "The sand is so hot here that someone left a skillet on a flat rock, and it is still sizzling.",
    "Glassblowers' furnaces glow along the ridge, and their apprentices argue over lunch.",
    "The Calice: a giant glass flower beams sunlight onto the great forge. The forge-keepers are hungry.",
]

# ---- ingredient lines, as Qty.parse in Model.kt -------------------------------------------------
UNITS = set('g kg mg ml cl dl l tsp tbsp cup cups oz lb lbs pinch pinches can cans clove cloves slice slices sheet sheets bunch handful '
            'cas cac càs càc c.à.s c.à.c pincée pincées boîte boîtes gousse gousses tranche tranches sachet sachets pot pots stick sticks knob drop drops'.split())


def parse_line(line):
    m = re.match(r'^(\d+(?:[.,]\d+)?)\s+(.*)$', line.strip())
    if not m:
        return {'u': '', 'n': line.strip()}
    q, rest = float(m.group(1).replace(',', '.')), m.group(2)
    word = rest.split(None, 1)
    unit = ''
    if len(word) > 1 and word[0].lower().rstrip('.') in UNITS:
        unit, rest = word[0].rstrip('.'), word[1].strip()
    return {'q': q, 'u': unit, 'n': rest}


def templates():
    src = open(MISSIONS, encoding='utf-8').read()
    block = src[src.index('val SUNFORGE = arrayOf('):src.index('fun build(')]
    out = []
    for m in re.finditer(r'T\("([^"]*)", "([^"]*)", "([^"]*)", "([^"]*)", (\d+), (\d+), (\d+), (true|false),\s*arrayOf\((.*?)\),\s*arrayOf\((.*?)\)\)', block, re.S):
        strs = lambda s: re.findall(r'"((?:[^"\\]|\\.)*)"', s)
        out.append({'key': m.group(1), 'title': m.group(2), 'cls': m.group(3), 'tier': m.group(4), 'effort': int(m.group(5)),
                    'time': int(m.group(6)), 'serv': int(m.group(7)), 'dessert': m.group(8) == 'true',
                    'ing': strs(m.group(9)), 'steps': [s.replace("\\'", "'") for s in strs(m.group(10))]})
    if len(out) != 10:
        sys.exit('expected 10 templates in Missions.kt, found %d' % len(out))
    return out


def praline(t, i):
    steps = []
    for s in t['steps']:
        at = s.rfind('@')
        if at > 0:
            steps.append({'t': s[:at], 's': int(s[at + 1:]), 'l': '', 'h': ''})
        else:
            steps.append({'t': s, 's': 0, 'l': '', 'h': ''})
    r = {'id': 'mission-sunforge-%d' % (i + 1), 'title': t['title'], 'dessert': t['dessert'], 'cls': t['cls'], 'tier': t['tier'],
         'effort': t['effort'], 'time': t['time'], 'serv': t['serv'], 'photo': '', 'orig': '', 'filter': '', 'str': 0.8,
         'cooked': 0, 'created': 0, 'updated': 0, 'equip': [], 'world': 'sunforge',
         'variants': [{'name': 'Classic', 'ing': [parse_line(x) for x in t['ing']], 'steps': steps}]}
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_STORED) as z:
        z.writestr('praline.json', json.dumps({'format': 'praline-recipes', 'v': 1, 'from': 'The Sunforge Sands', 'recipes': [r]}))
    return buf.getvalue()


def webp(im, **kw):
    b = io.BytesIO()
    im.save(b, 'WEBP', **kw)
    return b.getvalue()


def resample(points, step=4.0):
    lens = [0.0]
    for a, b in zip(points, points[1:]):
        lens.append(lens[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
    total = lens[-1]
    n = max(2, int(math.ceil(total / step)) + 1)
    out, j = [], 1
    for i in range(n):
        want = total * i / (n - 1)
        while j < len(points) - 1 and lens[j] < want:
            j += 1
        seg = max(1e-9, lens[j] - lens[j - 1])
        f = max(0.0, min(1.0, (want - lens[j - 1]) / seg))
        a, b = points[j - 1], points[j]
        out.append([round(a[0] + (b[0] - a[0]) * f, 1), round(a[1] + (b[1] - a[1]) * f, 1)])
    return out, total


def main(out_path):
    m = json.load(open(os.path.join(ASSETS, 'map.json')))
    W, H = 412, int(m['height'])
    entries = {}
    slices = []
    for i, s in enumerate(m['slices']):
        im = Image.open(os.path.join(ASSETS, s['file'])).convert('RGB')
        h = int(round(s['height'] * 3))
        im = im.resize((1236, h), Image.LANCZOS)
        name = 'slices/s%02d.webp' % i
        entries[name] = webp(im, quality=85)
        slices.append({'file': name, 'top': s['top'], 'height': s['height']})

    points, length = resample(m['trail'], 4.0)
    library, items = {}, []

    def sprite(lid, fname, ax=None, ay=None):
        im = Image.open(os.path.join(ASSETS, fname)).convert('RGBA')
        entries['library/%s.webp' % lid] = webp(im, lossless=True)
        library[lid] = {'kind': 'sprite', 'file': 'library/%s.webp' % lid, 'w': im.size[0], 'h': im.size[1],
                        'ax': im.size[0] // 2 if ax is None else ax, 'ay': im.size[1] // 2 if ay is None else ay, 'ppu': 2}

    def fx(kind):
        library['fx-' + kind] = {'kind': 'fx', 'fx': kind}
        return 'fx-' + kind

    def item(lib, x, y, layer='under', params=None, **extra):
        it = {'id': 'i%d' % (len(items) + 1), 'lib': lib, 'x': x, 'y': y, 'scale': 1, 'rot': 0, 'flip': False, 'layer': layer, 'z': 0, 'alpha': 1}
        if params:
            it['params'] = params
        it.update(extra)
        items.append(it)

    f = m['fx']
    item(fx('rays'), f['rays']['x'], f['rays']['y'], 'over', {'from': f['rays']['angle_from'], 'to': f['rays']['angle_to'], 'length': f['rays']['length'], 'count': 7, 'color': '#FFE7A6', 'alpha': 0.35})
    item(fx('halo'), f['sun']['x'], f['sun']['y'], 'over', {'r': f['sun']['r'], 'halo': f['sun']['halo_r'], 'color': '#FFF1C2'})
    item(fx('beam'), f['flower_focus']['x'], f['flower_focus']['y'], 'over', {'tx': f['beam_end']['x'], 'ty': f['beam_end']['y'], 'width': 14, 'color': '#FFE7A6'})
    item(fx('sparks'), f['forge_sparks']['x'], f['forge_sparks']['y'], 'over', {'color': '#FFC266', 'rate': 1.2})
    item(fx('glow'), f['kiln_glow']['x'], f['kiln_glow']['y'], 'over', {'r': 26, 'color': '#FF8A3D', 'pulse': 2.6})
    item('fx-glow', f['campfire_flame']['x'], f['campfire_flame']['y'] - 10, 'over', {'r': 18, 'color': '#FFB347', 'pulse': 1.4})
    item(fx('flame'), f['campfire_flame']['x'], f['campfire_flame']['y'], 'over', {'w': 12, 'h': 28, 'color': '#FF8A2A', 'core': '#FFD25A'})
    for s in f['smoke']:
        item(fx('smoke'), s['x'], s['y'], 'under', {'color': '#B8A898', 'rate': 1})
    for wtr in f['water']:
        item(fx('glints'), wtr['x'], wtr['y'], 'over', {'rx': wtr['rx'] * 0.7, 'ry': wtr['ry'] * 0.6, 'count': 5, 'color': '#FFFFFF'})
    for lt in f['lanterns']:
        item('fx-glow', lt['x'], lt['y'], 'over', {'r': 9, 'color': '#FFC266', 'pulse': 2.2})
    for ht in f['heat']:
        item(fx('heat'), ht['x'], ht['y'], 'over', {'w': 40, 'h': 60})
    for sp in f['sand_plumes']:
        item(fx('sand'), sp['x'], sp['y'], 'under', {'color': '#E8C890', 'rate': 1})
    item(fx('motes'), 206, 1500, 'over', {'count': 70, 'color': '#FFE9B8'})
    cap = f['capstone']
    sprite('capstone', 'capstone.png', cap['png_anchor_px'][0], cap['png_anchor_px'][1])
    item('capstone', cap['x'], cap['y'], 'under', bob={'amp': 7, 'period': 5})

    spots = []
    for i, (s, t) in enumerate(zip(m['spots'], templates())):
        sid = str(i + 1)
        icon = 'icon-' + t['key']
        sprite(icon, s['icon'])
        entries['recipes/%s.praline' % sid] = praline(t, i)
        spots.append({'id': sid, 'kind': 'recipe', 'name': 'The Calice' if i == 9 else t['title'], 'along': s['t'], 'x': s['x'], 'y': s['y'],
                      'side': s['side'], 'xp': GAPS[i], 'story': STORIES[i], 'recipe': 'recipes/%s.praline' % sid, 'icon': icon})

    world = {'format': 'praline-world', 'v': 1, 'id': 'sunforge', 'kind': 'mission', 'name': 'The Sunforge Sands', 'intro': '', 'ending': {'title': 'The Calice is lit', 'story': ''},
             'width': W, 'height': H, 'scale': 3,
             'title': {'text': '', 'x': 206, 'y': 150, 'size': 40, 'color': '#3B2414', 'rotate': 0, 'font': 'title'},
             'slices': slices, 'trail': {'points': points, 'length': round(length, 1)},
             'spots': spots, 'library': library, 'items': items}
    with zipfile.ZipFile(out_path, 'w', zipfile.ZIP_STORED) as z:
        z.writestr('world.json', json.dumps(world))
        for name in sorted(entries):
            z.writestr(name, entries[name])
    print('wrote %s (%.1f MB)' % (out_path, os.path.getsize(out_path) / 1048576))


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'sunforge.praline-world')
