# Square-Yuma for Windows — Progress

Read [WINDOWS_PORT_SPEC.md](WINDOWS_PORT_SPEC.md) first. Update this file at the end of every work session:
tick boxes, list touched files, paste the gate command and its result, and write the next step.

## Status

| Phase | State |
|---|---|
| 1 — KMP modules, shared core/backends, remove `sharedSources` + stubs | done (gate green 2026-11-24) |
| 2 — Native engine (WASAPI, decoders, DSP, SMTC, JNI) | done (Linux gate green 2026-11-25; Windows-only files uncompiled here) |
| 3 — Local library + Subsonic parity, queue persistence | done (Linux gate green 2026-11-25) |
| 4 — Frameless window, 3-pane glass UI, desktop package split | done (Linux gate green 2026-11-25; Windows-only files uncompiled here) |
| 5 — Fullscreen, mini-player, PC ergonomics, tray low-RAM | done (Linux gate green 2026-11-25; Windows-only files uncompiled here) |
| 5b — Native RSS leak fixes & low-RAM tuning | done (Linux gate + SOAK_RSS PASS 2026-11-25; Windows `mem_trim_win.cpp` uncompiled here) |
| 6 — Installer, auto-update, diagnostics, CI | done (Linux gate green 2026-11-25; Windows CI run pending) |

## Baseline (before Phase 1)
Gate `./gradlew :app:assembleDebug :app:testDebugUnitTest :core:audio-dsp:test :desktop:compileKotlin` on commit 3cf484c: **BUILD SUCCESSFUL in 4m40s, 90 @Test (66 app + 24 audio-dsp), 0 failures** — earlier "174" double-counted release variants (2026-11-24).

## Phase 1 checklist
- [x] `:shared:core` KMP module (androidTarget + jvm("desktop")), `common/Log`, `KeyValueStore`
- [x] Models moved: `Catalog`, `SearchResults`, `LyricsModels`
- [x] `MusicCatalog` contract in shared; `:app` `MusicBackend : MusicCatalog` + `createPlayer`
- [x] Subsonic client/models/mixes moved; `SubsonicCatalog` extracted from `SubsonicBackend`
- [x] Lyrics providers moved (Ttml on `org.xmlpull`, `LyricsLibrary` without `Context`)
- [x] `FlacMetadataParser` / `EmbeddedLyricsExtractor` core on streams, Android `Uri` overloads in `:app`
- [x] `:core:audio-dsp` converted to KMP; planners + analysis in common; media3 in androidMain; tests pass
- [x] Automix extension seams: `TempoMap`, `VocalDuckMode` + energy fill, `diagnosticsJson` / `DrumGridQaResult`
- [x] `:shared:ui` KMP+CMP module: theme, glass, lyrics views; Android/Skiko actuals
- [x] `:desktop`: `sharedSources` removed, stubs (`android/`, `androidx/`, `timber/`, forked copies) deleted, depends on shared modules
- [x] Gate green

## Phase 2 checklist
- [x] `native/windows/` CMake C++20 project (`square_win_native.dll` / `libsquare_win_native.so`), reusing `app/src/main/cpp/{analyzer,jni,stretch.cpp}` and `native-dsp/bungee` by path; vendored `miniaudio` (0.11.26), `stb_vorbis` (commit `033efa1`), `libebur128` (v1.2.6, MIT), `nlohmann/json` (v3.11.3, MIT); `FetchContent` pinned `ogg` (v1.3.5), `opus` (v1.5.2), `opusfile` (v0.12)
- [x] Dual-deck engine: decode threads + lock-free SPSC float/raw rings, zero-alloc/zero-lock render callback, local + HTTP Range streams (WinHTTP on Windows; `find_package(CURL)` or local-only stub on Linux), Shared float32 / Exclusive bit-perfect with fallback + reason, device-lost pause + callback
- [x] Full DSP chain (true bit-exact bypass when neutral): Bungee speed/pitch (`TempoMap` + 35% ease-back), `DjFilter` (LP/HP/low-shelf -24 dB + LR4 `MID_SIDE` vocal duck + `outgoingEnergyFill`), `MixStyle` curves + tiers, `PcmLoop`, 10-band EQ with auto headroom, Schroeder/Freeverb reverb, DUET karaoke, `libebur128` loudness (histogram mode), sleep timer fade, safety limiter
- [x] Windows-only Media Foundation AAC/M4A decoder (`mf_aac_decoder.cpp`), WinHTTP Range stream (`http_stream_win.cpp`), WinRT SMTC on hidden HWND (`smtc_win.cpp`) written & isolated behind `WIN32` / `#ifdef _WIN32` (not compiled on this Linux host)
- [x] JNI surface `dev.lelonio.square.desktop.nativeaudio.NativeEngine` + `src/jni_bridge.cpp`; `Platform.desktop.kt` updated to load `square_win_native`
- [x] Desktop rewired (`DesktopPlayer`, `Automix`, `Downloads`, `MediaKeys`, `Features`, `Updates`, `Main`); `ffmpeg`, `SourceDataLine`, and `jnativehook` removed; `.github/workflows/desktop-windows.yml` and `desktop/build.gradle.kts` (`squareWinNativeLinux`) updated
- [x] `--self-check` CLI exercises full DSP chain + all 4 `MixStyle`s + `AnalyzeAudio`
- [x] Native CTest suite (`ring_buffer`, `dsp_chain`, `decoders`, `exclusive_format`) and Linux Gradle gate green

