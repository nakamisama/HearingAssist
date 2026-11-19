import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseProperties = Properties().apply {
    rootProject.file("release.properties").inputStream().use { load(it) }
}
val releaseName = (project.findProperty("appVersionName") as String?) ?: releaseProperties.getProperty("versionName")
val releaseCode = ((project.findProperty("appVersionCode") as String?) ?: releaseProperties.getProperty("versionCode"))
    .toIntOrNull() ?: error("Invalid release version code")
require(releaseCode in 1..2_100_000_000) { "Release version code is outside Android's range" }
require(releaseName != null && Regex("bate[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}").matches(releaseName)) {
    "Release version name must use bateX.Y.Z"
}

android {
    namespace = "com.hearassist"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hearassist"
        minSdk = 26
        targetSdk = 34
        // 对外名称和 Android 整数序号分别由唯一版本配置提供；CI 重试不推进版本。
        versionCode = releaseCode
        versionName = releaseName
        // 荣耀 X50 是 arm64；保留 32 位 armeabi-v7a 以兼容更老的机型
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // 沿用现有签名以兼容已安装版本。现有密钥曾入库，需要另行规划密钥迁移；
    // 公开签名私钥存在冒用风险，不能将个人自用等同于无风险。
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

    buildFeatures {
        // 生成 BuildConfig.VERSION_CODE，用于新版本检测对比
        buildConfig = true
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

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
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
    implementation("androidx.lifecycle:lifecycle-process:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303")

    // 繁体 → 简体中文转换（纯 Java 实现，无额外体积负担）
    implementation("com.github.houbb:opencc4j:1.8.0")

    // 解压模型 tar.bz2 包（模型按需下载，不打包进 APK）
    implementation("org.apache.commons:commons-compress:1.26.1")
}
