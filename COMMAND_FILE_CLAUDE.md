# COMMAND_FILE_CLAUDE.md — Praliné

Strict working rules for Claude on this app. Read this file in full before answering, planning or changing anything.
If this file and a chat message disagree, Yaya's latest message wins and this file gets updated in the same change.
Repository: `yaya-jmartin/praline-app` (private). This file lives at its root.

App: **Praliné**, package `com.yaya.praline`, current version **1.4** (versionCode 5, built 2026-10-22: parties of four, mission invites, world packs, Beverages, OCR import, photo framing; first APK built and signed by GitHub Actions). Source: this repository (history from 1.1 on).

---

## 1. Rules of engagement (Yaya, 2026-10-17)

1. **Never build unless Yaya says so**, even for a fix. Questions get answers, suggestions get discussed. Every feature or bugfix Yaya asks for goes on the pending list for the next version (project memory `praline-next-version`; patches waiting in `/mnt/project-files/praline/pending/`) and is confirmed in the thread.
2. **Ship nothing half done.** Before a build, re-read the Praliné thread for every ask since the last release.
3. **Plan mode first.** The app is big enough (≈11,100 lines of Kotlin in 50 files): before any file changes, write the plan (files touched, lifecycle events affected (section 6), new security risks (section 9), tests to add), then edit.
4. **Global logic, not screen by screen.** Behaviour lives once in a shared object (`Store`, `Sync`, `Timers`, `Fame`, `Photos`, `Filters`, `Qty`, `Sheet`) and screens call it. A setting that turns something off turns it off everywhere.
5. **Lifecycle is never ignored** (section 6). Product choices go to Yaya for review.
6. **Stay compatible with Android**: minSdk 26, guard newer APIs, follow targetSdk rules (section 7).
7. **Security first, quietly** (section 9). Name any risk a build adds in the delivery reply.
8. **Recommend refactoring** when a module crosses the thresholds in section 8.
9. **CSVs stay on Yaya's side.** `praline-balancing.csv` and any spreadsheet are design input only. Their numbers are translated into Kotlin constants (e.g. `Fame`, `Classes`, `Tiers`, `Equipment`) or the app's storage; the app never ships or parses a CSV.
10. **Strict image memory management** (section 10) for every photo, portrait and filter.
11. **Issues mirror, rules decide**: once the `praline-app` repo exists, asks are tracked as GitHub issues (`pending` / `approved` / `release: next`); this file and the thread stay the source of truth, and only approved next-release issues are built.
12. **Library upgrades one at a time**: GitHub Actions build first, then Coil, then CameraX, each verified on Yaya's phones; signed APKs are then automated, publishing always waits for Yaya's approval.
13. **Plain-language guide**: keep `lightweight_explanation.md` (next to this file) current. Every dependency or tool added, removed or swapped gets a jargon-free line saying what it does and why it was picked over the alternatives, in the same change (Yaya, 2026-10-17).
14. **Test every version**: `gradle test testShrunk` (LogicTest, V11Test–V14Test, SceneTest, ScreensTest + new tests per change; tests read assets from build/apk/base.apk, so build the APK first; tests that sync set `HomeWifi.assumeHome = true`) once on the final build, after building the APK; check the screenshots in build/shots.

## 2. Tech stack (exact)