## Phase 3 checklist
- [x] Desktop Local backend implementing `MusicCatalog` (`BackendId.LOCAL`): multiple root folders, parallel recursive scan with persisted incremental JSON index (`~/.square-yuma/local_index.json`), recursive `WatchService` (create/delete/modify/overflow + debounce), albums/artists/songs/playlists/search
- [x] Shared tag & picture parsers in `:shared:core` `commonMain` (`AudioMetadata`, `FlacMetadataParser` picture support, `Id3TagParser` ID3v2.2/2.3/2.4 + ID3v1, `OggMetadataParser` Vorbis/Opus + `METADATA_BLOCK_PICTURE`, `Mp4MetadataParser` MP4/M4A atoms) + folder artwork (`folder.jpg`/`cover.jpg`/`front.jpg` case-insensitive) + embedded/sidecar `.lrc` lyrics
- [x] Per-track integrated EBU R128 loudness via native `libebur128` (`NativeEngine.measureTrackLufs`), low-priority background `LoudnessCache` persisted in `~/.square-yuma/analysis/`, applied as normalization gain on play for Local and Subsonic tracks
- [x] Playlists: Local UTF-8 `.m3u8` (`LocalPlaylistStore`, relative paths, create/rename/delete/add/remove/reorder) + standard Subsonic REST playlist endpoints (`createPlaylist.view`, `updatePlaylist.view` with `songIdToAdd`/`songIndexToRemove`, `deletePlaylist.view`) in shared `SubsonicClient`/`SubsonicCatalog` (Android `SubsonicBackend` preserves `useBridge = true`)
- [x] Reworked Subsonic offline cache (`Downloads.kt`): deduplicated by track id, original format (`download.view` with raw stream fallback), HTTP `Range` resumable `.part`, cache size limit with LRU eviction, Offline Mode, playback prefers cached file
- [x] Queue persistence (`QueuePersistence.kt`): debounced save + exit/shutdown hook (`~/.square-yuma/queue.json`) for Local + Subsonic (including offline-only), restored paused at saved position
- [x] Cross-source Automix: `Analyses` + `PairPlanner` + `DesktopPlayer` schedule mixes across Local→Local, Subsonic→Subsonic, Local→Subsonic, and Subsonic→Local
- [x] Minimal desktop UI wiring: `Local Folders` sidebar & screen (add/remove/rescan + songs/albums/artists), playlist create/rename/delete/add/remove/reorder, `Offline mode` toggle & cache limit in `Downloads`/`Settings`
- [x] Tests in `:shared:core:desktopTest` and `:desktop:test` + all gates green

### Phase 4 checklist
- [x] Desktop monoliths split into `desktop/src/main/kotlin/dev/lelonio/square/desktop/ui/{shell,home,library,browse,player,lyrics,search,settings,components}` + `LocalCatalog` split (`LocalLibraryModels.kt`, `LocalCatalogWatcher.kt`); no `.kt` file under `desktop/src/main` over 25 KB (`find desktop/src/main -name '*.kt' -size +26k` empty)
- [x] Frameless window (`Window(undecorated = true, transparent = false)`) + Win32 subclass (`native/windows/src/window_win.cpp`, `window_frame.h`, JNI `getWindowHwnd` / `installFrame` / `updateFrameLayout` / `uninstallFrame` via JAWT without JNA/`--add-opens`) + Linux stub & `WindowDraggableArea`
- [x] Glass titlebar: back/forward navigation history, `Ctrl+K` quick search field, stubbed Mini-Player toggle callback, Windows-style min/max/close reporting layout rects to native
- [x] Adaptive 3-pane shell: collapsible left sidebar (< 1000 dp icon rail or manual toggle, Library chip/filter preserved), lazy main content pane with off-UI-thread decoding & LRU bitmap cache, toggleable right inspector (`Lyrics` with translation toggle + target language, `Queue` with smart shuffle / repeat / radio / autoplay / drag reorder, `Audio & Automix` live signal chain), floating glass transport bar with debounced `LiquidSlider`, mouse-wheel volume, and Skiko-ported `AutomixBadge` / `mixRing` / `automixNeonBorder`
- [x] Search history with per-entry delete + drag-and-drop reorder in queue and local playlists
- [x] Settings: `GlassProfile` (`Performance` / `Balanced` / `Quality`), `ThemeMode` (`Dark` / `Light` / `System`), 12-language runtime picker independent of OS locale
- [x] Localization: single source of truth from `app/src/main/res/values*/strings.xml` packaged via `:desktop:sharedResources` (`android-res/`) + desktop-only XML files (`desktop/src/main/resources/i18n/values*/strings.xml`) parsed at runtime across all 12 languages with `en` fallback
- [x] Unit tests in `:desktop:test` (`NavigationHistoryTest`, `SidebarCollapseTest`, `StringsLoaderTest`, `WindowHitTestTest`, `QueueReorderTest`) + ~60s live soak + verification screenshots under `docs/screenshots/windows-port/`

## Phase 5 checklist
- [x] Immersive Fullscreen NowPlaying: borderless fullscreen (`F11` / artwork double-click / button, `Esc` exits), shared `AmbientBackground` & album-adaptive palette, shared synced `LyricsView`, `LiquidSlider` + `AutomixBadge` / `mixRing`, 3 s mouse-idle auto-hide of controls and cursor; native `hitTestPoint` returns `HTCLIENT` when caption/resize insets are zero in fullscreen
- [x] Floating Mini-Player: always-on-top undecorated Compose window (`MiniPlayerWindow.kt`) with artwork, title/artist, `mixRing`, prev/play/next, thin progress bar, whole-window drag, per-monitor position persistence & clamping (`MiniPlayerBounds.kt`), toggled via titlebar button & `Ctrl+M` (main hides while mini shows and restores at previous bounds)
- [x] Shared `GlassMenu` context menu (`ContextMenus.kt`): right-click and `Menu` / `Shift+F10` across track rows, albums, artists, playlists, and grid cards (`Play`, `Play next`, `Add to queue`, `Add to playlist…`, `Go to album`, `Go to artist`, `Download`/`Remove download`, `Show in folder` via `explorer.exe /select,` on Windows or `xdg-open` parent on Linux, `Start radio`, `Remove from playlist`)
- [x] Drag & Drop (`DropTargetHandler.kt` + `Shell.kt` / `QueueInspector.kt`): Explorer/file-manager drops of audio files play immediately, folders prompt with confirmation snackbar to add as Local root, drops onto Queue Inspector append, drops onto a local playlist add
- [x] Global in-app shortcuts (`ShortcutDispatch.kt`, suppressed during text input except Ctrl-combos): `Space`, `Ctrl+K`, `Ctrl+L`, `Ctrl+Q`, `Ctrl+Left/Right`, `Ctrl+Up/Down`, mouse wheel on volume, `F11`, `Ctrl+M`, `Alt+Left/Right`, `Ctrl+,` — documented in Settings → Shortcuts
- [x] System Tray + headless low-RAM mode (`TrayManager.kt`, `WindowTrayController.kt`, `Main.kt`): close (`X`) hides to tray when "Close to tray" is enabled (default on, first-time notice shown; graceful exit fallback on Linux if AWT `SystemTray` unsupported); tray menu (`Play/Pause`, `Next`, `Previous`, `Mini-Player`, `Open`, `Quit`) + left-click restore; dispose Compose main/mini windows from composition, clear artwork & ambient blur caches, release Skia `DirectContext` GPU resources (`flush`, `resourceCacheLimit = 0`, `abandon`), and request GC while keeping audio playback (`NativeEngine`), SMTC HWND, and queue persistence running uninterrupted
- [x] Fixed Phase 4 Light+Performance `TransportBar` glass readability & bottom `BarClearance` (`120.dp`)
- [x] Unit & leak tests in `:desktop:test` (`ShortcutDispatchTest`, `MiniPlayerBoundsTest`, `DropTargetHandlerTest`, `WindowTrayAndLeakTest`, `WindowHitTestTest` fullscreen check), live 10-cycle `--soak-test` (`maxUnderruns=0`, `posAdvanced=true`), and Phase 5 verification screenshots under `docs/screenshots/windows-port/` (< 5 MB total)

