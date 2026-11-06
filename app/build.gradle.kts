import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * ABIs to build the native core for.
 *
 * arm64 covers every phone made in the last decade, and each extra ABI is a full
 * extra Rust build (~2 min from cold), so the default is deliberately just the
 * one. Add "x86_64" for the emulator and "armeabi-v7a" for pre-2015 hardware.
 */
val nativeAbis = listOf("arm64-v8a", "armeabi-v7a")

/** NDK used for both AGP and the Cargo cross-build; keep the two in step. */
val ndkVersionForCargo = "28.2.13676358"

/** Maps Android ABI names to Rust target triples. */
val rustTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
)

/**
 * The release signing details, when there are any.
 *
 * Kept in `keystore.properties` beside the project and never committed: a
 * keystore in a repository is a keystore anyone can sign as you with. When the
 * file is absent — a fresh clone, or anyone building this who is not its
 * author — the release build falls back to the debug key, which is fine for
 * running it and useless for distributing it.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "dev.lelonio.square"
    compileSdk = 37
    ndkVersion = ndkVersionForCargo

    defaultConfig {
        applicationId = "dev.lelonio.square"
        // cpal's Android host is AAudio, which the ndk crate gates at API 26.
        minSdk = 29 // Accompanist lyrics-ui (the synced-lyrics view) declares 29; YumaPlayer forces it onto 26 instead.
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        buildConfigField("boolean", "VERBOSE_LOG", "false")

        // Bungee only for the ABIs that ship; see the splits below.
        externalNativeBuild {
            cmake {
                abiFilters += nativeAbis
            }
        }
    }

    // One APK per ABI, and a universal one for whoever does not know which
    // their phone is. The split sets the ABIs, so the NDK filter it would
    // conflict with is not set; CMake is told the same list, or it would build
    // Bungee for ABIs nothing ships.
    splits {
        abi {
            isEnable = true
            reset()
            include(*nativeAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The real key when there is one, the debug key otherwise — see
            // keystoreProperties above. A build signed with the debug key runs
            // and can be measured; it must never be the one handed to anyone,
            // because every machine's debug key is the same well-known one.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }

        /**
         * What to build while working on the app.
         *
         * A profile of a one-line-change build: R8 3m43s of a 4m12s total, then
         * lint-vital at 50s and the Compose mapping at 32s. Compiling the actual
         * Kotlin was ten seconds. Shrinking a build that is going straight onto
         * a test phone buys nothing and costs almost the entire wall clock.
         *
         * Not `debug`, though — a debuggable build turns off the ART optimiser
         * and leaves Compose's own debug instrumentation in, so scrolling and
         * animation measure far worse than they really are, which is the thing
         * this app is mostly being judged on. This is release without the
         * shrinker: same runtime behaviour, none of the wait.
         *
         * Verify on `release` before shipping — R8 is also what would surface a
         * missing keep rule (reflection over the Retrofit and serialization
         * models above all), and that failure only ever appears in a minified
         * build.
         */
        create("dev") {
            initWith(getByName("release"))
            // The Yuma modules (:core:audio-dsp) have only debug and release.
            matchingFallbacks += listOf("release")
            isMinifyEnabled = false
            isShrinkResources = false
            // This build type is release-shaped, so BuildConfig.DEBUG is false
            // here and anything gated on it stays off — including the log the
            // vendored InnerTube module writes through Timber, which is the
            // only view there is of a page that came back in the wrong shape.
            buildConfigField("boolean", "VERBOSE_LOG", "true")
            // The real key when there is one: a build signed with a different
            // key than the copy already on the phone cannot replace it, and
            // uninstalling first would take the login and the saved queue with
            // it every time.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        // Runs on demand (`./gradlew lint`) rather than inside every release
        // build. It is 50 seconds of a build whose output is a local test
        // install, and it has never been the thing that caught a problem here.
        checkReleaseBuilds = false
    }

    compileOptions {
        // Required by the vendored :innertube module, which reads java.time.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        // Off by default since AGP 8; SquareApplication reads BuildConfig.DEBUG.
        buildConfig = true
    }

    // Bungee and its JNI wrapper. The Rust core is not built here — it is
    // cross-compiled by the cargoBuild task and dropped into jniLibs.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    packaging {
        // The Rust cdylib is already stripped by the release profile; letting
        // Gradle re-strip it with the wrong tool breaks the arm64 build.
        jniLibs.keepDebugSymbols += "**/libsquarecore.so"
        // The universal APK would otherwise carry the x86 builds some
        // dependencies ship, without the Rust core: installable on an x86
        // device, and closing at once there.
        jniLibs.excludes += listOf("lib/x86/**", "lib/x86_64/**")
        // Dex compressed in the APK. From minSdk 28 AGP stores it raw so the
        // phone can map it in place, which nearly tripled the file passed
        // around (44 MB to 122 MB when minSdk went to 29); the phone compiles
        // it ahead of time on install either way.
        dex.useLegacyPackaging = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The real org.json for the JVM tests: android.jar carries only stubs.
    testImplementation("org.json:json:20240303")
    implementation(libs.timber)
    implementation(libs.androidx.core.ktx)
    // Installs baseline-prof.txt on first run, so the code the bar and the
    // player use is compiled before it is needed rather than while it runs.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    // An isolated JavaScript engine, no page around it: runs the challenge
    // solver YouTube's signed-in streams need; see PlayerChallenges.
    implementation(libs.androidx.javascriptengine)
    implementation(libs.reorderable)
    // The synced-lyrics view YumaPlayer uses: several lines lit at once, background vocals, no per-frame recomposition.
    implementation(libs.accompanist.lyrics.ui)
    implementation(libs.accompanist.lyrics.core)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.common)
    implementation(libs.media3.session)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ffmpeg.decoder)
    // DASH, for Spotify's own video: its manifest is a set of segment
    // templates, which is what a DASH source is built to walk.
    implementation(libs.media3.exoplayer.dash)
    // Apple's animated covers are HLS; see AppleCatalog and MotionCover.
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.newpipe.extractor)
    implementation(project(":innertube"))
    implementation(project(":core:audio-dsp"))
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.backdrop)
    implementation(libs.kyant.shapes)
    implementation(libs.phosphor)

    coreLibraryDesugaring(libs.desugaring)
}

