# COMMAND_FILE_CLAUDE.md — Praliné

Strict working rules for Claude on this app. Read this file in full before answering, planning or changing anything.
If this file and a chat message disagree, Yaya's latest message wins and this file gets updated in the same change.
Repository: `yaya-jmartin/praline-app` (private). This file lives at its root.

App: **Praliné**, package `com.yaya.praline`, current version **1.2** (versionCode 3). Source: this repository (history from 1.1 on).

---

## 1. Rules of engagement (Yaya, 2026-10-17)

1. **Never build unless Yaya says so**, even for a fix. Questions get answers, suggestions get discussed. Every feature or bugfix Yaya asks for goes on the pending list for the next version (project memory `praline-next-version`; patches waiting in `/mnt/project-files/praline/pending/`) and is confirmed in the thread.
2. **Ship nothing half done.** Before a build, re-read the Praliné thread for every ask since the last release.
3. **Plan mode first.** The app is big enough (≈8,900 lines of Kotlin in 32 files): before any file changes, write the plan (files touched, lifecycle events affected (section 6), new security risks (section 9), tests to add), then edit.
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
14. **Test every version**: `gradle test testShrunk` (LogicTest, V11Test, V12Test, ScreensTest + new tests per change; tests read assets from build/apk/base.apk, so build the APK first; tests that sync set `HomeWifi.assumeHome = true`) once on the final build, after building the APK; check the screenshots in build/shots.

## 2. Tech stack (exact)

| Layer | What is used | Notes |
|---|---|---|
| Language | Kotlin 2.1.21, JVM target 1.8 | same SDK-less toolchain as Pistachio |
| Android API | Plain framework, **no AndroidX / Jetpack / Compose** | Google Maven blocked in the build container |
| SDK levels | minSdk 26, targetSdk 34 | |
| Libraries | `com.google.zxing:core:3.5.3` (QR make + read) | the only dependency |
| UI | Views built in code (`Screen` subclasses in one `MainActivity`), custom drawables (`Ink`, `InkBox`, `PaperDrawable`, `TornEdge`), fonts Mea Culpa (titles) and Fraunces (body) | no `R` class |
| Storage | `filesDir/praline.json` (cookbook, chefs, proposals; atomic write via `.tmp`, broken file kept as `praline.broken.json`), `filesDir/photos/`, SharedPreferences `app` for UI prefs | |
| Camera | `android.hardware.Camera` (deprecated API) + `TextureView` for the QR scanner (preview size from `pickPreviewSize`: sharpest ≤1080p; refocus every 1.5 s; two binarizers + inverted); system camera intents for photos/clips via `PhotoProvider` | |
| Sync | Same-Wi-Fi TCP socket (`ServerSocket`), NSD (DNS-SD) discovery, protocol v2, JSON payloads; only on the saved home Wi-Fi (`HomeWifi`, SSID via location permission) | no server, no account |
| Recipe files | `.praline` zip (praline.json + photos/) shared through the share sheet, opened via VIEW/SEND intents (`RecipeFiles`) | for chefs outside the household |
| Missions | `Missions` (logic, world list), `Templates` (placeholder recipes), `MissionMap` (painted slices in assets/missions/<world>/ + live effects), `MissionScreen` | |
| Timers | `AlarmManager` + `TimerReceiver`, `BootReceiver`; hand-off to Pistachio with `ACTION_SET_TIMER` | |
| Build / tests | Gradle → ProGuard → dx → aapt2 → apksigner; Robolectric 4.14.1 | signing alias `praline`, key + password only in `signing-backup/`, never in git or memory |

Upgrade path (suggest when Google Maven is reachable): Coil 3 for images, CameraX for the scanner, Jetpack DataStore for prefs, Room if the cookbook outgrows one JSON file.

## 3. Architectural patterns