## Phase 6 checklist
- [x] Auto-update (`Updates.kt`, `UpdateNotice.kt`, `AppState.kt`, `SettingsScreen.kt`): GitHub Releases `yuma-pc-v*` check (ignoring Android `v*`, drafts, and prereleases unless Beta channel is enabled), SemVer parser/comparator, 24 h auto-check throttle + manual check, in-app liquid glass notice, Windows `.msi` temp download with progress + size/SHA-256 verification + `msiexec /i` launch and exit (preserving `upgradeUuid = 67fc8a84-7a38-4b2f-bb0f-8df4ac7267af`), Linux release-page open
- [x] Diagnostics (`Diagnostics.kt`, `SettingsScreen.kt` → About): rolling file log under `~/.square-yuma/logs` (`square-yuma.log`, 512 KB × 3 files), zip report (`summary.txt`, `native-status.json`, `settings.txt`, `logs.txt`, `self-check.txt`) with secrets redacted (Subsonic password/token/salt/URL creds, auth headers/tokens, emails), local library counts only unless "Include file paths in report" is ticked
- [x] jlink runtime minimization (`desktop/build.gradle.kts` via `:desktop:suggestRuntimeModules` + `jdk.crypto.ec`), Phase 5b JVM flags preserved, Linux `.deb` `MALLOC_ARENA_MAX=2` guidance added, packaged `createDistributable` `--self-check` & startup verified
- [x] Windows CI (`.github/workflows/desktop-windows.yml`): fetches `native-dsp/bungee` (`v2.4.24`), MSVC x64 CMake Release static CRT (`/MT`) build into `desktop/appResources/windows`, runs CTest and `:desktop:test`, packages MSI + portable zip, runs `--self-check` (fails on non-zero exit), documents why `--soak-test` is skipped on headless Windows runners, uploads artifacts and attaches to release when `TAG` is set; YAML + `actionlint` clean
- [x] Android release safety: fixed `ThemePlatformKt` duplicate class collision across `:app` and `:shared:ui` (renamed `:app` file to `ChosenTheme.kt`), added `:shared:core` `LyricsLibrary` serialization keep rule in `app/proguard-rules.pro`, verified `:app:assembleRelease` (R8) and `build.yml`/`release.yml`
- [x] Cleanup, version bump (`desktopVersion = "2.0.0"`), and docs (`WINDOWS_PORT_SPEC.md` §5/§7 updated with realistic RSS floor & JAWT/miniaudio reality; `DEVELOPMENT.md` maintainer guide added)
- [x] Full Linux gate + `UpdatesAndDiagnosticsTest` green

## Log
_(newest first)_

### 2026-11-25 — Phase 6 landed
Gates on Linux:
- `cmake -S native/windows -B native/windows/build -G Ninja -DCMAKE_BUILD_TYPE=Release && cmake --build native/windows/build && ctest --test-dir native/windows/build --output-on-failure`: **4/4 passed** (`ring_buffer`, `dsp_chain`, `decoders`, `exclusive_format`).
- `./gradlew --console=plain :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :core:audio-dsp:test :shared:core:desktopTest :desktop:test :desktop:compileKotlin`: **BUILD SUCCESSFUL** (`:app:testDebugUnitTest` 66, `:core:audio-dsp:testDebugUnitTest` 24, `:core:audio-dsp:desktopTest` 21, `:shared:core:desktopTest` 6, `:desktop:test` 36; **153 total**, 0 failures, 0 errors, 0 skipped; R8 `:app:assembleRelease` passed).
- `./gradlew --console=plain :desktop:createDistributable` + `./desktop/build/compose/binaries/main/app/square-yuma/bin/square-yuma --self-check`: exit 0 (`self-check ok: native self-check ok (4 MixStyles + full DSP + AnalyzeAudio) (duration=1.0s lufs=-19.9)`). Packaged GUI startup on `DISPLAY=:1` stayed alive cleanly (0 errors).
- `XMODIFIERS=@im=none DISPLAY=:1 ./gradlew --console=plain :desktop:run --args=--soak-test`: exit 0, `SOAK_SUMMARY initialPosMs=112 finalPosMs=20715 posAdvanced=true maxUnderruns=0 avgShownHeapMb=28.05 avgTrayHeapMb=22.88`, `SOAK_RSS firstTrayMb=410.93 lastTrayMb=365.63 growthPerCycleMb=0.47 verdict=PASS`.
- Workflow YAML validation (`yaml.safe_load` + `actionlint` v1.7.7 on `desktop-windows.yml`, `build.yml`, `release.yml`): passed with 0 diagnostics.
- `grep -rn --exclude-dir=build "ffmpeg\|jnativehook\|SourceDataLine" desktop .github`: empty.
- `grep -n upgradeUuid desktop/build.gradle.kts`: `upgradeUuid = "67fc8a84-7a38-4b2f-bb0f-8df4ac7267af"`.

Runtime image size (jlink, Linux x64):
- Before (`java.naming`, `java.prefs`, `java.sql`, `jdk.unsupported`, `java.net.http`, `java.logging`, `jdk.security.auth` → 15 jlink modules incl. transitive deps): `229M` total distributable (`43M` `du -sh` on ZFS for `lib/runtime`).
- After (`java.instrument`, `java.prefs`, `jdk.security.auth`, `jdk.unsupported`, `jdk.crypto.ec` → 13 jlink modules; `java.sql`, `java.transaction.xa`, `java.net.http` removed): `228M` total distributable (`43M` `du -sh` / `88,891,127` apparent bytes for `lib/runtime`, `55M` `lib/runtime/lib/modules`). Note: `java.naming`, `java.logging`, `java.security.sasl`, `java.security.jgss` remain pulled in transitively by `jdk.security.auth` (needed for Linux D-Bus/MPRIS).

