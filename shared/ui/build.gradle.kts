plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            implementation(libs.phosphor)
            implementation(libs.accompanist.lyrics.ui)
            implementation(libs.accompanist.lyrics.core)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}

android {
    namespace = "dev.lelonio.square.shared.ui"
    compileSdk = 37
    defaultConfig { minSdk = 29 } // the synced-lyrics view (Accompanist lyrics-ui) declares 29
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Enforced: the CMP libraries ask for a newer androidx Compose than AGP 8 accepts, so the phone stays on the BOM's.
    "androidMainImplementation"(enforcedPlatform(libs.compose.bom))
}