| Layer | What is used | Notes |
|---|---|---|
| Language | Kotlin 2.1.21, JVM target 1.8 | same SDK-less toolchain as Pistachio |
| Android API | Plain framework, **no AndroidX / Jetpack / Compose** | Google Maven blocked in the build container |
| SDK levels | minSdk 26, targetSdk 34 | |
| Libraries | `com.google.zxing:core:3.5.3` (QR make + read); GitHub build only: `com.google.mlkit:text-recognition:16.0.1` (on-device OCR, `agp/app/src/ocr/MlKitReader.kt`, looked up by name from `Ocr`) | the hand-made build has no OCR: "Read a cookbook page" is hidden there |
| UI | Views built in code (`Screen` subclasses in one `MainActivity`), custom drawables (`Ink`, `InkBox`, `PaperDrawable`, `TornEdge`), fonts Mea Culpa (titles) and Fraunces (body) | no `R` class |
| Storage | `filesDir/praline.json` (cookbook, chefs, proposals; atomic write via `.tmp`, broken file kept as `praline.broken.json`), `filesDir/photos/`, SharedPreferences `app` for UI prefs | |
| Camera | `android.hardware.Camera` (deprecated API) + `TextureView` for the QR scanner (preview size from `pickPreviewSize`: sharpest ≤1080p; refocus every 1.5 s; two binarizers + inverted); system camera intents for photos/clips via `PhotoProvider` | |
| Sync | Same-Wi-Fi TCP socket (`ServerSocket`), NSD (DNS-SD) discovery, protocol 4 (1.4): up to 3 paired phones (`Store.peers`, one 256-bit secret each), ECDH key exchange per sync, mutual proof, AES-256-GCM sealed streams (`SecureLink`), pairing by a 26-char QR secret or a 12-char typed code stretched with PBKDF2 (60k rounds); live sync every 15 s while open; the home-Wi-Fi lock (`HomeWifi`) is optional since 1.4 | no server, no account |
| Recipe files | `.praline` zip (praline.json + photos/) shared through the share sheet, opened via VIEW/SEND intents (`RecipeFiles`) | for chefs outside the household |
| Missions | `Missions` (logic; worlds from `Worlds.all()`), `MissionParty` (members, invites), `MissionInvite` (cards), `MissionMap` (mission layer on the map scene), `MissionScreen` | Templates removed in 1.4: spot recipes come from the packs |
| Map scene (1.4) | `SceneView` + `SceneFx` (Scene.kt): camera, slice and picture memory, sprites, sprite sheets, live effects, one clock, taps. `WorldPack` reads `.praline-world` packs (format: `tools/map-editor/WORLD-FORMAT.md`), `Worlds` lists bundled (assets/worlds/<id>/, unzipped) and installed (filesDir/worlds/<id>.praline-world) worlds. Mission chrome (dais, lock, figures) in assets/missions/chrome/ | the map editor writes packs, the scene plays them |
| Timers | `AlarmManager` + `TimerReceiver`, `BootReceiver`; hand-off to Pistachio with `ACTION_SET_TIMER` | |
| Build / tests | Gradle → ProGuard → dx → aapt2 → apksigner; Robolectric 4.14.1. From 1.3 also `agp/` (Android Gradle Plugin 8.7.3, compileSdk 35) built by `.github/workflows/android.yml` on every push/PR: debug + release APKs, unit tests, Android Lint, artifacts kept 14 days. From 1.4 the release APK is signed there with the `praline` key from repository secrets `PRALINE_KEYSTORE_B64` + `PRALINE_KEY_PASSWORD` (artifact `praline-signed`); the 1.4+ APK Yaya installs comes from there (it has OCR) | signing alias `praline`, key + password only in `signing-backup/`, never in git or memory |

Upgrade path (suggest when Google Maven is reachable): Coil 3 for images, CameraX for the scanner, Jetpack DataStore for prefs, Room if the cookbook outgrows one JSON file.

## 3. Architectural patterns

- **Single activity, screen stack**: `MainActivity` holds a stack of `Screen` objects (Home, Recipe, Editor, Quest, Photo, Profile, Pair, Board, Settings, Mission). `Screen.saveState()` + `Resume` bring the editor draft or quest step back after Android kills the process. `Sheet` is the shared pop-up.
- **One store**: `Store` (Model.kt) owns `Recipe` / `Variant` / `Step` / `Ingredient` / `Chef` / `Proposal` / `CookEntry`, loads and saves `praline.json`. Screens never write files directly.
- **Shared-cookbook rules**: newest edit wins per recipe, deletions stick, edits to shared recipes travel as proposals (outbox/inbox/answers), My Variant specials are per phone (index 100+k).
- **Game layer**: `Fame` (levels, feats, favourite weapon), `Classes`, `Tiers`, `Equipment`, `Fate` (d20), `Missions` are pure logic objects with tests.
- **Party missions**: progress is never stored, only derived: walk the members' `CookEntry` history (each from when they joined; 1.3 missions keep their old walk until `legacyUntil`); a spot sits at its gap × the party size past the one before, and only unreached spots stretch when someone joins; story stops pass on arrival. Before 1.4: both chefs' history since `Store.missions[world]` (start time, earliest wins on sync) in time order; a spot is done when its recipe (fixed id `mission-<world>-N`) is cooked after party XP ≥ the spot's threshold. Reached spots add their recipe to the grimoire once (`Missions.unlock`, respects tombstones). A new world = a `.praline-world` pack from the map editor (bundled in assets/worlds/ or opened from a file); spot recipe ids are `mission-<world>-<spotId>`.
- **Map scene (1.4)**: every painted, animated scene is a `SceneView` subclass playing a world pack: the scene owns the camera, memory, items under/over, effects and clock; the subclass draws only its own layer in `drawStage` (`MissionMap`: trail, dusk, spots, lock, party). Housing and other scenes reuse it. Forms, lists and profiles stay plain Views.
- **Shared UI kit**: Ui.kt (`C` colours, `F` fonts), Ink.kt, Widgets.kt (`FlowLayout`, `RoundPhoto`, `ServingsWheel`, `Slider`, `TimerRing`, portraits).

