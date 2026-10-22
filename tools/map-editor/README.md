# Praliné map editor

A PC tool for painting mission worlds: the trail, the recipe spots and story stops, their stories and
recipes, and the animated items on the map. It writes a `.praline-world` pack that the app's map scene plays.
The pack format is in [WORLD-FORMAT.md](WORLD-FORMAT.md).

## Open it

Double click `index.html` and open it in **Chrome or Edge** (they can save straight into a project folder).
Nothing to install, no server, nothing goes online. Firefox and Safari work too, but save by download.

## Use it

1. **World tab**: name the world, give it an id (never change it after it ships), write the opening story,
   set the title painted over the map, its height, the parchment and your own map art.
2. **Trail** tool (`P`): click to add points, drag to pull out curves, click the trail to split it,
   `Alt` for a sharp corner, double click a point to round or sharpen it.
3. **Select** tool (`V`): double click the trail to add a spot, double click a spot to remove it, drag a spot to
   slide it along the trail. In the **Selected** tab, pick *Recipe spot* or *Story stop*, write its story
   (600 characters), set the fame to reach it, then upload a `.praline` from the phone or *Create recipe…*
   (title, class, tier, effort, ingredients, steps with timers, and the cover picture players see as a hint).
   Story stops can give a profile decoration as a reward.
4. **Library tab**: drop in your own pictures and animation sheets (PNG or WebP, painted at 3×), then drag
   them onto the map. Effects (rays, glow, smoke, sparks…) are built in. Mark still pictures *Bake* to
   flatten them into the map so they cost the phone nothing.
5. **Preview** shows the phone screen: move the fame slider, walk the trail, tap spots to read their stories.
6. **Save** (`Ctrl+S`) keeps the project folder. **Export pack** checks the world and writes the
   `.praline-world` file for the app. *Open file* reopens a project or an exported pack.

An autosave draft is kept in the browser in case the tab closes.

## For developers

- Plain scripts, no build: `js/` (zip, geometry, model, drawing, recipe form, file I/O, map canvas, panels,
  preview, app). `js/assets.js` embeds the default parchment and the app fonts; rebuild it with
  `python3 tools/map-editor/make-assets.py`.
- `python3 tools/map-editor/convert-sunforge.py out.praline-world` builds The Sunforge Sands from the app's
  current mission assets, a real pack for testing the map scene (its stories are placeholders).
- `python3 tools/map-editor/check-pack.py some.praline-world` checks a pack against the format.
- `SHOTS=dir node tools/map-editor/test/run.js` runs the end-to-end test in headless Chromium (Playwright),
  saving screenshots to `dir`.
