import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.jetbrains.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation("org.json:json:20240303")

    // Liquid Glass shapes & icons & lyrics (JVM / Desktop native artifacts)
    implementation(libs.kyant.shapes)
    implementation(libs.phosphor)
    implementation(libs.accompanist.lyrics.core)
    implementation(libs.accompanist.lyrics.ui)
    implementation(libs.reorderable)

    // Pure JVM Lossless / Multi-format Audio Decoders (FLAC 16/24-bit, MP3, OGG Vorbis, AAC/M4A, ALAC)
    implementation("com.tianscar.javasound:javasound-flac:1.4.1")
    implementation("com.tianscar.javasound:javasound-mp3:1.9.8")
    implementation("com.tianscar.javasound:javasound-vorbis:2.1.0")
    implementation("com.tianscar.javasound:javasound-aac:0.9.8")
    implementation("com.tianscar.javasound:javasound-alac:0.2.3")
}

compose.desktop {
    application {
        mainClass = "dev.lelonio.square.desktop.MainKt"

        nativeDistributions {
            targetFormats(
                TargetFormat.Deb,
                TargetFormat.AppImage,
                TargetFormat.Msi,
                TargetFormat.Exe,
            )
            packageName = "Square-Yuma"
            packageVersion = "1.1.1"
            description = "Square × Yuma Desktop — Liquid Glass Lossless & Automix Music Player"
            vendor = "Square-Yuma Open Source"
            modules("java.instrument", "java.management", "java.naming", "java.prefs", "java.sql", "java.xml", "jdk.unsupported")

            linux {
                iconFile.set(project.file("src/jvmMain/resources/icon.png"))
                shortcut = true
                menuGroup = "AudioVideo;Audio;Player;"
            }
            windows {
                iconFile.set(project.file("src/jvmMain/resources/icon.ico"))
                shortcut = true
                menuGroup = "Square-Yuma"
                upgradeUuid = "8f45a23e-9a14-4d62-b8bc-91f8459e1201"
                dirChooser = true
                perUserInstall = false
            }
        }
    }
}