## 4. Coding style

- Kotlin, 4-space indent, long lines allowed, match the file. KDoc on public functions in terms of what the user sees.
- Names: screens `XxxScreen`, logic objects nouns (`Store`, `Sync`, `Timers`).
- Test code must not call stdlib helpers main code doesn't use (`testShrunk` breaks).
- Look: cream paper, wobbly ink outlines, colourful offset shadows, D&D tone; no instructional hint text.
- Every version: versionCode +1, APK `Praline-x.y.apk`, screenshots folder, source pushed to this repository through a PR (a source tarball in `/mnt/project-files/praline/` too, until builds run on GitHub Actions).

## 5. Features (1.4)

- **Home**: tier list (S/A/B–D), sorting, quickest-first view, Fate (d20 pick), offers.
- **Recipe**: D&D stat block, variants subtabs, servings wheel (scaled quantities), equipment, step variables, dock, My Variant.
- **Editor**: new/edit recipe, ingredients (autocomplete, pantry), steps with timers and variables, equipment, photos and clips, save choices (shared edit = proposal).
- **Quest** (cooking mode): step by step, step timers (repeating rounds), done card, rewards, fame.
- **Photos**: Torchlight / Grotto / Mimic / Tavern / Moonlit filters with strength slider; original kept.
- **Chef profile**: character sheet, stats, favourite weapon, feats, portraits unlocking at levels 3/5/8/12/16, peer profile read-only.
- **Quest board**: time/energy suggestions, week planner with drag.
- **Two phones**: pair by QR or typed code on the same Wi-Fi, shared cookbook, sync while open on both.
- **Timers**: own Pistachio-style timers, hand-off to Pistachio (Settings toggle, on by default).
- **Settings** (swipe in from home), **friction log** (two-finger 600 ms hold + note, share to Discord).
- **1.4**: parties of up to four chefs (offers and answers relayed through the party, live sync, optional home lock, stronger pairing); mission invites and party-size distances; world packs from the map editor; Beverages; read a recipe from a cookbook photo (GitHub build); photo framing (drag, pinch, exact crop); drag to reorder ingredients; paste steps; save needs ingredient + step + equipment or "No equipment needed"; new variant copies Main; abandon a quest; level-up shake, flash and chime; past days locked; shopping list today → Sunday.
- **1.2**: party missions (World I "The Sunforge Sands", 10 must-cook spots ending at "The Calice"; Home bar → Missions); recipe files to send/open (`.praline`); sync and pairing only on the home Wi-Fi (Settings → Home dungeon access); equipment counts ("2 × Mixing bowl"), Kenwood and KCook as engineer limbs, mixing bowl; × to remove a step's picture, clip or timer; sharper QR scanner; portrait only; editor draft and quest step survive Android closing the app.

## 6. Lifecycle and system events

