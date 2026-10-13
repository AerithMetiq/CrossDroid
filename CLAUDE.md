# CLAUDE.md

Guidance for Claude Code working in this repository. Read it before changing anything.

## What this is

**CrossDroid**: an unofficial Android launcher that plays **CrossCode** (Radical Fish Games, Steam app
**368340**) on Android. The user signs in to Steam, the app downloads the copy their account owns,
runs it in a WebView with the CCLoader mod loader, and syncs the save with Steam Cloud. It is a fork
of dain98's CrossDroid (`upstream`), itself forked from Namnodorel's CrossAndroid; `origin` is
AerithMetiq's fork. Package `de.radicalfishgames.crosscode`.

Its sibling is **Alabaster Droid** (`~/AlabasterDroid`, Alabaster Dawn), written later by the same
pair. In October 2026 CrossDroid was brought up to its feature set (see "Parity with Alabaster
Droid"). Alabaster Droid's CLAUDE.md holds the longer notes on Steam, JavaSteam and the Retroid
Pocket Nova; read it when a Steam question comes up here.

## Who does what

- **Claude writes the code; AerithMetiq reviews and tests it** on real devices (Retroid Pocket Nova,
  AYN Odin 3; dain98 develops on an AYN Thor). AerithMetiq is not an Android developer: give exact,
  copy-pasteable steps, one thing at a time, and say what output to expect.
- Claude also builds, installs and tests on the Nova itself over wireless adb when it is reachable
  and unlocked (never try to get past the lock; the port changes each time).
- **Commit attribution:** Claude is the commit author: `Claude <noreply@anthropic.com>`. Work has
  been on the branch `steam-achievements`.
- **Never push, force-push, rewrite published history or publish a release** unless AerithMetiq
  asks for that specific action in the session. Commit only when asked.

## Rules

1. **Secrets stay secret.** Never log or display the Steam password, refresh token or Steam Guard
   data. (The refresh token is still stored in plain preferences here; moving it to the Android
   Keystore, as Alabaster Droid's `LoginStore` does, is an open item.)
2. **Writes to Steam are deliberate.** Uploading the save, unlocking achievements and the playing
   status each have a switch; the playing status is off by default, achievements have a dry run, and
   "Check the save" is the save sync's dry run.
3. **The game's files are the user's.** CrossDroid ships a little CrossCode art (launcher logo,
   background, icon), which dain98 chose and the README credits; add no more game files. Game files
   copied off a device for debugging stay outside the repo.
4. Credits in the README (CrossAndroid, CCLoader, cc-font-fix, JavaSteam) must stay accurate.

## Build and test

```sh
bash gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk (gradlew is not executable)
bash gradlew :app:testDebugUnitTest    # SaveSyncTest, SaveBackupsTest, FrameRateTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s CrossCode                # the app's log tag
```

AGP 8.5.2, Kotlin 2.2.20, Gradle 8.7, compileSdk/targetSdk 34, minSdk 21 (guard API 23+ calls),
**JavaSteam 1.6.0** (not Alabaster Droid's 1.8.0: the depot downloader's raw/zstd chunk handling was
written against 1.6.0, and `PersonaStatesCallback` / `getGameAppID` are the 1.6.0 names).
WebView debugging is on: `adb forward tcp:9223 localabstract:webview_devtools_remote_$(adb shell pidof de.radicalfishgames.crosscode)`.

## Layout

```
app/src/main/java/de/radicalfishgames/crosscode/
  SteamLoginActivity.kt   the launcher: sign-in, PLAY (sync, then game), update prompt, download mirror
  GameActivity.kt         hosts the WebView; pause menu on Back; save upload on quit
  GameFiles.kt            where the game and save live, install marker, settings accessors
  GameDownloadService.kt  download / update / verify as a foreground service (+ GameDownloadState)
  FrameRate.kt            pure: frame-rate limits and the screen refresh rate for one (unit-tested)
  features/               one class per in-game feature (CrossAndroid's design)
    FrameRateFeature.kt   the requestAnimationFrame gate
    VirtualControllerFeature.kt  touch overlay; `show()` is the pause menu's switch
    CloudSaveFeature.kt   seeds cc.save into localStorage at page start, writes it back on pause
  preferences/PreferencesActivity.kt  Settings: also save check, restore, verify, delete
  steam/SteamCloudSync.kt   one Steam session: logon, profile, Cloud pull/push, build id, playing status
  steam/SteamPresence.kt    keeps the playing status up while the game is on screen (opt-in)
  steam/SaveSync.kt         pure: the three-way decision, the dry-run report, the status line (unit-tested)
  steam/SaveBackups.kt      cloudsave/backups/<stamp>/cc.save: backup, list, restore, prune (unit-tested)
  steam/SteamDepotDownloader.kt  the CDN download; `verify` hashes files, `cancel()` stops it
  steam/SteamAchievements.kt, AchievementSync.kt  achievements over the same session
```

## How the pieces work

- **The save** is one file, `cloudsave/cc.save`, byte-identical to the desktop save. The game keeps
  it in the WebView's localStorage; `CloudSaveFeature` copies it in at page start and out on pause.
- **Sync** (`SteamLoginActivity.play`, decisions mirrored in `SaveSync.decide`): three-way against
  `lastSyncedSha` (preferences file `steam`). Only one side changed: that side wins. Both: the user
  picks. Every local save that gets replaced is backed up first (`SaveBackups`); when this device's
  save wins a conflict, Steam Cloud's is kept as a backup tagged `cloud`.
- **Uploads switch** (`steam_cloud_uploads`, default on, as CrossDroid always uploaded): off makes
  PLAY and quit download-only. Choosing this device's save in a conflict with uploads off records
  Steam Cloud's checksum as the baseline, so the question is not asked again until the Cloud changes.
- **Download service**: one run downloads, resumes, updates or verifies. `installed.txt` in the game
  folder records the Steam build. A run that finds another build on Steam than the installed one
  writes `updating.txt` (the game is then not "installed") and hashes every file; it is removed when
  the run finishes. Installs from before this have no marker, so no update is offered until a
  download or verify has run once.
- **Steam sessions** use a `loginID` per purpose (`SteamCloudSync.Purpose`, and the downloader's own)
  so they can overlap, and `SteamCloudSync.open` retries a logon answered "busy / try another server".
- **Profile picture**: fetched on every launcher visit and replaced when its hash changes. The old
  code went Online and took the first persona callback, which often had no avatar yet: Steam's
  default picture was saved and never replaced. Do not save a default picture.
- **In-game menu** (`PauseMenuView`, opened by `GameActivity.openMenu`): a panel on the right in
  the style of Alabaster Droid's side menu (our own code, not a copy of alabaster-android's view):
  Touch controls, Limit the frame rate, Rate, Resume, Exit. Opening it dispatches `blur` to the page
  (CrossCode's own focus-lost pause; `ig.system.hasFocusLost()` is true while it is open); closing
  dispatches `focus`. Nothing in it takes view focus: `GameActivity.menuKey` / `dispatchGenericMotionEvent`
  hand it D-pad, stick, A and B.
- **Back** is acted on in `dispatchKeyEvent` whatever device sent it. Upstream dropped every Back
  from a gamepad source, to ignore the Back the system makes out of an unhandled B; on the Retroid
  Pocket Nova the real Back button belongs to the built-in gamepad, so Back never worked there. A
  B-made Back is now recognised by timing (it follows a `KEYCODE_BUTTON_B` within 300 ms); it does
  **not** carry `FLAG_FALLBACK` on the Nova, which was tried first.
- **One launcher instance**: `SteamLoginActivity` is `singleTop` and the download notification uses
  the package's launch intent. A second launcher stacked on the first made the first Back look like
  a stutter (starting the activity by component from adb also stacks one; use
  `adb shell monkey -p de.radicalfishgames.crosscode -c android.intent.category.LAUNCHER 1`).
- **No notification permission** (Android 13+, the user can decline): the download still runs as a
  foreground service, but nothing shows outside the app. The launcher's caption says so.
- **Frame-rate limit**: `FrameRateFeature` wraps `window.requestAnimationFrame` once the game has
  loaded. That is enough because the engine calls it by name every frame
  (`window.requestAnimationFrame(ig.system.run.bind(ig.system), ...)`). The gate keeps a running
  deadline, so 45 on a 60 Hz screen averages 45. `GameActivity.applyRefreshRate` asks for the screen
  mode a limit divides evenly.
- **Touch controls switch** (`touch_controls`): off means no overlay at all. The pause menu can hide
  or show an overlay that was set up at start; if it was not (a controller was connected), the change
  applies from the next start, and turning it on with a controller connected also sets `force_virtual`.

## Parity with Alabaster Droid (2026-10-12)

Ported, adapted to this code base: background download with notification, update check, verify and
delete game files, save backups and restore, the dry-run save check, the uploads switch, the "synced
… ago" line, the opt-in playing status, busy-logon retry and per-purpose login ids, the pause menu
with frame-rate limits and the touch-controls switch, grouped Settings. Not ported: Alabaster Droid's
gold status bar (AerithMetiq did not want the colour change) and its encrypted login store.

Checked on the Retroid Pocket Nova that day: the profile picture now loads; the save check; PLAY
downloading a newer Cloud save with a backup made; the menu pausing the engine; a 30 fps limit
measured at 30 over the debugger; quit and upload; Back and B with gamepad-source keys; a full
1.6 GB download finishing in the background after delete game files (AerithMetiq's run); the
playing status (AerithMetiq). **Not yet run on a device:** an update, verify with a damaged file,
cancel, restore a backup, the uploads-off paths, and the touch-controls switch without a controller.
