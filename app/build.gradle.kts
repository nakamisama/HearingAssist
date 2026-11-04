plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hearassist"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hearassist"
        minSdk = 26
        targetSdk = 34
        // 版本号由 CI 传入（GitHub run number），每次构建自动递增，保证可覆盖更新
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = "1.1.0"
        // 荣耀 X50 是 arm64；保留 32 位 armeabi-v7a 以兼容更老的机型
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // 固定 release 签名：密钥提交在仓库根目录，所有构建用同一密钥，
    // 保证 APK 可直接覆盖更新（个人自用 App，密钥公开无安全风险）
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.keystore")
            storePassword = "hearassist2026"
            keyAlias = "hearassist"
            keyPassword = "hearassist2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // sherpa-onnx 原生库不做压缩，加载更快
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

dependencies {
    // sherpa-onnx v2.0.1：VAD(Silero) + Whisper 离线识别 + 音频打标（环境音）
    // AAR 由 CI 在构建时下载到 app/libs/（见 .github/workflows/build-apk.yml）
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 繁体 → 简体中文转换（纯 Java 实现，无额外体积负担）
    implementation("com.github.houbb:opencc4j:1.8.0")
}
