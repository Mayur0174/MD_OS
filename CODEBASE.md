# MD OS — Codebase Guide

This file explains every part of the MD OS code: what each file does, how the pieces connect, and where to change things.

---

## 1. What the app is

MD OS is one Android app (package `com.mdos.auto`) with two faces:

| Face | Where it shows | Entry point |
|---|---|---|
| **Car screen** | Android Auto display in the Hyundai Venue | `car/MdCarAppService.kt` |
| **Phone app** | The phone itself: setup, sign-in, passenger player | `MainActivity.kt` |

Both run in the **same app process**, so they share one cookie jar (sign in on the phone, and the car is signed in too).
`CarLink` lets the phone reach the car screen.

---

## 2. How video gets onto Android Auto (the core trick)

```
Android Auto host (car)
   │  gives a raw drawing Surface (because MD OS declares the NAVIGATION category)
   ▼
CarRenderer  ── creates a private VirtualDisplay on that Surface
   │            shows a Presentation window on that display
   ▼
CarUi (inside the Presentation): launcher + WebView (browser engine) + overlays
   ▲
   │  taps/drags/flings/pinches on the car screen → SurfaceCallback.onClick/onScroll/onFling/onScale
TouchInjector ── turns them into real finger MotionEvents on the window
```

- Normal Android Auto apps can only use list/grid templates (no video). Navigation apps get a Surface, which MD OS
  uses to draw anything, including video.
- `NavigationTemplate` (in `MainScreen`) supplies Android Auto's own buttons on top of our drawing.
- SpeedMonitor decides parked or driving; CarUi covers video while driving (sound continues).

---

## 3. File-by-file

### Build & project files