Created:
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/Diagnostics.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/ui/components/UpdateNotice.kt`
- `desktop/src/test/kotlin/dev/lelonio/square/desktop/UpdatesAndDiagnosticsTest.kt`

Renamed:
- `app/src/main/java/dev/lelonio/square/ui/theme/ThemePlatform.kt` → `ChosenTheme.kt` (eliminated `ThemePlatformKt` duplicate class in `:app:minifyReleaseWithR8`)

Changed:
- `.github/workflows/desktop-windows.yml`, `.gitignore`, `app/proguard-rules.pro`
- `desktop/build.gradle.kts` (`desktopVersion = "2.0.0"`, minimized jlink `modules(...)`, `MALLOC_ARENA_MAX=2` guidance)
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/{AppState,Automix,Main,Strings,Updates}.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/ui/settings/SettingsScreen.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/ui/shell/Shell.kt`
- `desktop/src/main/resources/i18n/values/strings.xml`
- `docs/{DEVELOPMENT,WINDOWS_PORT_SPEC,WINDOWS_PORT_PROGRESS}.md`

Consolidated Windows-only code never compiled on this Linux host (target of first Windows CI run):
- `native/windows/src/http_stream_win.cpp` (WinHTTP Range streaming)
- `native/windows/src/mf_aac_decoder.cpp` (Media Foundation `IMFSourceReader` AAC/M4A decoder)
- `native/windows/src/smtc_win.cpp` (WinRT `SystemMediaTransportControls` on hidden message-only HWND)
- `native/windows/src/window_win.cpp` (JAWT HWND lookup + Win32 frameless subclass, Snap Layouts, DWM attributes, fullscreen `HTCLIENT`)
- `native/windows/src/mem_trim_win.cpp` (`HeapCompact` + `SetProcessWorkingSetSizeEx(-1, -1)` + `EmptyWorkingSet`)
- `#ifdef _WIN32` branches in `native/windows/src/audio_stream.cpp` (UTF-16 wide-char file open), `native/windows/src/engine.cpp` (MMCSS `"Pro Audio"` thread boost), and `native/windows/src/decoder.cpp` (Media Foundation AAC path)
- Runtime-only Windows behavior in JVM code (`Updates.kt` `msiexec /i` launch, `NativeEngine.getWindowHwnd` / `installFrame`, `DropTargetHandler.kt` `explorer.exe /select,`, `TrayManager.kt` Windows tray, miniaudio WASAPI shared/exclusive on Windows 10/11)

Next: push branch `windows-port` + run `desktop-windows.yml` via `workflow_dispatch`, fix any MSVC/Windows CI errors.