- **Single activity, screen stack**: `MainActivity` holds a stack of `Screen` objects (Home, Recipe, Editor, Quest, Photo, Profile, Pair, Board, Settings, Mission). `Screen.saveState()` + `Resume` bring the editor draft or quest step back after Android kills the process. `Sheet` is the shared pop-up.
- **One store**: `Store` (Model.kt) owns `Recipe` / `Variant` / `Step` / `Ingredient` / `Chef` / `Proposal` / `CookEntry`, loads and saves `praline.json`. Screens never write files directly.
- **Shared-cookbook rules**: newest edit wins per recipe, deletions stick, edits to shared recipes travel as proposals (outbox/inbox/answers), My Variant specials are per phone (index 100+k).
- **Game layer**: `Fame` (levels, feats, favourite weapon), `Classes`, `Tiers`, `Equipment`, `Fate` (d20), `Missions` are pure logic objects with tests.
- **Party missions**: progress is never stored, only derived: walk both chefs' `CookEntry` history since `Store.missions[world]` (start time, earliest wins on sync) in time order; a spot is done when its recipe (fixed id `mission-<world>-N`) is cooked after party XP ≥ the spot's threshold. Reached spots add their recipe to the grimoire once (`Missions.unlock`, respects tombstones). A new world = templates + `World` entry + painted assets folder with `map.json`.
- **Shared UI kit**: Ui.kt (`C` colours, `F` fonts), Ink.kt, Widgets.kt (`FlowLayout`, `RoundPhoto`, `ServingsWheel`, `Slider`, `TimerRing`, portraits).

## 4. Coding style

- Kotlin, 4-space indent, long lines allowed, match the file. KDoc on public functions in terms of what the user sees.
- Names: screens `XxxScreen`, logic objects nouns (`Store`, `Sync`, `Timers`).
- Test code must not call stdlib helpers main code doesn't use (`testShrunk` breaks).
- Look: cream paper, wobbly ink outlines, colourful offset shadows, D&D tone; no instructional hint text.
- Every version: versionCode +1, APK `Praline-x.y.apk`, screenshots folder, source pushed to this repository through a PR (a source tarball in `/mnt/project-files/praline/` too, until builds run on GitHub Actions).

## 5. Features (1.2)

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
| Low memory (`onTrimMemory`) | `Photos.trim` empties the cache at UI_HIDDEN and above, halves it at RUNNING_LOW; mission map drops its slices when hidden | OK (1.2) |
| Left home Wi-Fi | server answers "not on your home Wi-Fi", auto-sync and NSD stop; Pair shows the home-first card | OK (1.2) |
| Location permission denied | Wi-Fi name unreadable, so no sync until allowed (explained on the Home dungeon access card) | OK (1.2) |
| Recipe file opened while Praliné runs | `onNewIntent` → "Add …?" sheet | OK (1.2) |
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
| Editor | 921 | 91 | 1.0, 1.1, 1.2 | never | **Refactor now** (over 600 lines, edited in 3 versions): split ingredients, steps, equipment, photo sections |
| MainActivity | 837 | 37 | 1.0, 1.1, 1.2 | never | **Refactor now** (over 600 lines, 3 versions): move `Sheet`, `Screen` base, crash and gesture handling out |
| MissionMap | 556 | new | 1.2 | never | Watch: split drawing layers (scenery, effects, spots, party) when a second world is added |
| Widgets | 477 | 44 | 1.0, 1.1, 1.2 | never | Refactor (3 versions): move `Photos` cache into its own file |
| Model | 465 | 16 | 1.0, 1.1, 1.2 | never | Refactor (3 versions): split `Store` from data classes |
| Sync | 454 | 9 | 1.0, 1.1, 1.2 | never | Refactor with the security fix (S1): protocol, transport, discovery |
| Quest | 424 | 5 | 1.0, 1.1, 1.2 | never | Refactor: separate timer UI from step flow |
| RecipeScreen | 430 | 19 | 1.0, 1.1, 1.2 | never | Watch (small edits) |
| Timers | 394 | 0 | 1.0, 1.1 | never | Watch |
| RecipeFiles, Missions, MissionScreen, HomeWifi, Resume | 48–202 | new | 1.2 | never | fine |
| others | < 330 | < 30 | | never | fine |