| File | What it contains |
|---|---|
| `settings.gradle.kts` | Project name, the `:app` module, repositories (Google, Maven Central). |
| `build.gradle.kts` (root) | Plugin versions: Android Gradle Plugin 8.7.3, Kotlin 2.0.21. |
| `gradle.properties` | Gradle JVM memory, AndroidX on, non-transitive R classes. |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/*` | Gradle wrapper (Gradle 8.11.1); build with `./gradlew assembleRelease`. |
| `app/build.gradle.kts` | App config: `applicationId com.mdos.auto`, minSdk 26 (Android 8), target/compile SDK 35, version 1.1. Signing with `mdos.keystore`. Dependencies: Car App Library 1.4.0 (`app` + `app-projected`), AndroidX WebKit 1.12.1, Core-KTX, Activity. Test dependencies (AndroidX Test). |
| `app/mdos.keystore` | Fixed signing key (not secret) so each new APK installs over the old one and keeps sign-ins. Password `mdos-pass`, alias `mdos`. |
| `.github/workflows/build.yml` | CI. **build** job: builds release APK → artifact + publishes it as the `latest` GitHub release. **device-test** job: boots an Android 14 emulator, runs `CarScreenTest`, publishes screenshots/report as the `test-report` release. |
| `.gitignore` | Ignores build outputs, IDE files, `local.properties`. |
| `README.md` | User guide: install, Android Auto setup, controls, limits. |
| `CODEBASE.md` | This file. |

### Manifest & resources (`app/src/main/`)

| File | What it contains |
|---|---|
| `AndroidManifest.xml` | Permissions: Internet, location (fine/coarse), `NAVIGATION_TEMPLATES` + `ACCESS_SURFACE` (Android Auto surface), `CAR_SPEED`. Declares `MdCarAppService` with category `NAVIGATION`, `minCarApiLevel 1`, the `automotive_app_desc` metadata, and the activities `MainActivity` (launcher) and `LoginActivity`. |
| `res/xml/automotive_app_desc.xml` | Tells Android Auto this is a template (Car App Library) app. |
| `res/values/strings.xml` | App name "MD OS". |
| `res/values/themes.xml` | Dark theme `Theme.MdOs`, colors (`launcher_bg`). |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive app icon (background color + foreground vector). |
| `res/drawable/ic_launcher_fg.xml` | Icon artwork: blue screen with a play triangle. |
| `res/drawable/ic_home.xml`, `ic_back.xml`, `ic_keyboard.xml`, `ic_play_pause.xml`, `ic_up.xml`, `ic_down.xml` | White vector icons for Android Auto's buttons. |

### Shared code (`app/src/main/java/com/mdos/auto/`)

#### `Services.kt` — the app catalogue
- `enum Kind { VIDEO, MUSIC, WEB }`: VIDEO and WEB are covered while driving; MUSIC stays usable.
- `data class Service(id, name, glyph, url, color, kind, desktop, searchUrl, loginUrl)`
  - `desktop = true` → the service is shown with a desktop-browser identity (needed for Netflix/Prime/Spotify web players).
  - `searchUrl` uses `%s` for the query; null if the service has no reliable search URL.
- `Services.all`: the 13 tiles in order: YouTube, Netflix, Prime Video, JioHotstar, SonyLIV, ZEE5, MX Player, Spotify,
  YouTube Music, JioSaavn, Amazon Music, Gaana, Browser.
- **To add an app:** add one `Service(...)` line here. Nothing else is needed.

#### `WebConfig.kt` — browser engine setup (used by car and phone)
- `WebConfig.configure(webView)`: JavaScript, storage, autoplay allowed, zoom, cookies (incl. third-party),
  high renderer priority, removes the `X-Requested-With` header (helps Google sign-in), injects the
  **background-play script** (pages always think they're visible, so playback doesn't pause).
- `WebConfig.applyService(webView, service, oldScript)`: switches user agent (mobile Chrome or desktop Windows Chrome),
  sets matching Client-Hints metadata, and adds the desktop `navigator.platform` script for desktop services.
- `mobileUa()` / `desktopUa()`: user-agent strings built from the phone's real Chrome version.
- `AdBlock`: list of ad/tracker hosts; `isBlocked(host)` checks a host and its parent domains.
- `MdWebClient`: keeps links inside the app, converts `intent://` links to their web fallback, blocks other app
  deep links, and returns empty responses for ad hosts.
- `MdChromeClient`: grants the **DRM (Widevine) permission** so protected streams can play; denies camera/mic.

#### `MainActivity.kt` — phone setup screen
- Passenger button **"Watch on this phone"** → `watchOnPhone()`: asks the car screen (`CarLink.ui.handoff`) for the
  current page and time, then opens it in `LoginActivity`.
- Location + car-speed permission request (needed for the parked check).
- One **Sign in** button per service (opens `LoginActivity`), and **Sign out of everything** (clears cookies and storage).
- Android Auto setup steps and in-car tips (`SETUP_STEPS`, `CAR_TIPS` texts).
- UI is built in code (no XML layouts).

#### `LoginActivity.kt` — phone browser / passenger player
- Full-screen WebView set up with `WebConfig` for one service (`EXTRA_ID`), optionally opening a given URL (`EXTRA_URL`).
- Supports fullscreen video (`onShowCustomView`, `applyFullscreen` hides system bars on Android 11+).
- Back goes back a page or leaves fullscreen; cookies are flushed on pause.

### Car code (`app/src/main/java/com/mdos/auto/car/`)

#### `MdCarAppService.kt` — Android Auto entry point
- `MdCarAppService`: the service Android Auto binds to; accepts any host (needed for sideloaded apps).
- `MdSession`: on start, creates `CarRenderer`, registers it as the surface callback, starts `SpeedMonitor`, asks the
  phone for missing location or car-speed permissions, and cleans everything up when Android Auto disconnects.
- `MainScreen`: the `NavigationTemplate`.
  - Top buttons: Home, Back, Keyboard, Play/Pause.
  - Side buttons: Pan, Scroll up, Scroll down.
  - The car's own Back key is routed to `CarUi.back()`.
- `TypeScreen`: Android Auto `SearchTemplate` (the car's keyboard). Rows: "Type and press Enter", "Type only",
  "Search on <service>" (current service first). Submit calls `CarUi.submit()`.

#### `CarRenderer.kt` — car surface → our window
- Implements `SurfaceCallback`.
- `onSurfaceAvailable`:
  - First time, or the screen size changed: creates the `VirtualDisplay` + `Presentation` (with `CarUi` inside).
  - Same size coming back (after the keyboard or another app): just re-attaches the surface, so playback continues.
- `onSurfaceDestroyed`: detaches the surface but keeps everything alive (background audio).
- `onStableAreaChanged` / `onVisibleAreaChanged`: tell `CarUi` which area isn't covered by Android Auto's buttons.
- `onClick`, `onScroll`, `onFling` → `TouchInjector`; `onScale` → `CarUi.zoom()` (pinch-to-zoom).
- `driveState`: passed through to `CarUi`.
- `release()`: tears down everything.
- `object CarLink`: holds the live `CarUi` so the phone app can reach it (passenger hand-off).

#### `CarUi.kt` — everything drawn on the car screen
Layers, bottom to top, inside `content` (padded to the safe area):
1. `webBox`: the single shared **WebView**.
2. `fullBox`: fullscreen video layer.
3. `home`: launcher (top bar: "MD OS", parked/driving chip, ▶ Resume button, clock; then a 5-column tile grid).
4. `lock`: the driving cover ("Video is hidden while driving", Play/Pause, Home).
5. `progress` (page-load bar) and `flash` (short toast messages).

Public functions (called from Android Auto buttons, the phone, or the test):

| Function | What it does |
|---|---|
| `open(service)` | Switch identity and load the service (if not already open), then show it. |
| `searchOn(service, q)` | Load the service's search URL for `q`. |
| `submit(q)` | Keyboard submit: type into the focused field on the page, else search the current service (YouTube from home). |
| `typeIntoPage(text, enter)` | Fill the focused text box via JavaScript, optionally pressing Enter. |
| `togglePlay()` | Pause/play whatever is playing (media elements or the site's play button). |
| `pageScroll(dir)` | Scroll the page by ~70% of a screen. |
| `zoom(factor)` | Pinch zoom on web pages. |
| `back()` | Leave fullscreen, then go back a page, then go to Home. |
| `showHome()` / `showWeb()` | Switch between the launcher and the page. |
| `resumeLast()` | Re-open the last page watched (saved in SharedPreferences `mdos`: `last_service`, `last_url`). |
| `handoff(cb)` | Passenger mode: pause, return the URL (+ YouTube time), so the phone continues. |
| `setDriveState(state)` | Updates the chip and shows/hides the driving cover. |
| `setSafeArea(rect, w, h)` | Pads content away from Android Auto's buttons. |
| `contentCenter()` | Where synthetic drags start. |
| `destroy()` | Stops timers and destroys the WebView. |
| `evalJs()`, `tileCenter()` | Test hooks (internal). |

Internals:
- `refresh()` decides what's visible. The cover shows when not on home, the service isn't MUSIC, and the car isn't PARKED.
- `createWebView()` wires the clients:
  - progress bar, fullscreen
  - JS dialogs auto-answered (they can't be shown on the car screen)
  - last-page saving
  - crash recovery via `recreateWebView()`
- Embedded JavaScript constants:
  - `TYPE_JS`: typing into a page field
  - `PLAY_PAUSE_JS`: play/pause
  - `HANDOFF_JS`: pause and report URL and time

#### `TouchInjector.kt` — Android Auto gestures → real touches
- `tap(x, y)`: finger down, then up after 60 ms.
- `scroll(dx, dy)`: starts a drag at the content center, moves the finger, ends the drag 150 ms after the last
  movement (or when it leaves the screen).
- `fling(vx, vy)`: a few fast moves, then lift, so pages fling naturally.
- Builds `MotionEvent`s with source touchscreen and finger tool type.

#### `SpeedMonitor.kt` — parked or driving
- Sources: **car speed** via Android Auto `CarInfo.addSpeedListener` (first choice), **phone GPS** (fallback,
  used only if fresh within 10 s).
- States (`enum DriveState`): `PARKED`, `DRIVING`, `UNKNOWN` (no data, so video is covered).
- Rules:
  - Above 2.5 m/s (~9 km/h) → DRIVING.
  - Below 1.0 m/s (~3.6 km/h) for 1 s → PARKED.
  - In between → keep the current state.
- Checks every second; calls back only when the state changes.

### Tests (`app/src/androidTest/java/com/mdos/auto/`)

#### `CarScreenTest.kt` — emulator test of the real car screen
- Runs `CarRenderer` on an 800×480 surface at 160 dpi (the Venue's Android Auto size), with Android Auto's
  right-hand button area reserved.
- Steps:
  1. Home screen
  2. Parked
  3. Tap the YouTube tile through `onClick`
  4. Drag-scroll and fling
  5. Driving cover
  6. Keyboard search
  7. Open all 13 services
- Per service it records: final URL, title, page text, number of video elements, Widevine DRM availability,
  user agent and platform.
- Output in the app's `files/report/`:
  - PNG screenshots
  - `report.json`
  - `progress.txt`
- CI publishes these as the `test-report` release.
- Safety rails: main-thread calls time out (15 s), and step logging (`MdOsTest` log tag) shows where a run stops.

---

## 4. Key flows

**Opening an app on the car:** tap tile → Android Auto `onClick` → `TouchInjector.tap` → tile click → `CarUi.open` →
`WebConfig.applyService` → `loadUrl`.

**Driving:** `SpeedMonitor` → `MdSession` callback → `CarRenderer.driveState` → `CarUi.setDriveState` → `refresh()`
shows the cover (audio keeps playing).

**Passenger hand-off:** phone "Watch on this phone" → `CarLink.ui.handoff` (pauses car, gets URL + time) →
`LoginActivity` with `EXTRA_URL`.

**Sign-in sharing:** `LoginActivity` and `CarUi` both use `WebConfig`, the same process, and the same `CookieManager`.

**Switching to Maps and back:** `onSurfaceDestroyed` detaches the display surface; the page keeps playing;
`onSurfaceAvailable` re-attaches it.

---

## 5. Where to change common things

| Want to… | Change |
|---|---|
| Add/remove an app tile | `Services.kt` → `Services.all` |
| Change tile grid columns | `CarUi.kt` → `buildHome()` → `columns` |
| Change colors/text sizes on the car | `CarUi.kt` → `BG`, `label(...)` sizes, `tile()` |
| Change Android Auto buttons | `MdCarAppService.kt` → `MainScreen.onGetTemplate()` |
| Change speed thresholds | `SpeedMonitor.kt` → `MOVING_MPS`, `STOPPED_MPS`, the 1000 ms hold |
| Add ad hosts to block | `WebConfig.kt` → `AdBlock.hosts` |
| Change user agents | `WebConfig.kt` → `mobileUa()`, `desktopUa()`, `uaMetadata()` |
| Phone setup texts | `MainActivity.kt` → `SETUP_STEPS`, `CAR_TIPS` |
| Version number | `app/build.gradle.kts` → `versionCode`, `versionName` |
