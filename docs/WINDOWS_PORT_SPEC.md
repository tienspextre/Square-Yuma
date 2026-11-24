# Square-Yuma for Windows — Port Specification

Status: living document. Progress lives in [WINDOWS_PORT_PROGRESS.md](WINDOWS_PORT_PROGRESS.md).
Every decision here is binding for later phases; change it here first, then in code.

## 0. Goals and hard constraints

| # | Constraint |
|---|---|
| C1 | Windows build is **Subsonic/Navidrome + Local folders only**. No Spotify (`librespot`, websdk), no YouTube Music (`NewPipeExtractor`, `:innertube`) on the desktop classpath. |
| C2 | `:app` (Android) keeps all 4 backends, Android Auto, Media3 foreground service. After every phase: `:app:assembleDebug :app:testDebugUnitTest` green. |
| C3 | One source of truth: shared code is **compiled** by both targets from KMP source sets. No `Sync` copies, no string `replace`, no `android.*` / `androidx.media3.*` / `timber.*` stubs in `:desktop`. |
| C4 | Audio on Windows: native C++20 engine `square_win_native.dll` (WASAPI shared float32 / exclusive bit-perfect), in-process decode, real-time DSP. No `ffmpeg.exe`, no `SourceDataLine`, no `jnativehook`. |
| C5 | Liquid Glass UI shared with Android (theme, glass, lyrics views), laid out for PC (frameless window, 3 panes). |
| C6 | MSI `upgradeUuid = 67fc8a84-7a38-4b2f-bb0f-8df4ac7267af` never changes. Desktop tags `yuma-pc-v*`. |

## 1. Baseline (surveyed 2026-11-24, commit 3cf484c)

- Modules: `:app` (Android, ~334 .kt), `:innertube`, `:core:audio-dsp` (Android library, media3), `:desktop` (Kotlin/JVM + Compose Desktop 1.12.2, 33 .kt).
- Toolchain: AGP 8.13.2, Kotlin 2.3.21, JVM 17 toolchain, Compose BOM 2026.06.01 (Android), Compose MP 1.12.2 (desktop).
- `:desktop` debt:
  - `sharedSources` `Sync` copies ~40 files from `app/src/main/java` and `core/audio-dsp/src/main/kotlin`, rewriting `System.loadLibrary("yuma_analysis")`.
  - Stubs: `desktop/src/main/kotlin/android/{content/Context,util/Log,util/Xml}.kt`, `androidx/media3/common/C.kt`, `timber/log/Timber.kt`, plus forked desktop copies of glass platform files, `PcmLoop`, `DjFilterAudioProcessor`, `AudioDecoder`, `SubsonicPreferences`.
  - Monoliths: `Screens.kt` 53 KB, `App.kt` 26 KB, `DesktopPlayer.kt` 21 KB, `NowPlaying.kt` 19 KB.
  - Output: `ffmpeg.exe` subprocess → `SourceDataLine` 16-bit/44.1 kHz. Media keys: `jnativehook` (Win), dbus MPRIS (Linux).
- Android dependencies of the code we want to share (what must be abstracted):

