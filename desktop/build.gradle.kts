import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jetbrains.compose)
}

/** Desktop releases are numbered on their own (tags yuma-pc-vX.Y.Z); the Android versionName is not touched. */
val desktopVersion = "1.4.0"

/** The native C++20 engine + analysis library (native/windows), built for Linux; Windows CI builds the .dll. */
val squareWinNativeLinux by tasks.registering(Exec::class) {
    val srcDir = file("../native/windows")
    val buildDir = file("../native/windows/build")
    val out = file("appResources/linux/libsquare_win_native.so")
    onlyIf { System.getProperty("os.name").startsWith("Linux") }
    inputs.dir(file("../native/windows/src"))
    inputs.file(file("../native/windows/CMakeLists.txt"))
    inputs.dir(file("../app/src/main/cpp"))
    outputs.file(out)
    commandLine(
        "sh", "-c",
        """mkdir -p "${out.parentFile.path}" && cmake -S "${srcDir.path}" -B "${buildDir.path}" -G Ninja -DCMAKE_BUILD_TYPE=Release && cmake --build "${buildDir.path}" --target square_win_native && cp -f "${buildDir.path}/libsquare_win_native.so" "${out.path}"""",
    )
}
tasks.matching { it.name == "prepareAppResources" || it.name == "run" }.configureEach { dependsOn(squareWinNativeLinux) }

val sharedResources by tasks.registering(Sync::class) {
    from("../shared/ui/src/androidMain/res/font/inter.ttf") { into("font") }
    from("../docs/square.png")
    from("../app/src/main/assets") { include("*.onnx"); into("models") }
    into(layout.buildDirectory.dir("shared/resources"))
}

sourceSets.main {
    resources.srcDir(sharedResources)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared:core"))
    implementation(project(":shared:ui"))
    implementation(project(":core:audio-dsp"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation(libs.phosphor)
    implementation(libs.accompanist.lyrics.ui)
    implementation(libs.accompanist.lyrics.core)
    implementation(libs.kotlinx.serialization.json)
    implementation("com.squareup.okhttp3:okhttp:5.3.2") // what the Android app resolves to; the shared LrcLib needs its non-null body
    implementation("org.json:json:20240303")
    implementation("com.github.hypfvieh:dbus-java-core:5.2.2") // MPRIS on Linux
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.2")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.28.0") // the automix beat and vocal models, as on the phone
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.coroutines.get()}")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

compose.desktop.application {
    mainClass = "dev.lelonio.square.desktop.MainKt"
    jvmArgs += "-Dsquare.version=$desktopVersion"
    nativeDistributions {
        targetFormats(TargetFormat.Deb, TargetFormat.Msi)
        // Native engine binaries live in appResources/{windows,linux}.
        appResourcesRootDir.set(project.layout.projectDirectory.dir("appResources"))
        packageName = "square-yuma"
        packageVersion = desktopVersion
        description = "Navidrome/Subsonic music player"
        vendor = "Square-Yuma"
        licenseFile.set(project.file("../LICENSE"))
        modules("java.naming", "java.prefs", "java.sql", "jdk.unsupported", "java.net.http", "java.logging", "jdk.security.auth")
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
