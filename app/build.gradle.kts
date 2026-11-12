plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.chinta44.endoview"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chinta44.endoview"
        minSdk = 29
        targetSdk = 34
        versionCode = 11
        versionName = "0.5.1"

        // 内視鏡は arm 系のみで十分。ライブラリ同梱の so に合わせる
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // UVCカメラライブラリ (saki4510t/UVCCamera 系, Maven Central 版)
    // プレビュー / 写真 / 動画 / UVC制御 (明るさ・コントラスト等) を提供
    implementation("com.herohan:UVCAndroid:1.0.13")
}
