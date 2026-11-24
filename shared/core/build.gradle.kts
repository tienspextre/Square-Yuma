plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")

    sourceSets {
        // Both targets are JVMs: the JDK, OkHttp and org.json are used from commonMain, and each target supplies them.
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.timber)
            implementation("com.squareup.okhttp3:okhttp:5.3.2") // what the app resolves to; the lyrics clients need its non-null body
            implementation(libs.kotlinx.coroutines.android)
        }
        val desktopMain by getting {
            dependencies {
                implementation("com.squareup.okhttp3:okhttp:5.3.2") // what the Android app resolves to; LrcLib needs its non-null body
                implementation("org.json:json:20240303")
                implementation("net.sf.kxml:kxml2:2.3.0") // XmlPullParser for the TTML reader
            }
        }
        commonTest.dependencies {
            implementation("junit:junit:4.13.2")
        }
        val desktopTest by getting {
            dependencies { implementation("org.json:json:20240303") }
        }
    }
}

android {
    namespace = "dev.lelonio.square.shared.core"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
