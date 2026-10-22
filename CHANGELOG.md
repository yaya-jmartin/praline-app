# Praliné version log

One entry per version: name, versionCode, date, and what it brought. The newest version is at the top.

## 1.4 (versionCode 5) · 2026-10-22
- **Parties of up to four chefs:**
  - pair up to three other phones; chefs you meet through another phone join the party too;
  - offers and answers on shared recipes travel to every chef until everyone has answered (or a month passes);
  - phones sync every 15 seconds while Praliné is open on both, and sync errors now say what went wrong.
- **Safer pairing:** the QR code carries a long secret, and the typed code is 12 characters (3 groups of 4), slowed down against guessing.
- **Home Wi-Fi lock is optional:** sync stays paired, encrypted and limited to five wrong codes. Phones that saved a home keep the lock on.
- **Mission invites:** one chef sets off and invites the others, who accept or decline. Each spot sits further away with more chefs, and only the spots not reached yet stretch when someone joins.
- **Map scene:** worlds are now `.praline-world` packs from the map editor. You can open one from a file, story stops tell their story, and one figure walks per chef. The BlueStacks crash when leaving the map is fixed.
- **Beverages:** a new category next to Meals and Desserts.
- **Recipes:**
  - read a recipe from a photo of a cookbook page (GitHub build);
  - frame the photo exactly as the recipe page shows it, by dragging and pinching;
  - drag ingredients to reorder them, and paste a whole method as steps;
  - a recipe needs an ingredient, a step, and its equipment (or "No equipment needed") before saving;
  - a new variant starts from the main recipe;
  - "to taste" sits under the ingredient name; step timers stay in the quest.
- **Quests:** abandon a quest (its timers stop, no fame). Levelling up shakes the phone, flashes the screen white and rings a chime.
- **Quest board:** past days are locked, and a shopping list covers today to Sunday, ready to share.
- **Fixes:** the picked portrait is clearly marked, tier letters are centred, the Settings button is gone from Home (the swipe stays), the title no longer gets cut off, and swiping out of Settings no longer flickers.
- **Updating:** every phone in the party needs 1.4 to sync; a 1.3 phone is told to update. Missions already under way keep their progress.

## 1.3 (versionCode 4) · 2026-10-18
- **Encrypted sync between the two phones:**
  - every sync gets fresh keys (elliptic-curve key exchange), and everything is sealed with AES-256-GCM;
  - the pairing code is never sent over the Wi-Fi;
  - phones paired on 1.2 move over by themselves.
- **Wrong-code limit:** five wrong pairing codes in 10 minutes lock pairing for 10 minutes.
- **Updating:** both phones need 1.3 to sync. A 1.2 phone is told to update.
- **Automatic GitHub build:** every change is built with Google's official Android tools, and the tests and Android Lint run there.

## 1.2 (versionCode 3) · 2026-10-17
- **Party missions:** World I "The Sunforge Sands" has 10 must-cook spots ending at The Calice. The party's XP comes from both chefs' cooking.
- **Recipe files (`.praline`):** send a recipe with its pictures to a chef outside the household, or open one they sent.
- **Home Wi-Fi only:** sync and pairing work only on the saved home Wi-Fi (Settings → Home dungeon access).
- **Equipment:** equipment counts ("2 × Mixing bowl"), a new mixing bowl, and the Kenwood and KCook as engineer limbs.
- **Steps:** a × removes a step's picture, clip or timer.
- **Fixes and safety:**
  - sharper QR scanner;
  - portrait only;
  - the editor draft and the current quest step survive Android closing the app.

## 1.1 (versionCode 2) · 2026-10-17
- **Shared recipes:** edits to a shared recipe are sent as proposals the other chef accepts or declines.
- **My Variant:** each phone keeps its own variant of a recipe.
- **Settings:** a swipe on home opens Settings.
- **Friction log:** a two-finger hold saves an entry with a note, and Settings sends the log to Discord.
- **Chef portraits:** new portraits unlock at levels 3, 5, 8, 12 and 16.
- **Timers:** handed to Pistachio when the Settings toggle is on.
- **Updating:** the sync protocol changed to version 2, so both phones need 1.1.

## 1.0 (versionCode 1) · 2026-10-16
- **Home:** tier list (S/A/B–D), sorting, the d20 Fate pick.
- **Recipes:** D&D stat block, variants, servings wheel with scaled quantities, equipment.
- **Editor:** ingredients, steps with timers, photos and clips.
- **Quest (cooking mode):** step-by-step cooking with step timers and rewards.
- **Photo filters:** Torchlight, Grotto, Mimic, Tavern and Moonlit, with a strength slider; the original photo is kept.
- **Chef character sheet:** stats, favourite weapon and feats.
- **Quest board:** time and energy suggestions, and a week planner.
- **Two phones:** they pair once by QR code on the same Wi-Fi and share one cookbook, with no account.
