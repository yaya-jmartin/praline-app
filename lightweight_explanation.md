# Praliné, explained lightly

This file explains, without jargon, every building block Praliné uses: what it is, what it does for the app, and why it was chosen over other options. It is updated whenever a building block is added, removed or swapped.

**A quick picture first.** An Android app is code, plus pictures, sounds and fonts, all packed into one installable file, the **APK**. Building it is like a kitchen: ingredients (code and libraries), a recipe book (the build script), appliances (the tools that cook and pack) and a taste test (automated tests).

One constraint shapes most of the choices below. The computer Claude builds on can't download anything from Google's servers, where the official Android tools and many popular libraries live. The plan is to move the build to GitHub Actions, where they're reachable (see "Coming later").

---

## What's inside the app

| Building block | What it is | What it does for Praliné | Why this one |
|---|---|---|---|
| **Kotlin** (language, version 2.1) | The language the code is written in | Every screen, recipe, timer and sync step | Google's recommended language for Android: shorter and safer than Java |
| **Android's built-in toolkit** | What every Android phone already has: screens, camera, notifications, alarms, Wi-Fi | Draws the hand-inked screens, runs the timers, opens the camera | Already on the phone, so the app stays small. The add-on toolkits (AndroidX, Compose) are on Google's servers |
| **ZXing "core"** (version 3.5.3) | A free, widely used QR-code library, the classic one many scanner apps use | Draws the pairing QR code and reads it from the camera | It's one small piece with no other dependencies, and it's published where we can reach it. Google's ML Kit scanner is better at reading blurry codes but needs Google's servers and Play Services |
| **Mea Culpa and Fraunces fonts** | Free fonts (open licence) | Mea Culpa for the script titles, Fraunces for the body text | The free stand-in for Raillouise (which isn't free to bundle), and a warm, bookish serif that suits the D&D cookbook look |
| **A file on the phone** (`praline.json`) | The cookbook saved as one text file | Holds recipes, variants, chefs, proposals and cooking history | Simple, and easy to send to the other phone. It's written safely: a broken file is kept aside, never overwritten. A database (Room) would be the next step if the cookbook grows very large |
| **Phone storage for settings** (SharedPreferences) | A small notebook the app keeps | Remembers the home tab, sorting and other small choices | Built in. Jetpack DataStore replaces it later |
| **The phone's photo tools** (BitmapFactory, a memory cache) | Built-in picture loading | Opens photos at the size the screen needs, keeps recent ones ready, applies the dungeon-light filters | The image library Coil is on Google's servers, so the proven manual technique is used instead: open a smaller copy, cache a limited amount. The strict rules are in COMMAND_FILE_CLAUDE.md |
| **The phone's camera** (old camera API, plus the system camera app) | Camera access | The live view for QR scanning, and the system camera for recipe photos and clips | The old API is built in. CameraX (modern, sharper focus) is on Google's servers |
| **Wi-Fi sync** (sockets + local network discovery) | Two phones talking directly on your home network | Pairing and keeping one shared cookbook | No server, no account, no cost, and your data never leaves home. Since 1.2 it only syncs on your saved home Wi-Fi. Encryption comes before any wider release |
| **Pistachio hand-off** (Android "set a timer" message) | A standard Android message to timer apps | Sends recipe timers to Pistachio | Uses Android's own standard instead of a private link between the apps. If the hand-off is off, Praliné runs its own timers |

## New in 1.2 (no new libraries; all built from what the phone already has)

| Building block | What it is | What it does for Praliné | Why this one |
|---|---|---|---|
| **Recipe files** (`.praline`, a zip file) | A recipe and its pictures packed into one file, like a parcel | Lets you send a recipe to a chef outside the household through any chat app, and open one they send you | Works with any app that sends files, needs no server or account. Live sync with friends far away would need an online relay, planned separately |
| **Android's share sheet and "open with"** | The standard "Send to…" and "Open with…" menus | Sends the recipe file, and lets Praliné open one from WhatsApp, Discord, mail or Files | Built in, familiar, nothing to install |
| **Wi-Fi name check** (needs the location permission) | Android only tells apps the Wi-Fi's name if they have location access | Recognises your home Wi-Fi so the phones sync only there | It's Android's rule, not a choice: the name could reveal where you are. Praliné reads only the name, never your location |
| **Painted map slices** | The world map cut into six pictures stacked top to bottom | Shows The Sunforge Sands; only the pieces near the screen are kept in memory | One giant picture would use too much memory on smaller phones |
| **Live drawing on the map** | The phone draws on top of the pictures, many times a second | Sun rays, the Calice's beam, forge sparks, the lit trail, the dusk ahead, the spots and the two chefs | Moves and changes with your progress without needing a new picture for every state |

## The tools that build the app (not inside it)

Same chain as Pistachio:

| Tool | Kitchen comparison | What it does |
|---|---|---|
| **Gradle** | The recipe book | Runs every build step in order |
| **android-all (from Robolectric)** | A copy of the phone's toolkit | Lets the code be checked as if the official Android tools were there |
| **ProGuard** | Trimming the vegetables | Removes unused code, which makes the APK smaller |
| **dx** and **aapt2** (from Debian) | Translating and packing | Turn the code into the phone's format and pack fonts, icons and pictures |
| **zipalign and apksigner** | Sealing and signing | The signature (key "praline") proves updates come from you, so they install over the old app |

## The taste test (automated tests)

| Tool | What it does | Why this one |
|---|---|---|
| **JUnit** | The basic tester: "do this, check that" | The standard |
| **Robolectric** | Pretends to be an Android phone on a computer, tests screens and takes the screenshots used to check the design | The only way to test Android behaviour here without Google's emulator |

## Coming later (approved order, each step checked on your phones)

| Building block | What it will do | Why |
|---|---|---|
| **GitHub Actions** | Builds the app on GitHub's computers, which can reach Google's servers | Unlocks everything below, plus automatic builds. Publishing always waits for your OK |
| **Coil** (image library) | Loads, shrinks and caches photos automatically, and cancels loading for screens you've left | Kotlin-first, light, the current standard for Android. Glide is the older, also-proven alternative |
| **CameraX** | Modern camera access: sharper focus, works the same on all phones | Fixes the root cause of blurry QR scans, and is Google's recommended camera library |
| **Jetpack DataStore** | Safer storage for settings | Google's recommended replacement for SharedPreferences |
