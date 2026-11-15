# MD OS — streaming hub for Android Auto

MD OS is an Android Auto app, tested against the setup of a **Hyundai Venue 2022** (wired Android Auto, touchscreen).
It plays **YouTube, Netflix, Prime Video, JioHotstar, SonyLIV, ZEE5, Spotify, YouTube Music, JioSaavn, Amazon Music and Gaana**
on the car screen, plus a web browser. **Video is allowed.**

## How it gets video onto Android Auto

Android Auto normally lets apps show only fixed list and button templates, and no video.
MD OS registers as a *navigation* app. Android Auto gives navigation apps a raw drawing surface on the car display,
so map apps can draw their own maps. MD OS attaches a private virtual display to that surface and runs a real
browser engine (Android System WebView) on it. Android Auto passes each tap, drag and fling on the car screen
back to MD OS, which replays it as a finger touch.

- Copy-protected streams (Widevine DRM) are allowed, so Netflix, Prime Video and others can play.
- Desktop-only players (Netflix, Prime Video, Spotify web player) get a desktop browser identity.
- Playback keeps going if you switch to Maps or another app on the car screen.
- Sign in **once on the phone**. The car screen shares those sign-ins, so you never type passwords in the car.
- The car's own keyboard (keyboard button) searches services or fills the text box you tapped.
- **Safety:** video is covered while the car is moving and sound keeps playing. Speed comes from the car
  through Android Auto, with phone GPS as a backup. Music apps stay usable while driving.

## Install

1. On your phone, open **https://github.com/Mayur0174/MD_OS/releases/tag/latest** and download `MD-OS.apk`.
   (Every build is also kept under the **Actions** tab → *Build MD OS APK* → **Artifacts**, as a zip.)
2. Install the APK on your Android phone and allow "install unknown apps" for your browser or file manager.
3. Open **MD OS** on the phone:
   - tap **Allow location**
   - tap **Sign in to …** for each service you use
4. Allow non-Play-Store apps in Android Auto (one time):
   1. Phone Settings → Android Auto → scroll down → tap **Version** about 10 times → allow developer settings.
   2. ⋮ menu → **Developer settings** → turn on **Unknown sources**.
   3. Android Auto settings → **Customise launcher** → tick **MD OS**.
5. Connect to the Venue over USB and open **MD OS** from the Android Auto app list.

Every build is signed with the same key (`app/mdos.keystore`, which isn't a secret), so a new APK installs over
the old one and keeps your sign-ins.

## Using it in the car

| Control | What it does |
|---|---|
| Tiles | Open a service |
| Drag / swipe on the screen | Scroll the page |
| Home | Back to the launcher; playback continues |
| Back | Back a page, or leave full screen |
| Keyboard | Car keyboard: type into the page, or search a service |
| Play/Pause | Toggle whatever is playing |
| ▲ / ▼ (side) | Scroll the page |

## Known limits

- **DRM quality.** Netflix and Prime Video play through their websites inside the app's browser engine, using
  software Widevine (L3). Quality is usually SD to 720p. Netflix in particular may refuse some titles, or show an
  "unsupported browser" error on some phones. YouTube, JioHotstar, SonyLIV, ZEE5 and the music services work most reliably.
- **Google sign-in** for YouTube may be blocked inside embedded browsers on some phones. YouTube still plays when signed out.
- Spotify and YouTube Music also have official Android Auto apps. Those support the steering-wheel buttons; MD OS does not.
- Android Auto can hide or disable apps from outside the Play Store in future updates.

## Building

```bash
./gradlew assembleRelease   # → app/build/outputs/apk/release/app-release.apk
```

You need JDK 17 and the Android SDK (API 35). CI runs this on every push (`.github/workflows/build.yml`).

## Code map

| File | Role |
|---|---|
| `car/MdCarAppService.kt` | Android Auto entry point, session, the action-strip screen, keyboard screen |
| `car/CarRenderer.kt` | Car surface → virtual display → our window; forwards touches |
| `car/CarUi.kt` | Launcher, browser engine, full-screen video, driving cover |
| `car/TouchInjector.kt` | Turns Android Auto tap/scroll/fling callbacks into touch events |
| `car/SpeedMonitor.kt` | Parked/driving detection (car speed + GPS) |
| `WebConfig.kt` | Shared browser setup: DRM, user agents, background play |
| `Services.kt` | The app catalogue (add a service here) |
| `MainActivity.kt`, `LoginActivity.kt` | Phone setup and sign-in |