Thresholds: recommend a refactor when a module passes **600 lines**, has been edited in **3 versions since its last refactor**, or had more than **half its lines changed** in one version. Put the source in a git repository (recommended: a `praline-app` repo, private) so this table can use real history.

## 9. Security risks before a wider release

| # | Risk | Where | Severity | Fix before wider release |
|---|---|---|---|---|
| S1 | **Sync is unencrypted**: the 8-character pair key and the whole cookbook (recipes, photos, chef names) travel in plain JSON over Wi-Fi; anyone on the same network can read them, and with the key, pull or push changes | Sync.kt | **Medium** (fine at home; not on shared Wi-Fi) | Next version (Yaya, 2026-10-17): sync only runs on the saved home Wi-Fi, recognised by its network name (location permission asked once, only to read the Wi-Fi name), changed in Settings → "Home dungeon access". Before a wider release, also encrypt (TLS or a cipher keyed from the pairing secret) and replace sending the key with a challenge-response |
| S2 | Pair key is 8 chars from 31 symbols (~40 bits) and the server has no attempt limit | Sync.newKey, server hello | Low | Rate-limit failed hellos; longer key in the QR |
| S3 | Signing key backup and password sit together in the project folder | signing-backup/ | Low (private project) | Keep, but never copy into git; use a password manager for a public release |
| S4 | Personal naming (`yaya`): full inventory in section 12 | manifest, all sources | Low | Rename before the first public release (section 12) |
| S5 | `allowBackup="true"`: cookbook, photos, chef profiles go to Android/Google backups | manifest | Low | `dataExtractionRules`, or false |
| S6 | Photos are stored with EXIF (location, phone model) and shared to the other phone as-is | Photos, Share | Low | Strip EXIF on import |
| S7 | `USE_EXACT_ALARM` needs store justification (Play allows it for alarm/timer apps) | manifest | Store policy | Switch to `SCHEDULE_EXACT_ALARM` + request flow if refused |
| S8 | ~~Source not in version control~~ | — | Done | Private `praline-app` repo (2026-10-18) |
| S9 | **Recipe files are untrusted input** (1.2): anyone can send a `.praline` file | RecipeFiles.read/add | Low | Done: zip names checked (flat, safe characters, .jpg/.mp4 only), caps of 80 MB per entry, 200 MB total, 600 entries, 4 MB JSON, 200 recipes; pictures renamed on import; nothing added without the chef's tap. Later: strip EXIF, check images decode before keeping them |
| S10 | **Location permission** (1.2), asked only to read the Wi-Fi name | HomeWifi | Low (privacy) | No location is read or stored, only the SSID; explained before asking. Store listing must say why |
| S11 | Mission recipe ids are fixed (`mission-<world>-N`), so a crafted sync payload could overwrite them | Sync merge | Low (needs the pair key) | Covered by S1's fix |

New risks are added here in the same change that introduces them, and named in that version's delivery reply.

## 10. Images and memory (strict)

Rules for every bitmap in the app:

1. **Never decode a full-size photo for display.** Decode bounds first (`inJustDecodeBounds`), then `inSampleSize` to the view's size (`Filters.load(file, maxSide)` does this — always use it, never `BitmapFactory.decodeFile` directly).
2. **One cache**: `Photos` LRU keyed by `name@maxSide`, sized from the device: `ActivityManager.memoryClass / 8` MB, 8–64 MB (1.2).
3. **Trim on pressure**: `onTrimMemory` and `onLowMemory` call `Photos.trim` (1.2). Mission map slices are decoded RGB_565, only near the screen, and released when the page hides.
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
