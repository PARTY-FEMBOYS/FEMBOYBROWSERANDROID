plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.djanime.femboybrowser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.djanime.femboybrowser"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Для личной раздачи подписываем debug-ключом, чтобы release-APK сразу ставился на телефон.
            // Для Google Play нужен свой ключ (см. README).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // mp4-фоны лежат в assets и должны быть НЕсжатыми, иначе MediaPlayer не сможет их открыть
    androidResources { noCompress += listOf("mp4", "webm") }
}
