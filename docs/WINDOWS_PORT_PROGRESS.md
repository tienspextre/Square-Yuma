# Square-Yuma for Windows — Progress

Read [WINDOWS_PORT_SPEC.md](WINDOWS_PORT_SPEC.md) first. Update this file at the end of every work session:
tick boxes, list touched files, paste the gate command and its result, and write the next step.

## Status

| Phase | State |
|---|---|
| 1 — KMP modules, shared core/backends, remove `sharedSources` + stubs | done (gate green 2026-11-24) |
| 2 — Native engine (WASAPI, decoders, DSP, SMTC, JNI) | done (Linux gate green 2026-11-25; Windows-only files uncompiled here) |
| 3 — Local library + Subsonic parity, queue persistence | done (Linux gate green 2026-11-25) |
| 4 — Frameless window, 3-pane glass UI, desktop package split | not started |
| 5 — Fullscreen, mini-player, PC ergonomics, tray low-RAM | not started |
| 6 — Installer, auto-update, diagnostics, CI | not started |

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

## Log
_(newest first)_

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