| Code | Android/Media3 coupling | Resolution |
|---|---|---|
| `MusicBackend` | `createPlayer(PlaybackHost): Player`, `PlaybackHost.context/looper` | Split: shared `MusicCatalog` (everything else); app keeps `interface MusicBackend : MusicCatalog` with `createPlayer` |
| Subsonic client/models/mixes | none | move as-is |
| `SubsonicPreferences` | `Context` (SharedPreferences) | shared `KeyValueStore` expect/actual (Android SharedPreferences / desktop `java.util.prefs`) — or keep per-platform if trivial |
| `SubsonicBackend` | `Player`, `LocalPlayerFactory` | catalog half → shared `SubsonicCatalog`; app `SubsonicBackend` = catalog + `createPlayer` |
| `OfflineTrackStore`, `SubsonicDownloadManager` | SAF, MediaStore | stay in `:app` (desktop has its own `Downloads.kt`, reworked in Phase 3) |
| Lyrics providers (`LrcLib`, `NetEase`, `KuGou`, `Lossless`, `BetterLyrics`, `LyricsOvh`, `Amll`, `SpicyLyrics`, `TrackTitle`, `Translate`) | none | move |
| `Ttml` | `android.util.Xml` | `org.xmlpull.v1` API (built into Android, kxml2 on desktop) + `expect fun newPullParser()` |
| `LyricsLibrary` | `Context` (cache dir) | take a `File` cache dir |
| `SpotifyLyrics` | Spotify | stays in `:app` |
| `FlacMetadataParser`, `EmbeddedLyricsExtractor` | `Context/ContentResolver/Uri`, Timber | core logic on `InputStream`/`RandomAccessFile`-like source in shared; Android `Uri` overloads stay in `:app` |
| Analysis (`BeatTracker`, `MelSpectrogram`, `VocalTracker`, `TrackFeatures`, `ModelPass`, `PairCache`) | Timber, `Context` (assets/filesDir in ModelPass), `System.loadLibrary` | move to `:core:audio-dsp` common; `ModelSource` expect/actual; `loadAnalysisLibrary()` expect/actual |
| `AudioDecoder`, `TrackAnalyzer` | MediaCodec/Uri | Android stays androidMain/app; desktop decoder is the native engine (Phase 2) |
| `MusicServiceCrossfadeMath` | `C.TIME_UNSET` | own constant |
| `DjFilterAudioProcessor`, `AudioDeck`, `DeckController`, `DualForwardingPlayer`, `CrossfadeTarget` | media3 | `:core:audio-dsp` androidMain |
| Theme/glass/lyrics UI | `GlassPlatform`, `Coroutines`, backdrop `Platform`, `RuntimeShaderAndroid`, `RenderEffect`, `Paint`, `ThemePlatform`, `Fonts` | expect/actual; desktop actuals = today's desktop copies (`RuntimeShaderSkiko.kt` etc.) |
| Logging | Timber / `android.util.Log` | `dev.lelonio.square.common.Log` expect/actual (Android → Timber, desktop → stderr gated by `-Dsquare.debug`, errors always) |

## 2. Target module graph

```
                 ┌──────────────────────── :app (Android app) ─────────────────────────┐
                 │ Spotify, YouTube Music (:innertube, NewPipe), Media3 service,      │
                 │ Android Auto, MusicBackend : MusicCatalog + createPlayer()          │
                 └───────┬─────────────────────┬────────────────────┬──────────────────┘
                         │                     │                    │
                  :shared:ui  ───────▶  :shared:core  ◀──────  :core:audio-dsp
                  (KMP + CMP)            (KMP)                  (KMP)
                         ▲                     ▲                    ▲
                 ┌───────┴─────────────────────┴────────────────────┴──────────────────┐
                 │ :desktop (Kotlin/JVM app, Compose Desktop)                          │
                 │  └─ JNI ─▶ square_win_native.dll (native/windows, CMake/MSVC)       │
                 └─────────────────────────────────────────────────────────────────────┘
```

KMP modules declare exactly two targets: `androidTarget()` and `jvm("desktop")`. Because every target is a JVM, `commonMain` may use JDK APIs, OkHttp, kotlinx-serialization and `org.json`/`org.xmlpull` (compile-only in common; Android supplies the platform copy, `desktopMain` adds `org.json:json` / `kxml2` at runtime). If the metadata compiler refuses a JDK API, add a `jvmShared` intermediate source set rather than an expect/actual.

Packages are **not** renamed when files move (`dev.lelonio.square.*`, `moe.rukamori.archivetune.*`), so `:app` imports stay untouched. Kotlin `internal` that crosses the new module boundary becomes `public`.

