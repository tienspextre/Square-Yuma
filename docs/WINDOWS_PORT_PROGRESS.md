# Square-Yuma for Windows — Progress

Read [WINDOWS_PORT_SPEC.md](WINDOWS_PORT_SPEC.md) first. Update this file at the end of every work session:
tick boxes, list touched files, paste the gate command and its result, and write the next step.

## Status

| Phase | State |
|---|---|
| 1 — KMP modules, shared core/backends, remove `sharedSources` + stubs | done (gate green 2026-11-24) |
| 2 — Native engine (WASAPI, decoders, DSP, SMTC, JNI) | not started |
| 3 — Local library + Subsonic parity, queue persistence | not started |
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

## Log
_(newest first)_

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
