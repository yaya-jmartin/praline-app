// End-to-end test of the map editor in headless Chromium (Playwright).
//   node tools/map-editor/test/run.js            (from the repo root; needs playwright and python3 with Pillow)
// It drives the real page with the mouse and keyboard: trail pen, spots, the recipe form with a cover,
// a .praline upload, the library (still picture, sprite sheet, effects), the preview and story box, the
// export (checked with check-pack.py), opening the pack again, and saving and reopening the project.
const path = require('path');
const fs = require('fs');
const os = require('os');
const { execFileSync } = require('child_process');
let playwright;
try { playwright = require('playwright'); } catch (e) { playwright = require('/opt/node22/lib/node_modules/playwright'); }

const HERE = __dirname;
const EDITOR = 'file://' + path.resolve(HERE, '..', 'index.html');
const OUT = fs.mkdtempSync(path.join(os.tmpdir(), 'map-editor-test-'));
const SHOTS = process.env.SHOTS || OUT;
let failures = 0, passed = 0;
function ok(cond, what) { if (cond) { passed++; console.log('  ok  ' + what); } else { failures++; console.log('  FAIL ' + what); } }

(async () => {
  const browser = await playwright.chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, acceptDownloads: true });
  // test the download route for saving (the folder route needs a person to pick a folder)
  await ctx.addInitScript(() => { delete window.showDirectoryPicker; });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(EDITOR);
  await page.waitForFunction(() => window.Model && Model.state.p && Model.state.p.spots.length === 10);
  await page.waitForTimeout(400);
  const P = () => page.evaluate(() => JSON.parse(JSON.stringify(Model.state.p)));
  ok((await P()).world.id === 'new-world', 'starts from the template');

  // map units -> page coordinates
  async function screen(x, y) {
    return page.evaluate(([x, y]) => {
      const r = document.getElementById('map').getBoundingClientRect(), v = MapView.view;
      return { x: r.left + (x - v.left) * v.k, y: r.top + (y - v.top) * v.k };
    }, [x, y]);
  }
  async function trailPoint(t) { const p = await page.evaluate(t => { const q = Model.trail().at(t); return { x: q.x, y: q.y }; }, t); await page.evaluate(y => MapView.scrollTo(y), p.y); await page.waitForTimeout(50); return screen(p.x, p.y); }

  // --- trail pen
  console.log('trail');
  await page.click('#tool-trail');
  const before = (await P()).trail.length;
  await page.evaluate(() => MapView.scrollTo(400));
  let s = await screen(320, 330);
  await page.mouse.move(s.x, s.y); await page.mouse.down(); await page.mouse.move(s.x + 40, s.y - 30, { steps: 4 }); await page.mouse.up();
  ok((await P()).trail.length === before + 1, 'clicking empty map extends the trail');
  const end = (await P()).trail.slice(-1)[0];
  ok(Math.abs(end.x - 320) < 2 && end.ox > end.x, 'the new end point sits where clicked, with a pulled-out handle');
  s = await trailPoint(0.5);
  await page.mouse.click(s.x, s.y);
  ok((await P()).trail.length === before + 2, 'clicking on the trail inserts a point');
  await page.keyboard.press('Delete');
  ok((await P()).trail.length === before + 1, 'Delete removes the selected point');
  await page.keyboard.press('Control+z');
  ok((await P()).trail.length === before + 2, 'undo brings it back');
  await page.keyboard.press('Control+Shift+z');
  ok((await P()).trail.length === before + 1, 'redo removes it again');

  // --- spots
  console.log('spots');
  await page.click('#tool-select');
  s = await trailPoint(0.03);
  await page.mouse.dblclick(s.x, s.y);
  let p = await P();
  ok(p.spots.length === 11 && p.spots[0].id === '11', 'double click on the trail adds a spot, in trail order, with a fresh id');
  s = await trailPoint(p.spots[0].along);
  await page.mouse.move(s.x, s.y); await page.mouse.down();
  const s2 = await screen(...Object.values(await page.evaluate(() => { const q = Model.trail().at(0.045); return { x: q.x, y: q.y }; })));
  await page.mouse.move(s2.x, s2.y, { steps: 6 }); await page.mouse.up();
  p = await P();
  ok(p.spots[0].id === '11' && Math.abs(p.spots[0].along - 0.045) < 0.01, 'dragging a spot slides it along the trail');
  await page.mouse.dblclick(s2.x, s2.y);
  ok((await P()).spots.length === 10, 'double click on an empty spot removes it');

  // spot 1: story, then a recipe made with the quick form
  p = await P();
  s = await trailPoint(p.spots[0].along);
  await page.mouse.click(s.x, s.y);
  await page.waitForSelector('.spot-head');
  const story = 'You can spot strange silhouettes in the distance, horses but with bumps on their backs and even fur. Malik welcomes you at the Camel Farm.';
  await page.fill('.story-row textarea', story);
  await page.locator('.story-row textarea').blur();
  ok((await P()).spots[0].story === story, 'the story box saves the story');
  ok((await page.textContent('.counter')).startsWith(story.length + ' /'), 'the story counter counts characters');

  const jpeg = await page.evaluate(async () => {
    const c = new OffscreenCanvas(800, 600), g = c.getContext('2d');
    g.fillStyle = '#F4E1B5'; g.fillRect(0, 0, 800, 600); g.fillStyle = '#C9774A'; g.beginPath(); g.arc(400, 300, 220, 0, 7); g.fill();
    const b = await c.convertToBlob({ type: 'image/jpeg', quality: 0.9 });
    return Array.from(new Uint8Array(await b.arrayBuffer()));
  });
  await page.click('text=Create recipe…');
  await page.waitForSelector('.recipe-form');
  await page.fill('.recipe-form input[placeholder="Camel Cheesecake"]', 'Camel Cheesecake');
  await page.fill('.recipe-form textarea[placeholder^="200 g"]', '200 g cream cheese\n3 eggs\n1 1/2 tbsp honey\nsalt');
  await page.fill('.step-row .step-text', 'Beat the cheese with the honey.');
  await page.click('text=+ Add a step');
  await page.locator('.step-row .step-text').nth(1).fill('Bake until set.');
  await page.locator('.step-row .step-time').nth(1).fill('35:00');
  await page.locator('.step-row .step-label').nth(1).fill('Oven');
  const [chooser] = await Promise.all([page.waitForEvent('filechooser'), page.click('text=Choose a picture…')]);
  await chooser.setFiles({ name: 'cover.jpg', mimeType: 'image/jpeg', buffer: Buffer.from(jpeg) });
  await page.waitForFunction(() => document.querySelector('.cover-pic') && document.querySelector('.cover-pic').style.display === 'block');
  await page.screenshot({ path: path.join(SHOTS, 'editor-recipe-form.png') });
  await page.click('text=Save recipe');
  await page.waitForFunction(() => { const s = Model.state.p.spots[0]; return s.recipe && Model.state.recipes.get(s.id) && Model.state.recipes.get(s.id).title; });
  const rec = await page.evaluate(async () => { const s = Model.state.p.spots[0]; const x = await Recipes.read(Model.state.files.get(s.recipe)); return { r: x.recipe, photos: Object.keys(x.photos), name: s.name }; });
  ok(rec.r.title === 'Camel Cheesecake' && rec.name === 'Camel Cheesecake', 'the quick form makes the recipe and names the spot after it');
  ok(rec.r.variants[0].ing.length === 4 && rec.r.variants[0].ing[2].q === 1.5 && rec.r.variants[0].ing[2].u === 'tbsp' && rec.r.variants[0].ing[3].q === undefined, 'ingredient lines are read like the app reads them');
  ok(rec.r.variants[0].steps[1].s === 2100 && rec.r.variants[0].steps[1].l === 'Oven', 'step timers are kept in seconds with their name');
  ok(rec.r.photo && rec.r.orig && rec.r.photo !== rec.r.orig && rec.photos.length === 2, 'the cover is the photo, with the original kept for filters');
  ok(rec.r.world === 'new-world', 'the recipe knows its world');

  // spot 2: upload a .praline made by the app's format (from the Sunforge converter)
  const sun = path.join(OUT, 'sunforge.praline-world');
  execFileSync('python3', [path.resolve(HERE, '..', 'convert-sunforge.py'), sun]);
  const sunZip = await page.evaluate(async b => { const f = await Zip.read(new Uint8Array(b).buffer); return Array.from(f['recipes/4.praline']); }, Array.from(fs.readFileSync(sun)));
  p = await P();
  s = await trailPoint(p.spots[1].along);
  await page.mouse.click(s.x, s.y);
  await page.waitForSelector('.spot-head');
  const [ch2] = await Promise.all([page.waitForEvent('filechooser'), page.click('text=Upload .praline…')]);
  await ch2.setFiles({ name: 'Camel-Farm-Cheese.praline', mimeType: 'application/octet-stream', buffer: Buffer.from(sunZip) });
  await page.waitForFunction(() => { const s = Model.state.p.spots[1]; return s.recipe && Model.state.recipes.get(s.id) && Model.state.recipes.get(s.id).title; });
  ok(await page.evaluate(() => Model.state.recipes.get(Model.state.p.spots[1].id).title === 'Camel-Farm Cheese'), 'a .praline file becomes the spot\'s recipe');
  // a file that is not a recipe is refused
  const [ch3] = await Promise.all([page.waitForEvent('filechooser'), page.click('text=Upload .praline…')]);
  await ch3.setFiles({ name: 'nope.praline', mimeType: 'application/octet-stream', buffer: Buffer.from('not a zip') });
  await page.waitForFunction(() => document.getElementById('toast').classList.contains('bad'));
  ok(await page.evaluate(() => Model.state.recipes.get(Model.state.p.spots[1].id).title === 'Camel-Farm Cheese'), 'a broken file is refused and the recipe stays');

  // --- library
  console.log('library');
  const sprite = await page.evaluate(async () => {
    const c = new OffscreenCanvas(210, 330), g = c.getContext('2d');
    g.fillStyle = '#6B4A2B'; g.fillRect(95, 150, 20, 180); g.fillStyle = '#3E8E4E';
    for (let i = 0; i < 6; i++) { g.beginPath(); g.ellipse(105, 140, 100, 22, i, 0, 7); g.fill(); }
    return Array.from(new Uint8Array(await (await c.convertToBlob({ type: 'image/png' })).arrayBuffer()));
  });
  const sheet = await page.evaluate(async () => {
    const c = new OffscreenCanvas(96 * 6, 96), g = c.getContext('2d');
    for (let i = 0; i < 6; i++) { g.fillStyle = 'rgba(255,' + (120 + i * 20) + ',40,0.9)'; g.beginPath(); g.ellipse(i * 96 + 48, 60 - i * 3, 18 + i * 2, 28 + i * 3, 0, 0, 7); g.fill(); }
    return Array.from(new Uint8Array(await (await c.convertToBlob({ type: 'image/png' })).arrayBuffer()));
  });
  await page.click('.tab[data-tab="library"]');
  const [ch4] = await Promise.all([page.waitForEvent('filechooser'), page.click('text=Import pictures…')]);
  await ch4.setFiles([{ name: 'Palm Tree.png', mimeType: 'image/png', buffer: Buffer.from(sprite) }, { name: 'campfire-sheet.png', mimeType: 'image/png', buffer: Buffer.from(sheet) }]);
  await page.waitForFunction(() => Object.keys(Model.state.p.library).length === 2);
  const lib = (await P()).library;
  ok(lib['palm-tree'].kind === 'sprite' && lib['palm-tree'].ax === 105 && lib['palm-tree'].ay === 330, 'a still picture is anchored at its bottom middle');
  ok(lib['campfire-sheet'].kind === 'sheet' && lib['campfire-sheet'].frames === 6 && lib['campfire-sheet'].frameW === 96, 'a strip of square-ish frames is read as an animation');
  // drag the palm onto the map
  await page.evaluate(() => MapView.scrollTo(2600));
  const drop = await screen(90, 2600);
  await page.dragAndDrop('.lib-tile[title^="Palm Tree"]', '#map', { targetPosition: { x: drop.x - (await page.locator('#map').boundingBox()).x, y: drop.y - (await page.locator('#map').boundingBox()).y } });
  p = await P();
  ok(p.items.length === 1 && p.items[0].lib === 'palm-tree' && Math.abs(p.items[0].x - 90) < 2 && Math.abs(p.items[0].y - 2600) < 2, 'dragging a library picture onto the map places it there');
  // a click on an effect places it
  await page.click('.tab[data-tab="library"]');
  await page.click('.lib-tile[title^="Glow"]');
  await page.click('.tab[data-tab="library"]');
  await page.click('.lib-tile[title^="God rays"]');
  await page.evaluate(() => MapView.placeItem('campfire-sheet', 250, 2500));
  p = await P();
  ok(p.items.length === 4 && p.items[1].params && p.items[1].params.color === '#FFB347' && p.items[2].layer === 'over', 'effects come with their settings and light goes over the trail');
  // move an item with the arrow keys, bake the palm
  await page.evaluate(() => MapView.select({ type: 'item', id: 'i1' }));
  await page.keyboard.press('Shift+ArrowRight');
  ok(Math.abs((await P()).items[0].x - 100) < 2.1, 'Shift + arrow nudges an item by 10 units');
  await page.click('.tab[data-tab="inspect"]');
  await page.click('text=Bake into the map');
  ok((await P()).items[0].bake === true, 'a still picture can be baked');

  // story stops for the rest, one with a reward
  await page.evaluate(() => {
    Model.snapshot();
    Model.state.p.spots.forEach((s, i) => { if (i >= 2) { s.kind = 'story'; s.story = 'A story for stop ' + (i + 1) + '.'; } });
    Model.state.p.spots[4].reward = { kind: 'decoration', name: 'Brass spoon', item: 'palm-tree' };
    Model.state.p.spots[2].icon = 'palm-tree';
    Model.state.p.spots[1].story = 'Malik is gone, and there is a recipe on the counter.';
    Model.state.p.world.ending = { title: 'The farm is fed', story: 'Malik comes back with a smile.' };
    Model.changed('spots');
  });
  ok(await page.evaluate(() => {
    const c = document.createElement('canvas').getContext('2d');
    return Model.FX.every(k => { const it = { id: 'i9', x: 50, y: 50, params: {} }; Draw.drawFx(c, k, {}, it, 1.3); return true; });
  }).catch(() => false), 'every effect, flame and ripples included, draws');
  await page.evaluate(() => MapView.scrollTo(2500));
  await page.waitForTimeout(200);
  await page.screenshot({ path: path.join(SHOTS, 'editor-map.png') });

  // --- preview
  console.log('preview');
  await page.click('#preview');
  await page.waitForSelector('.phone-screen');
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(SHOTS, 'preview.png') });
  await page.evaluate(() => { const r = document.querySelector('.preview-side input[type=range]'); r.value = 0; r.dispatchEvent(new Event('input')); });
  await page.click('text=Find the party');
  await page.waitForTimeout(100);
  const box = await page.locator('.phone-screen').boundingBox();
  const at = await page.evaluate(() => { const t = Model.state.p.spots[1], q = Model.trail().at(t.along), v = Preview.view(); return { x: q.x * v.k, y: (q.y - v.top) * v.k }; });
  await page.mouse.click(box.x + at.x, box.y + at.y);
  await page.waitForSelector('.story-box', { timeout: 2000 }).catch(() => {});
  ok(await page.$('.story-box') !== null, 'tapping a spot in the preview opens its story box');
  await page.screenshot({ path: path.join(SHOTS, 'preview-story.png') });
  await page.keyboard.press('Escape');
  ok(await page.$('.preview-back') === null, 'Esc closes the preview');

  // --- export
  console.log('export');
  await page.click('#export');
  await page.waitForSelector('.check-list');
  const [download] = await Promise.all([page.waitForEvent('download', { timeout: 60000 }), page.click('text=Export new-world.praline-world')]);
  const packPath = path.join(OUT, 'new-world.praline-world');
  await download.saveAs(packPath);
  let out = '';
  try { out = execFileSync('python3', [path.resolve(HERE, '..', 'check-pack.py'), packPath]).toString(); ok(true, 'the exported pack passes check-pack.py'); }
  catch (e) { out = e.stdout.toString(); ok(false, 'the exported pack passes check-pack.py'); }
  console.log(out.split('\n').map(l => '       ' + l).join('\n'));
  const w = await page.evaluate(async b => { const f = await Zip.read(new Uint8Array(b).buffer); return JSON.parse(new TextDecoder().decode(f['world.json'])); }, Array.from(fs.readFileSync(packPath)));
  ok(w.items.length === 3 && !w.items.some(i => i.lib === 'palm-tree'), 'baked pictures are painted into the slices, not sent as items');
  ok(w.library['palm-tree'] && w.library['fx-glow'] && w.library['campfire-sheet'], 'the library holds what the world uses (icons and rewards included)');
  ok(w.spots[0].kind === 'recipe' && w.spots[4].reward.name === 'Brass spoon' && w.spots[2].kind === 'story', 'spot kinds and rewards are exported');
  ok(w.spots.every((x, i) => i === 0 || x.along >= w.spots[i - 1].along), 'spots are in trail order');
  ok(w.ending && w.ending.title === 'The farm is fed', 'the ending banner is exported');

  // export is refused while a recipe spot has no recipe
  await page.evaluate(() => { Model.snapshot(); Model.state.p.spots[3].kind = 'recipe'; Model.changed('spots'); });
  await page.click('#export');
  await page.waitForSelector('.check-list .errors');
  ok((await page.textContent('.check-list .errors')).includes('without a recipe'), 'export lists a recipe spot without a recipe as something to fix');
  await page.keyboard.press('Escape');
  await page.evaluate(() => Model.undo());

  // --- save the project and open it again
  console.log('save and reopen');
  const projBefore = await P();
  const [dl2] = await Promise.all([page.waitForEvent('download'), page.click('#save')]);
  const projPath = path.join(OUT, 'new-world.praline-map');
  await dl2.saveAs(projPath);
  await page.click('#new');
  await page.waitForFunction(() => Model.state.p.items.length === 0);
  const [ch5] = await Promise.all([page.waitForEvent('filechooser'), page.click('#open-file')]);
  await ch5.setFiles(projPath);
  await page.waitForFunction(() => Model.state.p.items.length === 4);
  const projAfter = await P();
  ok(JSON.stringify(projAfter) === JSON.stringify(projBefore), 'a saved project opens exactly as it was');
  await page.waitForFunction(() => Model.state.recipes.get(Model.state.p.spots[0].id), null, { timeout: 5000 }).catch(() => {});
  ok(await page.evaluate(() => Model.state.recipes.get(Model.state.p.spots[0].id)?.title === 'Camel Cheesecake' && !!Model.state.recipes.get(Model.state.p.spots[0].id).cover), 'its recipes and covers come back');

  // --- open the exported pack, and the converted Sunforge pack
  console.log('open packs');
  const [ch6] = await Promise.all([page.waitForEvent('filechooser'), page.click('#open-file')]);
  await ch6.setFiles(packPath);
  await page.waitForFunction(() => Model.state.p.world.id === 'new-world' && Model.state.p.art && Model.state.p.spots.length);
  await page.waitForTimeout(300);
  p = await P();
  ok(p.spots.length === 10 && p.spots[0].name === 'Camel Cheesecake' && p.released === true && p.parchment.mode === 'none', 'an exported pack opens again for editing, marked as shipped');
  ok(p.nextSpot === 12, 'new spots in an opened pack never reuse an id');
  ok(p.world.ending.story === 'Malik comes back with a smile.', 'the ending banner comes back with the pack');
  const [ch7] = await Promise.all([page.waitForEvent('filechooser'), page.click('#open-file')]);
  await ch7.setFiles(sun);
  await page.waitForFunction(() => Model.state.p.world.id === 'sunforge' && !document.querySelector('.modal-back'));
  await page.waitForTimeout(300);
  p = await P();
  ok(p.spots.length === 10 && p.spots[9].name === 'The Calice' && p.items.length === 27, 'The Sunforge Sands opens with its 10 spots and 27 effects (' + p.spots.length + ', ' + p.spots[9].name + ', ' + p.items.length + ')');
  await page.waitForTimeout(300);
  await page.evaluate(() => MapView.scrollTo(600));
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(SHOTS, 'sunforge-editor.png') });
  await page.click('#preview');
  await page.waitForSelector('.phone-screen');
  await page.evaluate(() => { const r = document.querySelector('.preview-side input[type=range]'); r.value = 700; r.dispatchEvent(new Event('input')); });
  await page.click('text=Find the party');
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(SHOTS, 'sunforge-preview.png') });
  await page.keyboard.press('Escape');

  ok(errors.length === 0, 'no errors in the page' + (errors.length ? ': ' + errors.join(' | ') : ''));
  await browser.close();
  console.log('\n' + passed + ' passed, ' + failures + ' failed. Screenshots in ' + SHOTS);
  process.exit(failures ? 1 : 0);
})().catch(e => { console.error(e); process.exit(2); });
