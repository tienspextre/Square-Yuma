plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(17)
    androidTarget {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=androidx.media3.common.util.UnstableApi")
        }
    }
    jvm("desktop")

    sourceSets {
        // Both targets are JVMs: org.json and ONNX Runtime are used from commonMain, and each target supplies its own.
        commonMain.dependencies {
            implementation(project(":shared:core"))
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.media3.common)
            implementation(libs.media3.exoplayer)
            implementation(libs.timber)
            implementation(libs.kotlinx.coroutines.android)
            implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
        }
        val desktopMain by getting {
            dependencies {
                implementation("org.json:json:20240303")
                implementation("com.microsoft.onnxruntime:onnxruntime:1.28.0")
            }
        }
        commonTest.dependencies {
            implementation("junit:junit:4.13.2")
            implementation("org.json:json:20240303")
        }
        val androidUnitTest by getting {
            dependencies {
                implementation("junit:junit:4.13.2")
                implementation("org.json:json:20240303")
            }
        }
    }
}

android {
    namespace = "moe.rukamori.archivetune.audiodsp"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// `:core:audio-dsp:test` runs both targets' unit tests.
tasks.matching { it.name == "test" }.configureEach { dependsOn("desktopTest") }
