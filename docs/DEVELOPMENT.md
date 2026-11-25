# Developing Square

Everything about building Square and finding your way around its code. For
what the app does and how to install it, see the [README](../README.md).

- [Architecture](#architecture)
- [Building](#building)
- [Signing a release build](#signing-a-release-build)
- [Desktop (Windows & Linux)](#desktop-windows--linux)
- [Notes for anyone reading the code](#notes-for-anyone-reading-the-code)

## Architecture

```
┌───────────────────────────────────────────────────────────┐
│ Compose UI                                                │
├───────────────────────────────────────────────────────────┤
│ MediaController ──► MediaSession                          │  notification, lock screen, Bluetooth
├───────────────────────────────────────────────────────────┤
│ PlaybackService                                           │  foreground service, owns the player
├───────────────────────────────────────────────────────────┤
│ MusicBackend            selected once, swapped on change  │
├──────────────────────────┬────────────────────────────────┤
│ SpotifyBackend           │ YouTubeBackend                 │
│   LibrespotPlayer        │   ExoPlayer                    │
│     : SimpleBasePlayer   │     + YouTubeStreamResolver    │
├─────────────────┬────────┼──────────────────┬─────────────┤
│ Web API (HTTPS) │ JNI ──►│ NewPipeExtractor │ innertube   │
│ search, top     │ libsq… │ search, streams  │ the account │
│ tracks, devices │ libre… │ anonymous        │ library     │
└─────────────────┴────────┴──────────────────┴─────────────┘
```

Spotify plays through [librespot](https://github.com/librespot-org/librespot):
audio is streamed in-process by a native Rust core, reached over JNI. YouTube
Music plays through ExoPlayer.

`MusicBackend` is the seam: every catalogue read and the `Player` itself come
from it, so no screen has to know which service is answering. A backend that
cannot do something, as YouTube Music has no Connect device list, returns the
empty value rather than throwing, and that is what keeps one UI usable on both.

On the YouTube side the split is by what needs an account, not by preference.
Search, trending and stream URLs are public and go through NewPipeExtractor,
which is why the app works before anyone signs in and for anyone who never does.
The library is the account's own, so it goes through the vendored
[`innertube/`](../innertube/) module with the session cookie attached.

On the Spotify side the catalogue comes from the access point
(`native/src/catalog.rs`), not from `api.spotify.com`: the Web API meters
requests per application, and there is no streaming endpoint there at all. The
Web API is used only where the access point has nothing to offer: search, the
account's top tracks, the Connect device list and editing playlists. That is why
Square asks you to register an application of your own.

The player keeps no copy of the engine's state: `LibrespotPlayer` reports only
what the engine has confirmed by event. That is why the seek bar never runs
ahead of the audio.

## Building

Requirements:

- Android Studio with SDK platform 35 and **NDK 28.2.13676358**
- Rust ≥ 1.86 with the Android targets
- `cargo-ndk`

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
sdkmanager --install "ndk;28.2.13676358"
```

Then:

```bash
./gradlew :app:assembleDebug
```

The `cargoBuild` task cross-compiles the core and copies the `.so` files into
`app/src/main/jniLibs`. To iterate faster, cut `nativeAbis` in
`app/build.gradle.kts` down to `arm64-v8a`.

## Signing a release build

The release build is signed with a real key when `keystore.properties` exists
beside the project, and with the debug key otherwise. The file and the keystore
are both ignored by git and must stay that way.

```bash
keytool -genkey -v -keystore square-release.jks -keyalg RSA \
        -keysize 2048 -validity 10000 -alias square
```

```properties
# keystore.properties, never commit this
storeFile=square-release.jks
storePassword=…
keyAlias=square
keyPassword=…
```

## Desktop (Windows & Linux)

The desktop app (`:desktop`) shares core logic, lyrics, Subsonic, and automix planners with Android via `:shared:core`, `:shared:ui`, and `:core:audio-dsp`, and drives audio + Win32 frameless windowing + SMTC through `native/windows/` (`square_win_native`).

### Building `native/windows` on Linux

Requirements: CMake ≥ 3.24, Ninja, C++20 compiler (`g++`/`clang++`), JDK 17 (`JAVA_HOME`), and `native-dsp/bungee` (cloned automatically by `./gradlew :app:fetchBungee`). Optional: `libcurl` dev headers for Linux HTTP streaming (`http_stream_curl.cpp`; otherwise `http_stream_linux_stub.cpp` is used for local files).

```bash
./gradlew :app:fetchBungee
cmake -S native/windows -B native/windows/build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build native/windows/build
ctest --test-dir native/windows/build --output-on-failure
```

`:desktop:run` and `:desktop:prepareAppResources` automatically run `:desktop:squareWinNativeLinux` to copy `libsquare_win_native.so` into `desktop/appResources/linux/`.

### Building `native/windows` on Windows

Requirements: Visual Studio 2022 (MSVC x64 toolset), CMake ≥ 3.24, Ninja, JDK 17 (`JAVA_HOME` pointing to JDK 17 for JNI headers), JDK 21 (for Gradle), and `native-dsp/bungee` (`git clone --depth 1 --branch v2.4.24 --recurse-submodules --shallow-submodules https://github.com/bungee-audio-stretch/bungee.git native-dsp/bungee`).

From an **x64 Native Tools Command Prompt** (or after `ilammy/msvc-dev-cmd`):

```cmd
cmake -S native\windows -B native\windows\build -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded
cmake --build native\windows\build
ctest --test-dir native\windows\build --output-on-failure
mkdir desktop\appResources\windows 2>nul
copy /Y native\windows\build\square_win_native.dll desktop\appResources\windows\square_win_native.dll
```

### Tests, `--self-check`, `--soak-test`, and packaging

```bash
# Desktop & shared unit tests
./gradlew --console=plain :core:audio-dsp:desktopTest :shared:core:desktopTest :desktop:test

# Deterministic native DSP / Automix / ONNX / analyzer verification (exits 0/1)
./gradlew -q :desktop:run --args=--self-check

# 10-cycle window/mini-player/tray RSS & audio underrun soak test (Linux X11)
# XMODIFIERS=@im=none avoids an OpenJDK XIM disposeXIC hang under fcitx during rapid window teardown.
XMODIFIERS=@im=none DISPLAY=:1 ./gradlew --console=plain :desktop:run --args=--soak-test

# Self-contained runtime image (minimized via jlink)
./gradlew :desktop:createDistributable
./desktop/build/compose/binaries/main/app/square-yuma/bin/square-yuma --self-check
```

**Linux `.deb` launcher note (`MALLOC_ARENA_MAX`)**: `jpackage`'s native binary launcher does not export environment variables before glibc initializes arenas. Packaged Linux `.deb` desktop entries/wrapper scripts should invoke the binary with `MALLOC_ARENA_MAX=2` (e.g., `Exec=env MALLOC_ARENA_MAX=2 /opt/square-yuma/bin/square-yuma %U`) to prevent ~60+ MB of multi-arena RSS sprawl before `NativeEngine.init()`.

### Runtime data, logs, and diagnostic reports

Everything written by the desktop app lives under `~/.square-yuma/` (`%USERPROFILE%\.square-yuma\` on Windows):

- `logs/square-yuma.log` (and `.1`, `.2`): rolling stderr/crash log (512 KB × 3 files)
- `reports/square-yuma-report-<version>-<timestamp>.zip`: diagnostics bundles saved from **Settings → About → Save a report** (secrets redacted; local paths excluded unless ticked)
- `self-check.txt`: output of the last `--self-check` run (included in diagnostic reports)
- `analysis/`, `local_index.json`, `local_folders.json`, `playlists/`, `downloads.json`, `queue.json`, `listening.json`

### Cutting a `yuma-pc-vX.Y.Z` release

Desktop releases use `yuma-pc-v*` tags independently of Android `v*` tags:

1. Bump `desktopVersion` in `desktop/build.gradle.kts` (never change `upgradeUuid = "67fc8a84-7a38-4b2f-bb0f-8df4ac7267af"`, or MSI upgrades will break).
2. Commit and push to GitHub, then create and push tag `yuma-pc-vX.Y.Z` (or run `.github/workflows/desktop-windows.yml` via `workflow_dispatch` first to verify).
3. Create the GitHub Release for `yuma-pc-vX.Y.Z`; `.github/workflows/desktop-windows.yml` builds `Square-Yuma-PC-X.Y.Z-windows-x64.msi` and `Square-Yuma-PC-X.Y.Z-windows-x64.zip` and uploads them to the release.

## Notes for anyone reading the code

**`librespot-core` is patched.** A local copy under `native/vendor/`, wired in
with `[patch.crates-io]`. Five changes: the advertised OS is pinned to
`"linux"` so it agrees with the desktop client id, Mercury gained POST and
header fields so listening events can be posted, the session carries a language
so artwork comes back in it, a non-premium account no longer exits the process,
and audio keys are retried, remembered and their refusals reported. All five are explained in
[native/vendor/README.md](../native/vendor/README.md).

**OAuth uses the keymaster client id on a fixed port.** The redirect has to be
exactly `http://127.0.0.1:5588/login`, the only one registered for that id, and
the socket must be bound to the IPv4 loopback explicitly: on Android
`InetAddress.getLoopbackAddress()` answers `::1` and the browser then fails the
redirect with `ERR_CONNECTION_REFUSED`.

**`MediaSession.Callback` is not optional.** A session never hands a
controller's `MediaItem`s to the player directly; it asks the app to resolve
them first, and the default implementation rejects every item without a
playable URI. Ours carry only a `mediaId`, so without `onAddMediaItems` the call
vanishes silently.

**Do not rebuild the playlist in `getState()`.** It runs on every
`invalidateState`, which includes each position update: rebuilding dozens of
`MediaItemData` twice a second makes enough garbage to be heard as stuttering.
The list is cached and dropped only when the queue actually changes.

**Keep NewPipeExtractor current.** YouTube breaks extraction deliberately and
often, and a version a few months stale does not fail loudly: v0.24.6 still
searched fine while every stream answered "The page needs to be reloaded". If
YouTube playback stops working and search does not, bump that version first.

**`vergen` is pinned to 9.0.6.** `librespot-core` 0.8.0's build script does not
compile against 9.1.0, a semver-compatible bump that changed the `Add` trait.
The pin lives in `native/Cargo.lock`.

**Token refresh is serialised.** `TokenStore.validAccessToken()` takes a mutex
before refreshing. Spotify rotates refresh tokens, so without it two parallel
requests around expiry would each refresh and the loser would save a token that
is already dead. It is the bug behind the random logouts in most other clients.
