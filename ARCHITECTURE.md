# tubing Android Root Executor

## Architecture

- Native Kotlin Android application, package `com.stealth.rootexecutor`.
- `MainActivity` is the management UI and uses Android's Storage Access Framework (`ACTION_OPEN_DOCUMENT`) for imports.
- `RootExecutor` owns all privileged file operations. It invokes `su -c` once per operation; commands do not nest `su`.
- Persistent managed data lives under `/data/adb/root_executor/{scripts,binaries,logs,tmp}`. App cache is only used as an import staging area.
- Imported names retain Unicode letters/numbers (including Chinese), spaces and a conservative punctuation allowlist, and are suffixed with a random identifier. Shell arguments are single-quote escaped.
- Directories and target files are checked against symlinks before modification or execution. Only regular files are listed and removable.
- Scripts are launched with `sh`; ELF imports are checked for ELF magic and launched directly. Every execution re-applies mode 777, verifies it using `stat`, reports SELinux state and checks for a `noexec` mount.
- Each run writes its output to a persistent log in `logs/`; the UI displays output and the root command's exit status. The manager also exposes log viewing and temporary-file cleanup.

## Development plan

1. Establish the Android project and root-only product scope. **Done**
2. Build a root command layer with shell-safe argument quoting and managed directory initialization. **Done**
3. Add SAF import, validation, collision-safe naming, permissions and per-run checks. **Done**
4. Add Root authorization gate, execution / file management / settings navigation, system / dark / light themes, font size, and optional saved logs. **Implemented**
5. Add PTY terminal input and streaming output, foreground service, wake lock renewal, and process group termination on task dismissal. **Implemented; rooted device validation pending**
6. File management navigation currently shows a placeholder, as requested; full management UI is deferred. Imports and file selection remain available in the execution page.
7. Validate Magisk/SU variants, keyboards, screen lock, task dismissal, SELinux enforcing behavior and `noexec` restrictions on rooted devices. **Pending device validation**

## Interactive session (1.1)

- Application label is `tubing`; retain `com.stealth.rootexecutor` to allow installing over the previous debug build and preserving app settings.
- MainActivity gates entry on actual `id -u == 0` plus managed directory initialization. No Root grant means no access to the main pages.
- `PtySession` uses the Android JNA AAR to call Bionic PTY functions. A separate `su` process runs `setsid -w`, opens the slave terminal and launches the selected file after checking permissions. No fork occurs inside the JVM and there is no nested `su`.
- Bundled xterm.js 5.5.0 renders ANSI sequences and sends keyboard/IME input through a local WebView bridge. It also reports terminal dimensions. Assets and the MIT license are bundled for offline use.
- `ExecutionService` owns execution independently from Activity, returns `START_NOT_STICKY`, uses a special-use foreground notification and a renewable, time-limited partial wake lock. Changing tabs, themes or moving to the background does not terminate the session. Task dismissal calls process-group termination and closes the PTY.
- Captured output is streamed to an app-private temporary file, then copied into the root log directory if enabled. UI history and queued output are bounded. Password echo follows the script's terminal settings; scripts must use `stty -echo` when appropriate.
- A force-stop, power loss, OEM process killer, or intentionally daemonized child cannot be treated as a guaranteed task-dismiss callback. These behaviors require device validation.
- PTY and Root-policy failures are shown in the terminal rather than silently falling back to pipes.
- Reference for wait/status behavior: [Android Toybox setsid source](https://android.googlesource.com/platform/external/toybox/+/40d21c59f76a9388b0ebb5c4c706732c2d034a67/toys/other/setsid.c).

## Build

Open this directory in Android Studio with Android SDK 35 installed, or run `gradlew.bat assembleDebug` with JDK 17+. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Root authorization is requested the first time the app checks its managed directory.

The development SDK used for this build is in the ignored `.android-sdk/` directory; `local.properties` points Android Gradle Plugin to that SDK and should remain machine-local.
