plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.killbill"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.killbill"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // 自用机是 Android 15 arm64。ML Kit OCR 的 .so 四个架构全带会 42MB，
        // 只留 arm64 砍掉三分之二体积。换 32 位老设备再放开 armeabi-v7a。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // 个人自用：release 直接用 debug 签名出包，省得管 keystore
        create("personalRelease") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("personalRelease")
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
        compose = true
        viewBinding = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        // 本项目零 Fragment（全 ComponentActivity + Compose），
        // registerForActivityResult 的 Fragment 版本检查是误报，压掉。
        disable += "InvalidFragmentVersionForActivityResult"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    // 2.8.1 的 collectAsStateWithLifecycle 消费 lifecycle 版 LocalLifecycleOwner，
    // 没人提供 → 启动即崩（用户 ColorOS 闪退根因）。2.8.3+ 回退读 compose-ui 版，必须升。
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 本地中文 OCR（模型经 Play Services 下发；无 GMS 设备运行时会失败，管线自动回退全图）
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")

    // Robolectric：JVM 上跑真实 Android framework（无模拟器测试）
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.room:room-testing:2.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test> {
    maxHeapSize = "2g"
}