### 2.1 `:shared:core` (`shared/core`)
- `commonMain`: `data/{Catalog,SearchResults,LyricsModels}.kt`; `backend/MusicCatalog.kt` (`BackendId`, `BackendAuthState`, `SearchLabels`, `HomeRow/HomeChip/HomeFeed`, `PlaylistDetails`, `interface MusicCatalog`); `backend/subsonic/{SubsonicClient,SubsonicModels,SubsonicMixes,SubsonicCatalog}`; `backend/lyrics/*` (minus Spotify); `data/local/{FlacMetadataParser,EmbeddedLyricsExtractor}` core; `common/Log`, `common/KeyValueStore`.
- `androidMain` / `desktopMain`: actuals only.

### 2.2 `:shared:ui` (`shared/ui`, Compose Multiplatform)
- `commonMain`: `ui/theme/{Theme,PageTones}`, `ui/glass/**` (minus Android-only and `WaveformSeekBar`), `ui/player/{LyricsView,LyricsTiming,ClassicLyricsView,GlassSurface,GlassProgressBar}`, `ui/components/GlassMenu`.
- `androidMain`: `GlassPlatform`, `Coroutines`, `backdrop/Platform`, `RuntimeShaderAndroid`, `internal/RenderEffect`, `internal/Paint`, `ThemePlatform`, `Fonts`.
- `desktopMain`: the Skiko versions now living in `desktop/src/main/kotlin/dev/lelonio/square/ui/**`.

### 2.3 `:core:audio-dsp` (KMP, in place)
- `commonMain`: planners (`PairPlanner`, `TransitionPlanner`, `TransitionRules`, `MixStyles`, `AutomixModels`, `TrackAnalysisResult`, `CrossfadeConstants`, `CrossfadeConfig`, `MusicServiceCrossfadeMath`, `pulse/*`), `FloatChunkList`, `PcmLoop`, `AnalysisStore`, analysis (`BeatTracker`, `MelSpectrogram`, `VocalTracker`, `TrackFeatures`, `ModelPass`, `PairCache`).
- `androidMain`: media3 processors/decks.
- `commonTest`/`androidUnitTest`: existing `PairPlannerTest`, `PulsePlannerTest`, `DjFilterStemBusTest` (the last stays Android if it touches media3).
- **Extension seams for VertexSelection/automix-engine** (data only, defaulted, no behaviour yet):
  - `TempoMap(points: List<TempoPoint(timeMs: Long, ratio: Float)>)`, optional field on the plan (`AutomixPlan`/`PairPlan`), null = today's linear ramp.
  - `VocalDuckMode { NONE, FULL_BAND, MID_SIDE }` + `outgoingEnergyFill: Boolean = false` on the mix automation.
  - `AnalysisStore`: optional `diagnosticsJson: String?` per entry and `DrumGridQaResult` (data class, nullable) — persisted, ignored by current planners.
  - Both C++ (Phase 2 `Deck`/`Filter`) and Kotlin read the same fields.

### 2.4 `:desktop`
Kotlin/JVM application. Depends on the three shared modules (desktop variants), never on `:app`/`:innertube`. Phase 4 splits it into `ui/{shell,home,library,browse,player,lyrics,search,settings,components}`.

## 3. Native Windows engine — `square_win_native.dll` (Phase 2)

Location: `native/windows/` (CMake ≥ 3.24, MSVC, C++20, `/O2 /GL`, static CRT). Reuses `app/src/main/cpp/analyzer/**`, `app/src/main/cpp/stretch.cpp` (Bungee from `native-dsp/bungee`) by path — no copies. Linux dev builds use the same CMake with a null/ALSA-free output stub so the self-check runs in CI on Linux.

### 3.1 Threads
- **Render thread** (MMCSS "Pro Audio"): pulls from Deck A/B → DSP chain → WASAPI. Never allocates, never locks (SPSC rings + atomics only).
- **Decode thread(s)**: one per deck, fills a float ring (≥ 2 s) ahead; next deck pre-buffers 6–10 s before its mix start.
- **I/O**: Local = buffered file reads; Subsonic = HTTP Range via WinHTTP into a ring, seek = new Range request.
- **Control**: JNI calls post commands into a lock-free queue consumed by the render thread.

