# tubing Android Root Executor

## Architecture

- Native Kotlin Android application, package `com.stealth.rootexecutor`.
- `MainActivity` shares the dual-pane file manager with the import picker, including swipe/range selection and batch SH/ELF import. Android's Storage Access Framework (`ACTION_OPEN_DOCUMENT`) remains an alternate import source.
- `RootExecutor` owns all privileged file operations. It invokes `su -c` once per operation; commands do not nest `su`.
- SH and ELF programs share `/data/adb/root_executor` directly; imported records use the `library` identity and runtime header detection chooses the launcher. `logs/` and `tmp/` remain internal subdirectories. Legacy `scripts`/`binaries` records retain their paths and identities for existing shortcuts, presets and history. App cache is only used as an import staging area.
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
6. File management provides a dual-pane browser for managed files and device storage, with navigation, favorites, copy, rename, delete, directory creation, and ZIP extraction. Clicking a ZIP opens a private cached snapshot for directory browsing, bounded content previews and cross-directory selection. ZIP data uses Java ZIP support (UTF-8 or legacy GB18030). Full extraction creates a new folder; selected files/folders are staged and published directly into the opposite pane or archive parent directory, refusing existing targets and rolling back newly published items on failure. Extraction rejects path traversal, duplicate paths, invalid CRCs and excessive output (512 MiB archive, 1 GiB expanded data, 10,000 files/directories); encrypted and split ZIP archives are unsupported. Imports and execution selection remain available from the execution page.
7. Validate Magisk/SU variants, keyboards, screen lock, task dismissal, SELinux enforcing behavior and `noexec` restrictions on rooted devices. **Pending device validation**

## Interactive session (1.1)

- Application label is `tubing`; retain `com.stealth.rootexecutor` to allow installing over the previous debug build and preserving app settings.
- MainActivity gates entry on actual `id -u == 0` plus managed directory initialization. No Root grant means no access to the main pages.
- `PtySession` uses the Android JNA AAR to call Bionic PTY functions. A separate `su` process runs `setsid -w`, opens the slave terminal and launches the selected file after checking permissions. No fork occurs inside the JVM and there is no nested `su`.
- Bundled xterm.js 5.5.0 renders ANSI sequences and sends keyboard/IME input through a local WebView bridge. It also reports terminal dimensions. Assets and the MIT license are bundled for offline use.
- `ExecutionService` owns execution independently from Activity, returns `START_NOT_STICKY`, uses a special-use foreground notification and a renewable, time-limited partial wake lock. Changing tabs, themes or moving to the background does not terminate the session. Task dismissal calls process-group termination and closes the PTY.
- Captured output is streamed to an app-private temporary file, then copied into the root log directory if enabled. UI history and queued output are bounded. In PTY mode, password echo follows the script's terminal settings; scripts must use `stty -echo` when appropriate.
- A force-stop, power loss, OEM process killer, or intentionally daemonized child cannot be treated as a guaranteed task-dismiss callback. These behaviors require device validation.
- PTY and Root-policy failures are shown in the terminal rather than silently falling back to pipes.
- Reference for wait/status behavior: [Android Toybox setsid source](https://android.googlesource.com/platform/external/toybox/+/40d21c59f76a9388b0ebb5c4c706732c2d034a67/toys/other/setsid.c).

## Build

### Execution modes (1.2.0)

- `ExecutionMode` centralizes preference keys and labels. Missing or invalid preferences default to `PIPE`, including upgrades from the previous PTY-only version.
- The execution page and settings page share the persisted mode selector. The chosen mode is passed in the start intent and captured by the service for the entire session. Changes during a run apply to the next run.
- `ExecutionSession` abstracts PTY and pipe backends. `PipeSession` uses a genuine stdin/stdout pipe and chunk-based UTF-8 readers, so prompts without newlines are visible immediately. This backend does not allocate a PTY or load JNA. Program-side buffering can still delay output.
- `PipeInput` provides local line editing/echo, CR/LF normalization, Unicode submission, Ctrl+C process-group signaling, and Ctrl+D stdin closure. Pipe mode submits input on Enter; raw key/full-screen terminal programs should use PTY. Pipe mode cannot honor terminal password-echo controls.
- Both backends share Root validation, chmod verification, execution restriction checks, log storage, foreground lifetime and process-group cleanup. A cancellation marker covers termination racing with session startup.
- xterm rendering uses LF-to-CRLF conversion for pipe output while preserving normal PTY behavior.
- JVM tests cover default mode, safety guards, input editing/control keys and real child-process streaming/input/exit status. Root/SELinux and device lifecycle behavior still require Android testing.

Open this directory in Android Studio with Android SDK 35 installed, or run `gradlew.bat assembleDebug` with JDK 17+. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Root authorization is requested the first time the app checks its managed directory.

The development SDK used for this build is in the ignored `.android-sdk/` directory; `local.properties` points Android Gradle Plugin to that SDK and should remain machine-local.
