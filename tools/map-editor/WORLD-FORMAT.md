# Praliné world pack format (`.praline-world`, v1)

One file holds one world: its painted map, trail, spots, stories, recipes and the library items
it uses. The **map editor** (`tools/map-editor/`) writes it and the **map scene** in the app plays it.
This file is the contract between the two; change it here first, in the same change as both sides.

## Units

- Every position and size is in **map units**. The map is **412 units wide** (a phone's width in dp).
  The scene scales one unit to `viewWidth / 412` screen pixels.
- Art is painted at **3 px per unit** (`"scale": 3`), so the map is **1236 px wide**.
- `y` grows downwards. The party walks from the bottom of the map (trail start) to the top (trail end).
- Angles are in degrees, 0 = right (+x), clockwise (because y points down).
- Colours are `"#RRGGBB"` or `"#AARRGGBB"`.

## The zip

A plain zip (stored or deflated). Entry names use only `A-Z a-z 0-9 . _ -` inside these folders:

| Entry | What |
| --- | --- |
| `world.json` | Everything below. At most 1 MB. |
| `slices/sNN.webp` (or `.jpg`) | The flattened map (parchment + art + baked items), opaque WebP (JPEG is also read). The editor writes 1236 × 1575 px slices (525 units); the last slice may be shorter. The scene places each slice by its `top` and `height` in units, never by its pixel size. |
| `recipes/<spotId>.praline` | One recipe file per recipe spot, exactly the app's `.praline` format (`praline.json` + `photos/*.jpg`), cover picture included. |
| `library/<file>` | PNG or WebP pictures used by items, icons and rewards. |

Limits the app enforces: whole pack ≤ 25 MB, ≤ 400 entries, any single entry ≤ 8 MB, unknown
entries ignored, names outside the folders above rejected. A pack is untrusted input, like a `.praline` file.

## `world.json`

```json
{
  "format": "praline-world",
  "v": 1,
  "id": "sunforge",
  "kind": "mission",
  "name": "The Sunforge Sands",
  "intro": "Optional story shown when the party first opens the world.",
  "ending": { "title": "The Calice is lit", "story": "Optional story shown when the last spot is cooked." },
  "width": 412,
  "height": 3150,
  "scale": 3,
  "title": { "text": "The Sunforge Sands", "x": 206, "y": 96, "size": 38, "color": "#3B2414", "rotate": -2, "font": "title" },
  "slices": [ { "file": "slices/s00.webp", "top": 0, "height": 525 } ],
  "trail": {
    "points": [[206.0, 2850.0], [212.0, 2844.7]],
    "length": 2659.0,
    "bezier": [ { "x": 206, "y": 2850, "ix": 206, "iy": 2850, "ox": 260, "oy": 2810 } ]
  },
  "spots": [
    {
      "id": "1",
      "kind": "recipe",
      "name": "Dune Flatbread",
      "along": 0.0766,
      "x": 304.2,
      "y": 2684.8,
      "side": "left",
      "xp": 80,
      "story": "You can spot strange silhouettes in the distance...",
      "recipe": "recipes/1.praline",
      "icon": "icon_flatbread"
    },
    {
      "id": "2",
      "kind": "story",
      "name": "The Bleached Skeleton",
      "along": 0.14,
      "x": 180.0,
      "y": 2520.0,
      "side": "right",
      "xp": 60,
      "story": "Half buried in the sand, a skeleton still clutches a brass spoon...",
      "reward": { "kind": "decoration", "name": "Brass spoon", "item": "brass_spoon" }
    }
  ],
  "library": {
    "palm":      { "kind": "sprite", "file": "library/palm.webp", "w": 210, "h": 330, "ax": 105, "ay": 320, "ppu": 3 },
    "campfire":  { "kind": "sheet",  "file": "library/campfire.webp", "frameW": 96, "frameH": 120, "cols": 6, "frames": 12,
                   "fps": 12, "loop": "loop", "ax": 48, "ay": 112, "ppu": 3, "blend": "normal" },
    "rays":      { "kind": "fx", "fx": "rays" }
  },
  "items": [
    { "id": "i1", "lib": "palm", "x": 92, "y": 2210, "scale": 1, "rot": 0, "flip": false, "layer": "under", "z": 0, "alpha": 1 },
    { "id": "i2", "lib": "campfire", "x": 236, "y": 1538, "scale": 1, "rot": 0, "flip": false, "layer": "under", "z": 0, "alpha": 1,
      "bob": { "amp": 0, "period": 0 } },
    { "id": "i3", "lib": "rays", "x": 338, "y": 205, "scale": 1, "rot": 0, "flip": false, "layer": "over", "z": 0, "alpha": 1,
      "params": { "from": 92, "to": 172, "length": 1550, "count": 7, "color": "#FFE7A6" } }
  ]
}
```

### Fields

- `id`: `[a-z0-9-]{1,32}`, permanent. Mission progress is keyed by it (`Store.missions[id]`), so it never changes once a world ships.
- `kind`: `"mission"` today. `"housing"` and others later use the same layout with no trail.
- `name`: shown in lists and the mission header. `title` is the name painted over the map, drawn live by the app (`font`: `"title"` = Mea Culpa, `"body"` = Fraunces semibold).
- `scale`: px per unit of the slices. The editor writes 3; the Sunforge pack bundled with the app is 2 (824 px slices). Library pictures carry their own `ppu`.
- `ending` (optional): the banner shown when the last spot is cooked, `title` (up to 40 characters) and `story` (up to 600).
- `height`: map height in units (2,400 to 4,800 recommended). `slices` cover `0..height` with no gaps.
- `editor` (optional): the map editor's own counters (`nextSpot`, `nextItem`) so ids are never reused when a pack is opened again. The app ignores it.
- `trail.points`: the path sampled every 8 units or less, from start (party's first step) to end. `length` = its length in units. `bezier` is the editor's own copy (anchors with in/out handles) and the app may ignore it.

### Spots

- `id`: `[a-z0-9-]{1,24}`, permanent and never reused in a world. The recipe of a recipe spot gets the
  app id `mission-<worldId>-<spotId>`, so The Sunforge Sands keeps `mission-sunforge-1` to `-10`.
- `kind`: `"recipe"` (must-cook: its story plays when the party arrives, then its recipe joins the grimoire,
  and the trail stays locked past it until a chef cooks it) or `"story"` (a story box, an optional reward,
  nothing to cook: the party walks straight on).
- `along`: 0..1 of the trail's length, increasing with the spot order. `x`, `y` are that point, for convenience.
- `side`: where the label goes, `"left"` or `"right"` of the spot.
- `xp`: party fame needed from the previous spot (or the start) to reach this one. A spot's threshold is the sum of `xp` up to it.
- `story`: up to 600 characters, plain text, `\n` for a new paragraph.
- `recipe` (recipe spots): the `.praline` entry. Its first recipe is the spot's recipe; its cover picture is that recipe's `photo` (and `orig`), a JPEG under `photos/`.
- `icon`: a library id for the medallion picture (a sprite, about 128 × 128 px). Missing = the recipe's cover, cropped round.
- `reward` (story spots, optional): `kind` `"decoration"` (a profile decoration), `name`, and `item` = a library id for its picture.

### Library

Each entry is one of:

- `sprite`: a still picture. `w`, `h` in px; `ax`, `ay` = anchor in px (where it stands: feet, base); `ppu` = px per unit it was painted at (3, older 2× art says 2).
- `sheet`: an animation. The file is a grid of `frames` equal frames (`frameW` × `frameH` px), `cols` per row, read left to right then down. `fps` 1 to 30, `loop` = `"loop"`, `"pingpong"` or `"once"`. `blend` = `"normal"` or `"add"` (glow, fire, magic). Anchor and `ppu` as for sprites. Sheets are at most 2048 × 2048 px and 48 frames.
- `fx`: a live effect the app draws itself; `fx` names it. Its settings live on the item (`params`), see below. An optional `file` is a small particle picture (≤ 128 × 128 px).

Sprites only used as `bake: true` items are merged into the slices at export and don't appear in `library` at all.

### Items

`lib` = library id; `x`, `y` = where the anchor sits; `scale` (1 = painted size), `rot` degrees, `flip` mirrored left-right,
`alpha` 0..1, `layer` = `"under"` (below the trail and spots) or `"over"` (above them, for light), `z` orders items within a layer
(lower first, then by `y`). `bob` (optional) floats the item up and down by `amp` units every `period` seconds.

FX kinds and their `params` (all optional; defaults in brackets):

| `fx` | Draws | `params` |
| --- | --- | --- |
| `rays` | god rays fanning from the point | `from` [92], `to` [172] degrees, `length` [1500], `count` [7], `color` [#FFE7A6], `alpha` [0.35] |
| `halo` | a soft round glow, a sun | `r` [34], `halo` [110], `color` [#FFF1C2] |
| `glow` | a pulsing light (lantern, kiln) | `r` [22], `halo` [2 × `r`] outer glow radius, `color` [#FFB347], `pulse` [2.2] seconds |
| `glints` | twinkling sparkles on water | `rx` [60], `ry` [24], `count` [6], `color` [#FFFFFF] |
| `smoke` | a rising smoke plume | `color` [#B8A898], `rate` [1] |
| `sparks` | sparks rising (forge, campfire) | `color` [#FFC266], `rate` [1] |
| `sand` | a drifting sand plume | `color` [#E8C890], `rate` [1] |
| `heat` | heat shimmer | `w` [40], `h` [60] |
| `beam` | a light beam to another point | `tx`, `ty` (end), `width` [14], `color` [#FFE7A6], `flare` [30] impact flare radius at the end, `sparks` [true] |
| `motes` | dust motes over the whole visible map (x, y ignored) | `count` [70], `color` [#FFE9B8] |
| `flame` | a campfire flame | `w` [12], `h` [28], `color` [#FF8A2A], `core` [#FFD25A] |
| `ripples` | rings spreading on water | `rx` [30], `ry` [10], `period` [3.2] seconds |

A scene that meets an `fx` it doesn't know, or an item whose `lib` is missing, skips it.

## What the app draws itself

The pack holds the world, not the mission chrome. The app draws the spot dais, the pennant (pole at `x − 14`, from `y − 64`
down to `y + 2`), the medallion ring at `(x, y − 34)` around the spot's `icon`, the MUST COOK pill, the lock on the trail
and the party's chef figures, all relative to each spot's `x`, `y`. The editor's phone preview draws them the same way.

## Packs opened on the phone

A `.praline-world` opened from Files or a chat app is checked against the limits above and kept as
`filesDir/worlds/<id>.praline-world`, replacing an older copy with the same `id`.

## Budgets

About 40 live (non-baked) items per world, at most 12 animations on screen at once, and a pack ≤ 25 MB.
The editor warns past these.

## Versioning

`v` goes up only when an old app can't read a new pack correctly. New optional fields don't bump it.
An app that meets a higher `v` says the world needs a newer Praliné.