/** Pinned so a build is not at the mercy of upstream's next commit. */
val bungeeTag = "v2.4.24"

/**
 * Clones Bungee, the time-stretching library behind the speed and pitch
 * controls.
 *
 * Fetched rather than vendored: Bungee is MPL-2.0, and keeping the licensed
 * sources exactly as upstream published them — pinned to a tag, never edited —
 * keeps the obligation simple. The build only compiles them; the patched copy
 * of librespot under native/vendor is vendored precisely because it *is*
 * modified, and that difference is the reason for treating the two differently.
 */
val fetchBungee by tasks.registering {
    group = "build"
    description = "Clones the Bungee time-stretch library if it is not present"

    // Everything is resolved here rather than inside the action: a task action
    // that touches the build script gets captured with it, which the
    // configuration cache refuses to serialize.
    val target = rootProject.file("native-dsp/bungee")
    val marker = target.resolve("bungee/Bungee.h")
    val tag = bungeeTag
    val tries = 3
    val retryMs = 5_000L
    outputs.dir(target)

    doLast {
        if (marker.exists()) return@doLast

        // git's own words kept and put in the failure. Inherited, they went to
        // a daemon's output that CI never shows, and a release failed on
        // "could not clone" with nothing to say which host refused. Tried a
        // few times: the submodules come from three different hosts, and any
        // one of them having a bad minute was a failed release.
        var output = ""
        repeat(tries) { attempt ->
            target.deleteRecursively()
            target.parentFile.mkdirs()
            val process = ProcessBuilder(
                "git", "clone",
                "--depth", "1",
                "--branch", tag,
                "--recurse-submodules", "--shallow-submodules",
                "https://github.com/bungee-audio-stretch/bungee.git",
                target.absolutePath,
            ).redirectErrorStream(true).start()
            output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0 && marker.exists()) return@doLast
            logger.warn("Bungee clone attempt ${attempt + 1} of $tries failed:\n$output")
            if (attempt < tries - 1) Thread.sleep(retryMs * (attempt + 1))
        }
        error("could not clone Bungee $tag into $target:\n$output")
    }
}


/**
 * Cross-compiles the Rust core and drops the resulting `.so` files straight into
 * `jniLibs`.
 *
 * This shells out to `cargo-ndk` instead of using externalNativeBuild because
 * the crate is Cargo-driven, not CMake-driven. Install it once with
 * `cargo install cargo-ndk` and add the targets with `rustup target add`.
 */
val cargoExists = false

val cargoBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compiles the librespot core for all configured ABIs"
    enabled = cargoExists

    val nativeDir = rootProject.file("native")
    workingDir = nativeDir

    if (cargoExists) {
        inputs.dir(nativeDir.resolve("src"))
        inputs.dir(nativeDir.resolve("vendor"))
        inputs.file(nativeDir.resolve("Cargo.toml"))
        inputs.file(nativeDir.resolve("Cargo.lock"))
        outputs.dir(layout.projectDirectory.dir("src/main/jniLibs"))

        val outputDir = layout.projectDirectory.dir("src/main/jniLibs").asFile.absolutePath
        val abiArgs = nativeAbis.flatMap { listOf("-t", it) }
        val cargo = File(System.getProperty("user.home"), ".cargo/bin/cargo")
            .takeIf { it.canExecute() }?.absolutePath ?: "cargo"
        commandLine(
            listOf(cargo, "ndk") + abiArgs +
                listOf("-P", "26", "-o", outputDir, "build", "--release"),
        )

        val ndkDir = File(android.sdkDirectory, "ndk/$ndkVersionForCargo")
        if (ndkDir.isDirectory) {
            environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
        }
        File(System.getProperty("user.home"), ".cargo/bin").takeIf { it.isDirectory }?.let {
            environment("PATH", "${it.absolutePath}:${System.getenv("PATH")}")
        }
    } else {
        commandLine("true")
    }
}

if (cargoExists) {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
        .configureEach { dependsOn(cargoBuild) }
}

// CMake reads Bungee's sources at configure time, so the clone has to have
// happened before any of the native build tasks run.
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(fetchBungee) }