### 3.2 Decoders and device I/O — decision (2026-11-25)
**miniaudio** (single header, public domain/MIT-0, vendored under `native/windows/third_party/` at a pinned release) is the device layer and the FLAC/WAV/MP3 decoder (it embeds dr_flac/dr_wav/dr_mp3). Rationale: it already implements WASAPI shared *and* exclusive, device-change/reroute notifications, and gives Linux a real output for dev builds — less code than raw WASAPI, same result on Windows.
- Ogg Vorbis: `stb_vorbis` (vendored, public domain). Opus: `libopus` + `libopusfile` + `libogg` via CMake `FetchContent` pinned tags (BSD).
- AAC/M4A: Windows Media Foundation `IMFSourceReader` (Windows only; Linux dev build reports unsupported).
- Native sample format and rate preserved up to output.

### 3.3 Output modes
| Mode | When | Format |
|---|---|---|
| WASAPI Shared (default) | Automix, crossfade, speed/pitch, reverb, karaoke, EQ, or bit-perfect off | float32 at the device mix rate (miniaudio `ma_share_mode_shared`), resample in the engine |
| WASAPI Exclusive (bit-perfect) | user enables *Bit-Perfect Exclusive* **and** Automix/DSP/EQ all off | `ma_share_mode_exclusive`, device format = file's rate/bit-depth (16/24/32, ≤ 384 kHz), DSP chain fully bypassed; if the device refuses, fall back to Shared and report the reason in `status()`; device closed on pause/stop |

Device lost / default changed (miniaudio notification `ma_device_notification_type_stopped`/`rerouted`) → pause and emit `DEVICE_LOST` to Kotlin.

### 3.4 DSP chain (per deck, then master)
`deck: decode → Bungee speed/pitch (TempoMap aware) → DjFilter (LP/HP/bass shelf −24 dB, MID_SIDE vocal duck + energy fill) → deck gain (MixStyle curve)` → `sum → 10-band parametric EQ (auto headroom) → reverb → karaoke (mid cancel) → ebur128 gain → sleep-timer fade → limiter → out`.
Every stage bypasses to a no-op when neutral (target < 1–2 % CPU on a 4-core laptop).

### 3.5 SMTC
WinRT `SystemMediaTransportControls` via `ISystemMediaTransportControlsInterop::GetForWindow` on a hidden message-only HWND (independent of the Compose window, so it survives tray mode). Pushes title/artist/album/artwork (`RandomAccessStreamReference` from bytes)/timeline/playback status; button presses → JNI callback.

### 3.6 JNI surface (`dev.lelonio.square.desktop.nativeaudio.NativeEngine`)
```
init(): Boolean                              shutdown()
deckLoad(deck, uri, headersJson, startMs)    deckPlay/Pause/Seek(deck, ms)
setOutput(mode, deviceId)                    listDevices(): String (JSON)
setDsp(json)                                 // speed, pitch, eq[10], reverb, karaoke, filter, normalizeGainDb
scheduleMix(planJson)                        // AutomixPlan incl. TempoMap/VocalDuckMode
setSleepTimer(ms, fadeMs, endOfTrack)
position(deck): Long                         status(): String (JSON: format chain for the Inspector)
smtcUpdate(json, artwork: ByteArray?)        setCallback(cb)  // ended, deviceLost, smtcButton, error
analyze(...)                                 // existing yuma_analysis entry points linked into the same DLL
```
`--self-check` (desktop CLI flag): load DLL, render 1 s of a generated sine through the full chain to a null sink, run analyzer on it, exit 0/1.