### 2026-11-25 — Phase 5b (native RSS leak fixes & low-RAM baseline)
Root causes found (with `-XX:NativeMemoryTracking=summary` + `jcmd VM.native_memory summary.diff`, `jcmd GC.class_histogram`, `jcmd Thread.print`, `/proc/<pid>/smaps`, and `LD_PRELOAD` allocation tracing):
1. **Uncompiled `malloc_trim(0)` + glibc multi-arena sprawl**: partial `native/windows/src/mem_trim_linux.cpp` checked `#ifdef __GLIBC__` before including `<malloc.h>`/`<cstdlib>`, so `__GLIBC__` was undefined and `malloc_trim(0)` compiled to a no-op (`nm -D -u` confirmed no symbol). With concurrent worker/IO threads and `ModelPass` ONNX tensors, glibc allocated multiple 64 MB sub-heaps (~258–320 MB `[anon]` + `[heap]`) that were never returned on `free()`. Fixed header order and added `configureAllocatorForLowMemory()` in `nativeInit()` (`mallopt(M_ARENA_MAX, 2)` + `mallopt(M_MMAP_THRESHOLD, 65536)`), plus `MALLOC_ARENA_MAX=2` in `:desktop:run` on Linux (also needed in packaged Linux launcher scripts before JVM startup).
2. **Per-window `FontCache` + `font/inter.ttf` (876 KB) leak**: `RootNodeOwner$OwnerImpl` created a fresh `SkiaFontLoader`/`FontCache` per `ComposeWindow` (`Main` and `MiniPlayer`), reloading `inter.ttf` (`876,576` B = `0.84 MiB` page-aligned `Data.makeFromBytes`) and leaking Skia `TypefaceFontProviderWithFallback`/`NotoSans-Regular.ttf` VMAs across cycles (+4–5 MB/cycle). Fixed via process-wide `SharedFontFamilyResolver` in `ThemePlatform.kt` wired via `CompositionLocalProvider(LocalFontFamilyResolver provides SharedFontFamilyResolver)` in `Main.kt` and `MiniPlayerWindow.kt`, `InteractiveHighlight` `RememberObserver`/`AutoCloseable` disposal (`shader` close), `RuntimeShaderCache` close/`RuntimeEffect` reuse, `Artwork`/`AmbientBackground` `.use {}`, and `clearGlobalFocusOwner()` on tray entry.
3. **Uncancelled OkHttp calls + 9 per-provider `OkHttpClient` pools**: `lyrics/*` providers each owned an `OkHttpClient` and ran blocking `.execute()` calls inside `Dispatchers.IO` without cancellation, growing `DefaultDispatcher-worker` (up to 53 threads) and `OkHttp` threads (+43 threads / +4.9 MB stacks across c4→c10). Unified all 9 `shared/core` lyrics providers (`SpicyLyrics`, `LyricsOvh`, `KuGou`, `BetterLyrics`, `Translate`, `Lossless`, `Amll`, `LrcLib`, `NetEase`) onto `SharedHttp` (`HttpCancellable.kt`) + `executeCancellable`/`runCancellableCatching` (also applied to `SubsonicClient.kt`, `Artwork.kt`, `Downloads.kt`, `Updates.kt`), with connection-pool eviction on tray entry.
4. **GC / JIT CodeCache retention + cycle-3 masking in `SoakTestRunner`**: default G1GC/C2 JIT kept ~131 MB committed Java heap + expanding CodeCache/C2 arenas (+7–12 MB). Added `-XX:+UseSerialGC -Xms16m -Xmx128m -XX:NewRatio=4 -XX:-ShrinkHeapInSteps -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=20m -Xss512k` (`-Xss256k` SIGSEGV'd `--self-check` in native JNI DSP stack frames on `--self-check`, so `-Xss512k` is the safe minimum) to `compose.desktop.application.jvmArgs` (shared with `:desktop:run`), `OrtSession.SessionOptions.use {}` in `BeatTracker`/`VocalTracker`, `ModelPass.releaseIdleSessions()` on tray entry, `NativeEngine.trimMemory()` after `System.gc()` in `SoakTestRunner.kt`, and an OLS post-warmup slope guard over cycles 5–10 alongside `(lastTrayMb - cycle3TrayMb) / 7` so a transient cycle-3 `ModelPass` spike cannot mask tail leaks.

Measure → RSS delta table (Linux `DISPLAY=:1`, 10-cycle `--soak-test`, `underruns=0` throughout):
| Configuration | Cycle 10 `TrayPostGc` RSS | Delta vs prev | Notes |
|---|---|---|---|
| Baseline partial tree (`HEAD` + broken `#ifdef __GLIBC__` no-op, default G1GC/C2) | `971.49 MB` | — | `growthPerCycleMb=18.25` FAIL (orchestrator baseline on `HEAD` was `574→706 MB`) |
| + Leak fixes + working `malloc_trim(0)` & `mallopt(M_ARENA_MAX=2, M_MMAP_THRESHOLD=65536)` (no JVM flags/env) | `444.24 MB` | `-527.25 MB` | Steady c5..c10 `451.28→444.24 MB` |
| + SerialGC heap sizing/shrink (`-Xms16m -Xmx128m -XX:NewRatio=4 -XX:-ShrinkHeapInSteps -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -XX:ReservedCodeCacheSize=32m -Xss256k`) + `MALLOC_ARENA_MAX=2` | `385.83 MB` | `-58.41 MB` | Tray Java heap shrunk to `~28.5 MB` committed (`~18.8 MB` used) |
| + `-XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=20m -Xss512k` (final Gradle `--soak-test`) | `372.86 MB` | `-12.97 MB` | Cumulative **`-598.63 MB`** (`-61.6%`) vs baseline; `growthPerCycleMb=-7.88` PASS |
| Diagnostic reference: `-Dskiko.renderApi=SOFTWARE` (bypassing headless Mesa `llvmpipe` LLVM/Gallium) | `274.95 MB` | `-97.91 MB` | Isolates `~98 MB` Mesa `llvmpipe` GL overhead on `DISPLAY=:1`; steady c5..c10 `267.93..277.27 MB` |

**Realistic RSS floor**: the aspirational `60–90 MB` target in spec §5 is **unreachable** for an OpenJDK 21 + Skia + Skiko + Compose Desktop process. Even with Java heap shrunk to `~28.5 MB` committed (`18.05 MB` used), ONNX sessions closed, Skia caches purged, `MALLOC_ARENA_MAX=2`, and `malloc_trim(0)`, the realistic Linux/X11 floor is **~364–373 MB RSS on OpenGL** (`~89–98 MB` attributable to Mesa `llvmpipe` software OpenGL on `DISPLAY=:1`) and **~270–277 MB RSS even on Skia Software rendering** (JVM Metaspace/CodeCache/Shared class space/Symbol/NMT + `libjvm`/`libskiko`/`libonnxruntime`/fonts/X11/glibc).

Final `-Xss512k` `--soak-test` (`XMODIFIERS=@im=none DISPLAY=:1 ./gradlew --console=plain :desktop:run --args=--soak-test`):
- Per-cycle `TrayPostGc` RSS (cycles 1–10): `396.70, 413.95, 442.88, 480.34, 430.11, 365.83, 370.98, 364.24, 371.52, 372.86 MB` (cycles 3–5 reflect transient background ONNX `ModelPass` inference on the synthetic 30s track before idle release returns RSS to ~364–373 MB at cycles 6–10)
- `SOAK_SUMMARY initialPosMs=224 finalPosMs=20809 posAdvanced=true maxUnderruns=0 avgShownHeapMb=27.10 avgTrayHeapMb=27.56`
- `SOAK_RSS firstTrayMb=396.70 lastTrayMb=372.86 growthPerCycleMb=-7.88 verdict=PASS`
- Note: also re-verified with `-Xss256k`: `381.95, 422.11, 431.92, 500.24, 365.71, 359.44, 365.83, 365.00, 361.83, 363.61 MB`, `growthPerCycleMb=-0.12 verdict=PASS`, before raising `-Xss` to `512k` for `--self-check` native stack safety.
- Linux launcher note: packaged Linux scripts should set `MALLOC_ARENA_MAX=2` before launching the JVM so early OpenJDK/AWT/Skia threads share at most 2 arenas prior to `NativeEngine.nativeInit()`; `native/windows/src/mem_trim_win.cpp` (`HeapCompact` + `SetProcessWorkingSetSizeEx(-1,-1)` + `EmptyWorkingSet`) is Windows-only and uncompiled on this Linux host.

### 2026-11-25 — Phase 5 landed
Gates on Linux:
- `cmake -S native/windows -B native/windows/build -G Ninja -DCMAKE_BUILD_TYPE=Release && cmake --build native/windows/build && ctest --test-dir native/windows/build --output-on-failure`: **4/4 passed** (`ring_buffer`, `dsp_chain`, `decoders`, `exclusive_format`).
- `./gradlew --console=plain :app:assembleDebug :app:testDebugUnitTest :core:audio-dsp:test :shared:core:desktopTest :desktop:test :desktop:compileKotlin`: **BUILD SUCCESSFUL** (`:app:testDebugUnitTest` 66, `:core:audio-dsp:testDebugUnitTest` 24, `:core:audio-dsp:desktopTest` 21, `:shared:core:desktopTest` 6, `:desktop:test` 29; 0 failures, 0 errors, 0 skipped).
- `./gradlew -q :desktop:run --args=--self-check`: exit 0 (`self-check ok: native self-check ok (4 MixStyles + full DSP + AnalyzeAudio) (duration=1.0s lufs=-19.9)`).
- Live 10-cycle soak test (`XMODIFIERS=@im=none DISPLAY=:1 ./gradlew --console=plain :desktop:run --args=--soak-test`): exit 0, `SOAK_SUMMARY initialPosMs=243 finalPosMs=21970 posAdvanced=true maxUnderruns=0 avgShownHeapMb=30.38 avgTrayHeapMb=29.00` (steady-state cycles 6–10 `TrayPostGc` heap `20.64–20.91 MB` vs `31–38 MB` shown; `underruns=0` across all 50 samples). Note: `XMODIFIERS=@im=none` was passed on this Linux host to avoid an OpenJDK X11/XIM `disposeXIC` hang triggered by `fcitx` when rapidly disposing and recreating AWT `ComposeWindow`s.
- `find desktop/src/main -name '*.kt' -size +26k`: 0 files.
- Screenshots captured under `docs/screenshots/windows-port/` (total folder size `4.2M`): `10_fixed_transport_light_perf.png`, `11_context_menu.png`, `12_mini_player.png`, `13_fullscreen_now_playing.png`.

Created:
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/{DropTargetHandler,MiniPlayerBounds,ShortcutDispatch,SoakTestRunner,TrayManager,WindowTrayController}.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/ui/components/ContextMenus.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/ui/player/MiniPlayerWindow.kt`
- `desktop/src/test/kotlin/dev/lelonio/square/desktop/{DropTargetHandlerTest,MiniPlayerBoundsTest,ShortcutDispatchTest,WindowTrayAndLeakTest}.kt`
- `docs/screenshots/windows-port/{10_fixed_transport_light_perf,11_context_menu,12_mini_player,13_fullscreen_now_playing}.png`

Changed:
- `native/windows/src/{engine.h,engine.cpp}` (non-EOF `underruns_` counter exposed in `statusJson()`)
- `native/windows/src/window_win.cpp` (`hitTestPoint` returns `HTCLIENT` for fullscreen `captionHeightPx <= 0 && resizeBorderPx <= 0`)
- `desktop/src/main/resources/i18n/values/strings.xml` (Phase 5 `desktop_*` keys)
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/{AppState,Automix,DesktopPlayer,Main,QueueLogic}.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/nativeaudio/NativeEngine.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/ui/browse/TracksView.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/ui/components/{AmbientBackground,Artwork,CommonControls,TrackRow}.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/ui/home/HomeScreen.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/ui/library/{LibraryScreen,LocalFoldersScreen,PlaylistsScreen}.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/ui/player/{NowPlaying,TransportBar}.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/ui/settings/SettingsScreen.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/ui/shell/{QueueInspector,Shell,WindowFrame,WindowHitTest}.kt`
- `desktop/src/test/kotlin/dev/lelonio/square/desktop/WindowHitTestTest.kt`

Deleted: none.

Untested Windows-only code (host is Linux, no MSVC/MinGW):
- `native/windows/src/window_win.cpp` (including Phase 5 fullscreen `hitTestPoint` `HTCLIENT` branch), `native/windows/src/{wasapi_out.cpp,smtc_win.cpp,http_stream_win.cpp,mf_aac_decoder.cpp}`, and Windows `explorer.exe /select,` + AWT `SystemTray` behavior on Windows 10/11 (Linux tested via AWT fallback / XFCE).

Next: Phase 6 (MSI/MSIX installer, auto-update, diagnostics bundle, Windows CI & end-to-end verification of native Windows code).

### 2026-11-25 — Phase 4 landed
Gates on Linux:
- `./gradlew --console=plain :app:assembleDebug :app:testDebugUnitTest :core:audio-dsp:test :shared:core:desktopTest :desktop:test :desktop:compileKotlin`: **BUILD SUCCESSFUL** (`:app:testDebugUnitTest` 66, `:core:audio-dsp:testDebugUnitTest` 24, `:core:audio-dsp:desktopTest` 21, `:shared:core:desktopTest` 6, `:desktop:test` 20; 0 failures, 0 errors, 0 skipped).
- `cmake -S native/windows -B native/windows/build -G Ninja -DCMAKE_BUILD_TYPE=Release && cmake --build native/windows/build && ctest --test-dir native/windows/build --output-on-failure`: **4/4 passed** (`ring_buffer`, `dsp_chain`, `decoders`, `exclusive_format`).
- `./gradlew -q :desktop:run --args=--self-check`: exit 0 (`self-check ok: native self-check ok (4 MixStyles + full DSP + AnalyzeAudio) (duration=1.0s lufs=-19.9)`).
- Live GUI run on `DISPLAY=:1`: stayed alive at 60s (`APP_ALIVE_AT_60S=1`) with 0 exceptions in log; verified screenshots captured in `docs/screenshots/windows-port/` (`01_inspector_lyrics.png`, `02_inspector_queue.png`, `03_inspector_audio.png`, `04_collapsed_rail.png`, `05_settings_controls.png`, `06_light_theme_performance.png`, `07_home_light_en.png`, `08_glass_balanced.png`, `09_glass_quality.png`).
- `find desktop/src/main -name '*.kt' -size +26k`: 0 files.

Created:
- `native/windows/src/{window_frame.h,window_frame_stub.cpp,window_win.cpp}`
- `desktop/src/main/resources/i18n/values*/strings.xml` (12 languages: `values`, `values-de`, `values-es`, `values-fr`, `values-hi`, `values-it`, `values-ja`, `values-ko`, `values-pt`, `values-ru`, `values-tr`, `values-vi`)
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/{AppState,LocalCatalogWatcher,LocalLibraryModels,NavigationHistory,QueueLogic}.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/ui/components/{AmbientBackground,Artwork,CommonControls,DragReorder,TrackRow}.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/ui/shell/{AudioInspector,InspectorPane,QueueInspector,Shell,Sidebar,TitleBar,WindowFrame,WindowHitTest}.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/ui/{home/HomeScreen,library/{LibraryScreen,LocalFoldersScreen,PlaylistsScreen},browse/{BrowseScreens,MonthScreen,TracksView},lyrics/{LyricsInspector,LyricsSources},player/{AutomixVisuals,NowPlaying,TransportBar},search/SearchScreen,settings/{AudioSettingsSection,SettingsScreen}}.kt`
- `desktop/src/test/kotlin/dev/lelonio/square/desktop/{NavigationHistoryTest,QueueReorderTest,SidebarCollapseTest,StringsLoaderTest,WindowHitTestTest}.kt`

Changed:
- `desktop/build.gradle.kts` (`sharedResources` Sync packages `app/src/main/res/values*/strings.xml` -> `android-res`)
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/{DesktopPlayer,Downloads,Features,LocalCatalog,Main,Strings,Updates}.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/nativeaudio/NativeEngine.kt`
- `native/windows/{CMakeLists.txt,src/jni_bridge.cpp}`
- `shared/core/src/commonMain/kotlin/dev/lelonio/square/backend/subsonic/{SubsonicClient,SubsonicModels}.kt` (`getSimilarSongs` / `similarSongs2`)

Deleted (split into packages above):
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/{Ambient,App,Artwork,LyricsSources,NowPlaying,Screens}.kt`

Untested Windows-only code (host is Linux, no MSVC/MinGW):
- `native/windows/src/window_win.cpp` (Win32 subclass `WM_NCCALCSIZE`, `WM_NCHITTEST`, `WM_GETMINMAXINFO`, `DwmExtendFrameIntoClientArea`, `DwmSetWindowAttribute`, JAWT HWND lookup) plus Phase 2 Windows-only native code (`wasapi_out.cpp`, `smtc_win.cpp`, Win32 COM/MMCSS branches).

Next: Phase 5 (fullscreen, mini-player, PC ergonomics, tray low-RAM) and Windows CI verification of `window_win.cpp` + WASAPI/SMTC.

### 2026-11-25 — Phase 3 landed
Gates on Linux:
- `./gradlew --console=plain :app:assembleDebug :app:testDebugUnitTest :core:audio-dsp:test :shared:core:desktopTest :desktop:test :desktop:compileKotlin --rerun-tasks`: **BUILD SUCCESSFUL in 1m23s** (`:app:testDebugUnitTest` 66, `:core:audio-dsp` 24 Android + 21 desktop, `:shared:core:desktopTest` 6, `:desktop:test` 8; 0 failures, 0 skipped).
- `cmake --build native/windows/build && ctest --test-dir native/windows/build --output-on-failure`: **4/4 passed** (`ring_buffer`, `dsp_chain` incl. `ebur128` loudness check, `decoders`, `exclusive_format`).
- `./gradlew -q :desktop:run --args=--self-check`: exit 0 (`self-check ok: native self-check ok (4 MixStyles + full DSP + AnalyzeAudio) (duration=1.0s lufs=-19.9)`).

Created:
- `shared/core/src/commonMain/kotlin/dev/lelonio/square/data/local/{AudioMetadata,Id3TagParser,OggMetadataParser,Mp4MetadataParser}.kt`
- `shared/core/src/desktopTest/kotlin/dev/lelonio/square/{data/local/TagParsersTest,backend/subsonic/SubsonicPlaylistSyncTest}.kt`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/{LocalCatalog,LocalPlaylistStore,LoudnessCache,QueuePersistence}.kt`
- `desktop/src/test/kotlin/dev/lelonio/square/desktop/{LocalCatalogTest,LocalPlaylistStoreTest,DownloadStoreTest,QueuePersistenceTest,DesktopAutomixAndLoudnessTest}.kt`

Changed:
- `native/windows/src/{engine.h,engine.cpp,jni_bridge.cpp}`, `native/windows/tests/test_dsp_chain.cpp` (`measureTrackLufs` / `measureSamplesLufs` via `libebur128`)
- `shared/core/src/commonMain/kotlin/dev/lelonio/square/data/local/FlacMetadataParser.kt` (picture detection & `parseArtwork` / `parsePictureBlock`)
- `shared/core/src/commonMain/kotlin/dev/lelonio/square/backend/subsonic/{SubsonicModels,SubsonicClient,SubsonicCatalog}.kt` (standard Subsonic playlist REST CRUD)
- `app/src/main/java/dev/lelonio/square/backend/subsonic/SubsonicBackend.kt` (`override val useBridge = true` to keep Android behavior unchanged)
- `desktop/build.gradle.kts` (`junit:junit:4.13.2` + `kotlin("test")` for `:desktop:test`)
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/nativeaudio/NativeEngine.kt` (`measureTrackLufs`)
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/{App,Artwork,Automix,DesktopPlayer,Downloads,LyricsSources,Main,NowPlaying,Screens,Strings}.kt`

Deleted: none.

Next: Phase 4 (frameless window, 3-pane glass UI, desktop package split) and Windows CI verification.

### 2026-11-25 — Phase 2 landed
Gates on Linux:
- `cmake -S native/windows -B native/windows/build -G Ninja -DCMAKE_BUILD_TYPE=Release && cmake --build native/windows/build && ctest --test-dir native/windows/build --output-on-failure`: **4/4 passed** (`ring_buffer`, `dsp_chain`, `decoders`, `exclusive_format`).
- `./gradlew --console=plain :app:assembleDebug :app:testDebugUnitTest :core:audio-dsp:test :desktop:compileKotlin`: **BUILD SUCCESSFUL** (`:app:testDebugUnitTest` 66, `:core:audio-dsp` 24 Android + 21 desktop, 0 failures, 0 skipped).
- `./gradlew --console=plain :desktop:run --args=--self-check`: exit 0 (`self-check ok: native self-check ok (4 MixStyles + full DSP + AnalyzeAudio) (duration=1.0s lufs=-19.9)`).
- `grep -rn "jnativehook\|SourceDataLine\|ffmpeg" desktop/src desktop/build.gradle.kts .github/workflows/desktop-windows.yml`: empty.

Created:
- `native/windows/CMakeLists.txt`, `native/windows/src/{ring_buffer.h,dsp_chain.h,dsp_chain.cpp,audio_stream.h,audio_stream.cpp,http_stream_win.cpp,http_stream_curl.cpp,http_stream_linux_stub.cpp,decoder.h,decoder.cpp,mf_aac_decoder.cpp,smtc.h,smtc_win.cpp,smtc_stub.cpp,third_party_impl.cpp,engine.h,engine.cpp,jni_bridge.cpp}`
- `native/windows/third_party/{miniaudio,stb,libebur128,nlohmann}/*` (pinned sources + license files)
- `native/windows/tests/{test_ring_buffer.cpp,test_dsp_chain.cpp,test_decoders.cpp,test_exclusive_format.cpp}`
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/nativeaudio/NativeEngine.kt`

Changed:
- `core/audio-dsp/src/desktopMain/kotlin/moe/rukamori/archivetune/playback/smart/Platform.desktop.kt` (loads `square_win_native`)
- `desktop/src/main/kotlin/dev/lelonio/square/desktop/{DesktopPlayer,Automix,Downloads,MediaKeys,Features,Updates,Main}.kt`
- `desktop/build.gradle.kts` (replaced `yumaAnalysisLinux` with `squareWinNativeLinux` CMake task; removed `jnativehook`)
- `.github/workflows/desktop-windows.yml` (replaced `ffmpeg` + `cl yuma_analysis.dll` with CMake MSVC build of `square_win_native.dll`)

Deleted: `desktop/appResources/linux/libyuma_analysis.so` (if present); removed `ffmpeg` subprocess, `SourceDataLine`, and `jnativehook` usage from desktop.

Untested Windows-only files (never compiled on this Linux host — require Windows CI / MSVC):
- `native/windows/src/http_stream_win.cpp` (WinHTTP Range streaming)
- `native/windows/src/mf_aac_decoder.cpp` (Media Foundation `IMFSourceReader` AAC/M4A decoder)
- `native/windows/src/smtc_win.cpp` (WinRT `SystemMediaTransportControls` on hidden message-only HWND)
- `#ifdef _WIN32` paths in `native/windows/src/audio_stream.cpp` (wide-char file open) and `native/windows/src/engine.cpp` (MMCSS `"Pro Audio"` thread boost)

Note on Linux HTTP(S): `libcurl` development headers were absent on this Linux build machine, so CMake selected `http_stream_linux_stub.cpp` (local files only on Linux unless `libcurl` dev package is installed and `find_package(CURL)` succeeds).

Next: Phase 3 (local library + Subsonic parity, queue persistence) and Windows CI verification of `square_win_native.dll`.

### 2026-11-24 — Phase 1 landed
Gate `./gradlew :app:assembleDebug :app:testDebugUnitTest :core:audio-dsp:test :desktop:compileKotlin`: **BUILD SUCCESSFUL**. Tests: `:app:testDebugUnitTest` 66, `:core:audio-dsp` 24 on Android (debug) + 21 on `desktopTest` (PairPlannerTest, PulsePlannerTest now in `commonTest`; `DjFilterStemBusTest` stays `androidUnitTest`), 0 failures. No test removed. `grep sharedSources|package android.|androidx.|timber desktop/` is empty.

Created: `shared/core` (`common/{Log,KeyValueStore}`, `backend/MusicCatalog`, `backend/subsonic/{SubsonicCatalog,OfflineTracks}`, `data/local/EmbeddedLyricsParser`, `lyrics/PullParser` actuals), `shared/ui` (expect declarations `glass/GlassPlatform`, `glass/backdrop/Platform`, `glass/backdrop/internal/Platform`, `theme/ThemePlatform`, `player/LyricsSyncOffset`), `core/audio-dsp` commonMain `playback/smart/{Platform,StereoRegion}` (`loadAnalysisLibrary`, `ModelSource` expect/actual). App adapters: `SubsonicPreferencesAndroid`, `SearchResultsSpotify`, `FlacContentParser`, `CatalogNative` (`object Catalog` + `toCatalogTrack`), app `EmbeddedLyricsExtractor`/`SubsonicBackend`/`MusicBackend` are now thin.
Moved (git mv, packages unchanged): Subsonic client/models/mixes/preferences, all lyrics providers + `LyricsLibrary`, `Catalog` models, `SearchResults`, `LyricsModels`, `FlacMetadataParser`, analysis (`BeatTracker`, `MelSpectrogram`, `VocalTracker`, `TrackFeatures`, `ModelPass`, `PairCache`), planners/pulse/`PcmLoop` to `:core:audio-dsp` commonMain, media3 deck/processor files to androidMain, theme/glass/lyrics/GlassMenu to `shared/ui`, `inter.ttf` to `shared/ui/src/androidMain/res/font`.
Deleted: `desktop/src/main/kotlin/{android,androidx,timber}/**`, desktop forks of glass/theme/PcmLoop/DjFilterAudioProcessor/AudioDecoder/AudioEffects, the `sharedSources` task.

Deviations from the spec (all deliberate):
- `KeyValueStore` is an interface with `asKeyValueStore()` adapters (SharedPreferences / `java.util.prefs`), not expect/actual. `SubsonicPreferences(context)` and desktop `SubsonicPreferences()` are factory functions over the shared class; the desktop migrates its old un-prefixed keys once.
- `SubsonicCatalog` is `open` and takes `OfflineTracks?` (implemented by app `OfflineTrackStore`); `SubsonicBackend` = catalog + `createPlayer` + SAF/download extras, and overrides `keptLyrics` (SpotifyLyrics). `LyricsLibrary.attach` takes the cache `File` plus hooks for Spotify lyrics, AMLL relatives and download-lyrics (the phone installs them).
- Embedded lyrics: shared class is `EmbeddedLyricsParser(open: (String) -> InputStream?)`; `EmbeddedLyricsExtractor(ContentResolver)` stays in `:app` with the old signature.
- `ModelPass.init(Context)` became `ModelSource.init(context)` (androidMain; `TrackAnalyzer` calls it). `AudioDecoder.StereoRegion` became top-level `StereoRegion`.
- Theme seams: `AndroidThemeHooks.systemFont` and `LyricsSyncOffset.source` are set from `SquareApplication`; `ChosenTheme` stays in `:app`.
- Compose versions: CMP 1.12.2 wants androidx Compose 1.12.1, which needs AGP 9.1. `:app` and `:shared:ui` (android side) use `enforcedPlatform(compose-bom)` to stay on the BOM's version. Revisit when AGP is upgraded.
- Seams (item 7): `TempoMap`/`TempoPoint` on `AutomixPlan.tempoMap`; `VocalDuckMode` + `outgoingEnergyFill` on `StemMix`; `TrackAnalysisResult.diagnosticsJson` and `drumGridQa: DrumGridQaResult?` persisted by `TrackAnalyzer` only when present (old cache files load unchanged). No planner reads them.
- commonMain uses JDK/OkHttp/org.json directly; the platform compilations accept it, so no `jvmShared` source set was needed (the metadata compile of commonMain is not part of any gate task and would reject it).
- OkHttp is pinned to 5.3.2 in `:shared:core` on both targets (the lyrics clients rely on the non-null response body).

Known risks: R8/release build and `:app:assembleRelease` were not run (consumer/keep rules unchanged, packages unchanged); `compileCommonMainKotlinMetadata` is not expected to pass; the desktop app was compiled but not launched.

Next: Phase 2 (native `square_win_native.dll`, WASAPI, JNI). Desktop still uses ffmpeg + `SourceDataLine` + jnativehook.

### 2026-11-25 — Phase 5 independent verification (orchestrator)
- Gate re-run with `--rerun-tasks`: green; `:desktop:test` 29, Android 66 + 24 unchanged.
- `--soak-test` (10 cycles, Linux/OpenGL): audio OK (position advanced, underruns 0), Java heap after GC in Tray ≈ 21 MB.
- **FAILED: native RSS.** Tray-post-GC RSS per cycle: 574, 578, 628, 621, 644, 656, 670, 677, 690, 706 MB → ~13 MB/cycle growth (native leak on window dispose/recreate) and absolute level far above the 60–90 MB target. Phase 5 stays open until a follow-up fixes the growth and lowers the baseline; see "Phase 5b".
  → **Resolved in Phase 5b (2026-11-25, see Log top):** `396.70, 413.95, 442.88, 480.34, 430.11, 365.83, 370.98, 364.24, 371.52, 372.86 MB`, `growthPerCycleMb=-7.88 verdict=PASS`.

### 2026-11-25 — First Windows CI build (orchestrator)
- Run 45555976904 green on windows-latest (MSVC 19.52, CMake 4, Windows SDK 10.0.26100): native DLL build, CTest 4/4, `:desktop:test`, MSI + portable zip, `--self-check` ok, 25 s launch smoke (~284 MB working set, headless runner).
- Fixes needed to get there: `CMAKE_POLICY_VERSION_MINIMUM 3.5` for libogg under CMake 4; MSVC force-include shim for Bungee's `__attribute__((noinline))`.
- Still unverified on real hardware: WASAPI Exclusive, SMTC, frameless Snap Layouts, tray, auto-update install. Packages are ~200 MB each (onnxruntime all-platform jar 42 MB, material-icons-extended 36 MB, phosphor 26 MB).