| Event | What Praliné does today | Status |
|---|---|---|
| Screen rotation / resize / theme change | `screenOrientation="portrait"` + `configChanges`: not recreated | OK (portrait only, 1.2) |
| Background (`onPause`) | stops the ticker; timers keep running via AlarmManager; `Resume.save` writes the top page's state | OK |
| Activity destroyed | `Sync.stopServer` | OK |
| Process killed by Android | cookbook is on disk; `resume.json` brings back the editor draft (with its tab) or the Quest step, only when Android rebuilds the app (a fresh launch drops it) | OK (1.2) |
| Crash | default handler stores the trace for the next launch | OK |
| Corrupt `praline.json` | renamed to `praline.broken.json`, app starts empty | **Yaya to review:** offer to restore from the other phone |
| Reboot / app update | `BootReceiver` re-arms timers | OK |
| Camera permission denied | scanner can't open; typed code still works | OK |
| Wi-Fi lost mid-sync | socket error reported, sync tried again on the next nudge or open (read from code, not tested on device) | OK |
| Other phone on an older protocol | "too old" toast | OK |
| Low memory (`onTrimMemory`) | `Photos.trim` empties the cache at UI_HIDDEN and above, halves it at RUNNING_LOW; `SceneView.trim` drops every open scene's pictures at RUNNING_LOW and above (they reload near the screen) | OK (1.4) |
| Left home Wi-Fi | server answers "not on your home Wi-Fi", auto-sync and NSD stop; Pair shows the home-first card | OK (1.2) |
| Location permission denied | Wi-Fi name unreadable, so no sync until allowed (explained on the Home dungeon access card) | OK (1.2) |
| Recipe file opened while Praliné runs | `onNewIntent` → "Add …?" sheet | OK (1.2) |
| World file opened (1.4) | `WorldPack.route` peeks for world.json → "Add the world …?" sheet; installs only on tap, replacing the same id; progress kept | OK (1.4) |
| Leaving the map / map hidden (1.4) | the scene's clock stops (no frame scheduled when detached or its window is hidden); pictures are dropped, never `recycle()`d, so a frame still on the render thread can't hit a recycled bitmap (BlueStacks crash) | OK (1.4) |
| Live sync while open (1.4) | every 10 ticks the ticker syncs each paired phone it knows the address of (15 s), only while the app is in front | OK (1.4) |
| Home lock off (1.4) | pairing and sync work on any Wi-Fi, still paired + encrypted + wrong-code lockout; no location needed | OK (1.4, Yaya's choice) |
| Location switched on later (1.4) | `Sync.announce` registers the phone on the network as soon as syncing is allowed | OK (1.4) |
| Midnight while the board is open (1.4) | past days lock on the next redraw | OK |
| Level up on silent / no vibrator (1.4) | chime skipped unless the ringer is normal; shake skipped without a vibrator; the flash restores the window brightness | OK |
| Quest abandoned (1.4) | its timers stop (a timer handed to Pistachio rings there until stopped there); no fame | OK |
| OCR reading (1.4) | runs on the phone (ML Kit); leaving during reading just drops the result | OK |
| Back gesture | `onBackPressed` pops the screen stack | move to `OnBackInvokedCallback` before targetSdk 36 |
| Editing a recipe on both phones at once | newest edit wins | OK (decided) |

## 7. Android compatibility

- minSdk 26; guard API 31+ exact alarms, 33+ notifications permission, 34+ photo picker / partial media access.
- `android.hardware.Camera` is deprecated (still works); move the scanner to Camera2 or CameraX when touching it.
- targetSdk 34 today. Before raising: 35 forces edge-to-edge (insets on every screen), 36 makes predictive back default (`onBackPressed` stops being called).
- Android 17+ may restrict local-network access behind a permission; sync and NSD must be re-checked on each new Android release.

## 8. Refactor log

Git history starts at 1.1. **No module has been refactored yet.** Snapshot 2026-10-17 (1.2); "changed" = lines changed 1.1→1.2.

| Module | Lines | Changed 1.1→1.2 | Edited in | Last refactored | Recommendation |
|---|---|---|---|---|---|
| Editor | 484 (+ EditorIngredients, EditorEquipment, EditorSteps, SaveChoices) | 1.4 | 1.0–1.4 | 1.4 (issue #9) | fine |
| MainActivity | 484 (+ Screen, Sheet, Crashes, FrictionTouch, Banner) | 1.4 | 1.0–1.4 | 1.4 (issue #9) | fine |
| Sync | 661 | most of it | 1.0–1.4 | 1.4 (rewritten for parties) | **Refactor next**: over 600 lines; split pairing/transport from merge |
| Model | 606 | 1.4 | 1.0–1.4 | never | **Refactor next**: over 600 lines; split `Store` from data classes (Migrate already apart) |
| MissionMap | ~290 | 1.4 | 1.2, 1.4 | 1.4 (scenery, effects, memory moved to Scene.kt) | fine |
| Scene (SceneView, SceneFx) | ~460 | new | 1.4 | never | Watch: move `SceneFx` to its own file if it grows past ~400 lines |
| WorldPack, Worlds | ~320, ~75 | new | 1.4 | never | fine |
| Widgets | 477 | 44 | 1.0, 1.1, 1.2 | never | Refactor (3 versions): move `Photos` cache into its own file |
| Quest | 424 | 5 | 1.0, 1.1, 1.2 | never | Refactor: separate timer UI from step flow |
| RecipeScreen | 430 | 19 | 1.0, 1.1, 1.2 | never | Watch (small edits) |
| Timers | 394 | 0 | 1.0, 1.1 | never | Watch |
| RecipeFiles, Missions, MissionScreen, HomeWifi, Resume | 48–202 | new | 1.2 | never | fine |
| others | < 330 | < 30 | | never | fine |

Thresholds: recommend a refactor when a module passes **600 lines**, has been edited in **3 versions since its last refactor**, or had more than **half its lines changed** in one version. Put the source in a git repository (recommended: a `praline-app` repo, private) so this table can use real history.

## 9. Security risks before a wider release

| # | Risk | Where | Severity | Fix before wider release |
|---|---|---|---|---|
| S1 | ~~Sync is unencrypted~~ | Sync.kt, SecureLink.kt | Fixed in 1.3 | Home Wi-Fi only (1.2); 1.3: fresh ECDH per sync, the pair code is never sent, both phones prove the 256-bit pairing secret, AES-256-GCM per direction with chunk counters. 1.4: the QR carries a 26-char (~128-bit) secret, the typed code is 12 chars (~60 bits) stretched with PBKDF2 (60k rounds), each phone has its own secret. Residual: someone on the same Wi-Fi at the exact pairing moment could still try typed-code guesses offline, now slow; a PAKE (e.g. SPAKE2) would close it before a wider release |
| S2 | ~~No attempt limit on the pair key~~ | Sync server | Fixed in 1.3 | 5 wrong codes or secrets in 10 minutes lock pairing and sync for 10 minutes. 1.4: typed code 12 chars |
| S3 | Signing key backup and password sit together in the project folder | signing-backup/ | Low (private project) | Keep, but never copy into git; use a password manager for a public release |
| S4 | Personal naming (`yaya`): full inventory in section 12 | manifest, all sources | Low | Rename before the first public release (section 12) |
| S5 | `allowBackup="true"`: cookbook, photos, chef profiles go to Android/Google backups | manifest | Low | `dataExtractionRules`, or false |
| S6 | Photos are stored with EXIF (location, phone model) and shared to the other phone as-is | Photos, Share | Low | Strip EXIF on import |
| S7 | `USE_EXACT_ALARM` needs store justification (Play allows it for alarm/timer apps) | manifest | Store policy | Switch to `SCHEDULE_EXACT_ALARM` + request flow if refused |
| S8 | ~~Source not in version control~~ | — | Done | Private `praline-app` repo (2026-10-18) |
| S9 | **Recipe files are untrusted input** (1.2): anyone can send a `.praline` file | RecipeFiles.read/add | Low | Done: zip names checked (flat, safe characters, .jpg/.mp4 only), caps of 80 MB per entry, 200 MB total, 600 entries, 4 MB JSON, 200 recipes; pictures renamed on import; nothing added without the chef's tap. Later: strip EXIF, check images decode before keeping them |
| S10 | **Location permission** (1.2), asked only to read the Wi-Fi name | HomeWifi | Low (privacy) | No location is read or stored, only the SSID; explained before asking. Store listing must say why |
| S12 | **World packs are untrusted input** (1.4): anyone can send a `.praline-world` file | WorldPack.read | Low | Done: whole pack ≤ 25 MB (copied with a cap before opening), ≤ 400 entries, entries ≤ 8 MB, names limited to world.json / slices/ / recipes/ / library/ with safe characters (`..`, `/`, `\` refused), world.json ≤ 1 MB, ids checked, numbers clamped, text capped (story 600), pictures decoded shrunk and refused past 2048 px wide, every recipe spot's file must read; installs only on the chef's tap. Later: pack signing if worlds are ever shared publicly |
| S13 | **Home lock off** (1.4, Yaya's choice): sync can run on any Wi-Fi (café, friend's) | Sync, HomeWifi | Low | Still paired-only, encrypted, wrong-code lockout; the phone announces itself (NSD name) on that network while open. Turn the lock back on in Settings to limit it |
| S14 | Wrong-code lockout is global: someone on the network sending wrong codes blocks the party's sync for 10 minutes | Sync | Low (nuisance) | Per-address limits before a wider release |
| S15 | **Cookbook photos read by OCR** (1.4) | Ocr, MlKitReader | None found | ML Kit's model is inside the app: no download, the picture never leaves the phone |
| S16 | **Release key in GitHub secrets** (1.4) | repository secrets | Low | Secrets are write-only and never printed; the workflow deletes the key file after the build. Keep the repo private and the backup in signing-backup/ |
| S11 | Mission recipe ids are fixed (`mission-<world>-N`), so a crafted sync payload could overwrite them | Sync merge | Low (needs the pair key) | Covered by S1's fix |

New risks are added here in the same change that introduces them, and named in that version's delivery reply.

## 10. Images and memory (strict)

Rules for every bitmap in the app:

1. **Never decode a full-size photo for display.** Decode bounds first (`inJustDecodeBounds`), then `inSampleSize` to the view's size (`Filters.load(file, maxSide)` does this — always use it, never `BitmapFactory.decodeFile` directly).
2. **One cache**: `Photos` LRU keyed by `name@maxSide`, sized from the device: `ActivityManager.memoryClass / 8` MB, 8–64 MB (1.2).
3. **Trim on pressure**: `onTrimMemory` and `onLowMemory` call `Photos.trim` (1.2) and `SceneView.trim` (1.4). Scene slices are decoded RGB_565, shrunk to the screen's width, only near the screen (one screen above and below), and dropped when the page hides. Scene pictures are dropped, never recycled: the render thread may still hold the last frame (1.4).
4. **Filters work on a preview-sized copy** (≤1280 px), and every intermediate bitmap (blur, bloom, graded) is `recycle()`d as soon as the next one exists. `Filters` recycles its intermediates (1.2).
5. **Thumbnails are saved once** (Clips thumb) and lists only ever load thumbnails.
6. Decode off the main thread (`Photos.pool`), deliver on the main thread, and drop the result if the view was recycled.
7. Use `RGB_565` for opaque photos in lists where the look allows it (half the memory).
8. **Recommended upgrade:** Coil 3 (memory + disk cache, downsampling, lifecycle-aware cancellation) once the toolchain can fetch AndroidX; Glide is the fallback. Until then, the rules above are the system.

## 11. Data rules

- Cookbook data: `praline.json` (atomic write). UI prefs: SharedPreferences `app` (DataStore when AndroidX is available).
- No CSV at runtime. Balancing numbers live as constants in `Fame` / `Classes` / `Tiers` / `Equipment`; when Yaya updates the CSV, Claude translates the change into those constants and says which values moved.

## 12. Personal naming to rename later (Yaya, 2026-10-17)

Changing the package id makes Android treat Praliné as a **new app** (no update over the old one, data must be exported first, both phones re-paired). Rename together with Pistachio.

| What | Today | Where |
|---|---|---|
| Application id / package | `com.yaya.praline` | app/AndroidManifest.xml, every `package` line in src/main and src/test |
| Kotlin source folders | `src/main/kotlin/com/yaya/praline/`, test folders | folder names |
| Photo provider authority | `com.yaya.praline.photos` | manifest, Camera.kt (`PhotoProvider`) |
| Timer actions | `com.yaya.praline.TIMER_DONE`, `TIMER_PAUSE`, `TIMER_PLUS`, `TIMER_RESET`, `TIMER_RESUME`, `TIMER_STOP` | Timers.kt |
| Pistachio contract | `<queries><package android:name="com.yaya.timer"/>`, `com.yaya.timer.ROUNDS`, `com.yaya.timer.extra.ROUNDS` | manifest, Timers.kt |
| Comments naming Yaya | Filters.kt ("after Yaya's isometric diorama references"), Sync.kt (example toast) | comments only |
| Signing alias | `praline` (neutral) | signing-backup/release.jks |

Rule until then: no new `yaya` strings; action names built from one package-prefix constant.
