import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jetbrains.compose)
}

/** Desktop releases are numbered on their own (tags yuma-pc-vX.Y.Z); the Android versionName is not touched. */
val desktopVersion = "1.2.0"

/**
 * The Android files the desktop app compiles as they are: the Subsonic client,
 * the theme, and the lyrics view. Copied in at build time rather than forked, so
 * a change on the phone is a change here too. Anything Android-only they call
 * has a desktop file of the same name under src/main/kotlin.
 */
val sharedSources by tasks.registering(Sync::class) {
    from("../app/src/main/java") {
        include(
            "dev/lelonio/square/backend/subsonic/SubsonicClient.kt",
            "dev/lelonio/square/backend/subsonic/SubsonicModels.kt",
            "dev/lelonio/square/backend/subsonic/SubsonicMixes.kt",
            "dev/lelonio/square/data/LyricsModels.kt",
            "dev/lelonio/square/backend/lyrics/LrcLib.kt",
            "dev/lelonio/square/backend/lyrics/TrackTitle.kt",
            "dev/lelonio/square/backend/lyrics/Ttml.kt",
            "dev/lelonio/square/backend/lyrics/Lossless.kt",
            "dev/lelonio/square/backend/lyrics/BetterLyrics.kt",
            "dev/lelonio/square/backend/lyrics/NetEase.kt",
            "dev/lelonio/square/backend/lyrics/KuGou.kt",
            "dev/lelonio/square/backend/lyrics/LyricsOvh.kt",
            "dev/lelonio/square/backend/lyrics/Translate.kt",
            "dev/lelonio/square/ui/player/ClassicLyricsView.kt",
            "dev/lelonio/square/ui/theme/Theme.kt",
            "dev/lelonio/square/ui/theme/PageTones.kt",
            "dev/lelonio/square/ui/player/LyricsView.kt",
            "dev/lelonio/square/ui/player/LyricsTiming.kt",
            "dev/lelonio/square/ui/glass/**",
            "dev/lelonio/square/ui/player/GlassSurface.kt",
            "dev/lelonio/square/ui/player/GlassProgressBar.kt",
            "dev/lelonio/square/ui/components/GlassMenu.kt",
        )
        // Android's halves of the glass; src/main/kotlin has the Skiko ones.
        exclude(
            "dev/lelonio/square/ui/glass/GlassPlatform.kt",
            "dev/lelonio/square/ui/glass/Coroutines.kt",
            "dev/lelonio/square/ui/glass/WaveformSeekBar.kt", // reads a media3 Player
            "dev/lelonio/square/ui/glass/backdrop/Platform.kt",
            "dev/lelonio/square/ui/glass/backdrop/RuntimeShaderAndroid.kt",
            "dev/lelonio/square/ui/glass/backdrop/internal/RenderEffect.kt",
            "dev/lelonio/square/ui/glass/backdrop/internal/Paint.kt",
        )
    }
    into(layout.buildDirectory.dir("shared/kotlin"))
}

val sharedResources by tasks.registering(Sync::class) {
    from("../app/src/main/res/font/inter.ttf") { into("font") }
    from("../docs/square.png")
    into(layout.buildDirectory.dir("shared/resources"))
}

sourceSets.main {
    kotlin.srcDir(sharedSources)
    resources.srcDir(sharedResources)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation(libs.phosphor)
    implementation(libs.accompanist.lyrics.ui)
    implementation(libs.accompanist.lyrics.core)
    implementation(libs.kotlinx.serialization.json)
    implementation("com.squareup.okhttp3:okhttp:5.3.2") // what the Android app resolves to; the shared LrcLib needs its non-null body
    implementation("org.json:json:20240303")
    implementation("net.sf.kxml:kxml2:2.3.0") // XmlPullParser for the shared TTML reader
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.coroutines.get()}")
}

compose.desktop.application {
    mainClass = "dev.lelonio.square.desktop.MainKt"
    jvmArgs += "-Dsquare.version=$desktopVersion"
    nativeDistributions {
        targetFormats(TargetFormat.Deb, TargetFormat.Msi)
        // The Windows CI drops a static ffmpeg.exe into appResources/windows; Linux uses the system one.
        appResourcesRootDir.set(project.layout.projectDirectory.dir("appResources"))
        packageName = "square-yuma"
        packageVersion = desktopVersion
        description = "Navidrome/Subsonic music player"
        vendor = "Square-Yuma"
        licenseFile.set(project.file("../LICENSE"))
        modules("java.naming", "java.prefs", "java.sql", "jdk.unsupported", "java.net.http")
        linux {
            iconFile.set(project.file("../docs/square.png"))
            shortcut = true
            menuGroup = "AudioVideo;Audio;Player"
            appCategory = "sound"
            debMaintainer = "tienspextre@users.noreply.github.com"
        }
        windows {
            iconFile.set(project.file("../docs/square.ico"))
            shortcut = true
            menu = true
            menuGroup = "Square-Yuma"
            dirChooser = true
            perUserInstall = true
            upgradeUuid = "67fc8a84-7a38-4b2f-bb0f-8df4ac7267af" // fixed forever, so a new MSI upgrades the old one
        }
    }
    buildTypes.release.proguard { isEnabled = false }
}
