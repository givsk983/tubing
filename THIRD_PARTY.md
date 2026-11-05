# Third-party components

- Simple Icons 13.0.0 — CC0-1.0. GitHub and Telegram paths converted to Android VectorDrawable without changing their geometry. https://github.com/simple-icons/simple-icons/tree/13.0.0/icons ; license bundled in APK assets at `licenses/simple-icons-CC0.txt`.
- LiquidGlass for Android v2.0.11 — MIT, Copyright (c) 2025-2026 pandadog. https://github.com/QWEA0/Liquid-Glass-Android
  - Uses the upstream `LiquidGlassTabBar` directly for rendering, backdrop capture, refracting selection droplet and drag gestures. Tubing supplies labels, icons and navigation callbacks.
  - Unmodified JitPack AAR: `https://jitpack.io/com/github/QWEA0/liquidglass/v2.0.11/liquidglass-v2.0.11.aar`, bundled in `app/libs/` for reproducible offline builds.
  - Tag v2.0.11, source commit `fc6bd34fa3290638c3594cc39386f549d1316ab5`; AAR SHA-256 `8218f8075c3c447241f2a1e96d502fc48718c6d007e9e05347bb9669901f13a9`.
  - License included beside the AAR and in APK assets under `licenses/liquidglass-MIT.txt`. AndroidX core-ktx 1.13.1 is an Apache License 2.0 runtime dependency.

- Android apksig 8.7.3 — Apache License 2.0. Used to sign and verify custom-package APK copies. https://android.googlesource.com/platform/tools/apksig/

- xterm.js 5.5.0 — MIT license. Bundled unmodified under `app/src/main/assets/terminal/`, including its `LICENSE` file. https://github.com/xtermjs/xterm.js
- JNA 5.15.0 Android AAR — used under the Apache License 2.0 option of its dual license. https://github.com/java-native-access/jna