## 4. Automix parity (all platforms)
`MixStyle` {BLEND, FILTER_SWEEP, BASS_HANDOFF, EQUAL_POWER}; `TransitionTier` {SMART_BEATMATCH, PLAIN_CROSSFADE, GAPLESS, HARD_CUT}; `LoopAudioProcessor`/`PcmLoop`; speed returns to the listener's rate over the last 35 % of the mix; `TransitionRules` (spoken word / live recording detection by metadata not by title words; album playthrough gapless vs *Automix Albums*; durations 0/3/6/9/12 s). Planners are shared Kotlin; desktop executes plans in C++. Analysis cache: `%USERPROFILE%/.square-yuma/analysis`.

## 5. Desktop UI (Phases 4–5)
Frameless window (Compose `undecorated` + Win32 subclass: `WM_NCCALCSIZE`, `WM_NCHITTEST` → `HTCAPTION`/`HTMAXBUTTON` for Snap Layouts, `DwmSetWindowAttribute(DWMWA_WINDOW_CORNER_PREFERENCE)`, `DwmExtendFrameIntoClientArea` for shadow), via JNA-free JNI in the same DLL.
Shell: glass titlebar (back/forward, `Ctrl+K` search, mini-player toggle, min/max/close) · collapsible sidebar (Home, Library[Songs, Albums, Artists, Playlists], Local Folders, Downloads/Offline, Settings; icon rail when narrow) · main pane · toggleable inspector (Lyrics | Queue | Audio & Automix chain) · floating transport bar · immersive fullscreen NowPlaying · always-on-top mini-player.
Tray: close → tray; dispose Compose `Window` and Skia caches; engine keeps playing; tray menu Play/Pause, Next, Previous, Mini-player, Open, Quit.
Shortcuts: `Space`, `Ctrl+K`, `Ctrl+L` (lyrics), `Ctrl+Q` (queue), `Ctrl+Left/Right`, wheel on volume. 12 languages, glass profiles Performance/Balanced/Quality.

## 6. Feature/screen parity checklist

| Feature | Android source | Desktop target | Phase |
|---|---|---|---|
| Subsonic browse/search/stream | `backend/subsonic` | shared `SubsonicCatalog` + native HTTP stream | 1/2 |
| Subsonic playlists CRUD | `SubsonicClient` | shared | 3 |
| Offline cache / downloads | `SubsonicDownloadManager` | desktop `Downloads` on shared store logic | 3 |
| Local folders scan + `WatchService` | `LocalMusicBackend`, `LocalLibrary` | desktop scanner on shared metadata | 3 |
| FLAC/ID3/Vorbis tags, art, embedded lyrics | `data/local/*` | shared | 1/3 |
| Loudness (ebur128) | — | native + cache | 2/3 |
| Queue persistence | `PlaybackStore` | desktop store | 3 |
| Synced lyrics + translation | `backend/lyrics`, `ui/player/LyricsView` | shared | 1 |
| Automix (all tiers/styles) | `:core:audio-dsp`, `playback/smart` | shared planners + native decks | 1/2/3 |
| Speed/Pitch, presets | `stretch.cpp` | native | 2 |
| EQ, reverb, karaoke, DjFilter, sleep timer | app DSP | native | 2 |
| Glass UI, theme from artwork, ambient bg | `ui/glass`, `ui/theme` | shared | 1/4 |
| Media flyout / media keys / BT headset | Media3 session | SMTC | 2 |
| Tray low-RAM mode | — | desktop | 5 |
| Auto-update `yuma-pc-v*`, Save a report | `update/`, `diagnostics/` | desktop `Updates.kt` | 6 |

## 7. Verification gates
- Phase 1: `./gradlew :app:assembleDebug :app:testDebugUnitTest :core:audio-dsp:test :desktop:compileKotlin`.
- Phase 2: + native unit tests (ctest), `square-yuma --self-check`.
- Phase 3: + `:desktop:test` (scanner, playlist sync, queue persistence, PairPlanner on both sources).
- Phase 4–5: manual checklist in PROGRESS + `:desktop:test`.
- Phase 6: full suite + `desktop-windows.yml` green with smoke test.
