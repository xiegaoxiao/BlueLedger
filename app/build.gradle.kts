plugins {
    // AGP 9 起 Kotlin 支持内置于 Android 插件，**不能**再套 org.jetbrains.kotlin.android，
    // 否则构建直接失败（见 https://kotl.in/gradle/agp-built-in-kotlin）。
    // Compose / serialization 编译器插件仍然单独应用。
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.blueledger.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.blueledger.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Room schema 导出目录由 KSP 参数指向，提交进版本库以便做迁移测试。
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Debug 使用与 release 相同的 applicationId，便于用同一 adb 包名验收。
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        // minSdk 26 已原生提供 java.time，无需脱糖。
        isCoreLibraryDesugaringEnabled = false
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // Compose UI 测试需要真实资源（Robolectric）。
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.logging", "stdout")
                // Robolectric + Compose 的测试 JVM 是内存大户：把全部用例塞进同一个 JVM
                // 会在 60—100 个用例后 OutOfMemoryError，并表现为 Robolectric 静态注册表
                // （NativeObjRegistry / ShadowLineBreaker）只增不减导致的
                // "ArrayIndexOutOfBoundsException: Index N out of bounds for length N"。
                it.maxHeapSize = "3g"
                // forkEvery 计的是**测试类**数量，不是用例数量。
                // A7 的对照实验证明：真实页面 Compose 用例很重，多个重载 UI 类共用一个 JVM
                // 会耗尽堆，失败形态从空栈 AIOOBE 变成空栈 OOM。
                // 设为 1 = 每个测试类一个全新 JVM，彻底切断 Robolectric 静态注册表与内存的跨类累积。
                // 代价是每类一次 JVM 启动；换来的是结果可复现、失败可归因。
                it.forkEvery = 1
            }
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = false
    }
}

kotlin {
    // AGP 9 内置 Kotlin 提供的顶层 kotlin 扩展：统一用 JDK 21 工具链，
    // Kotlin jvmTarget 与 Java source/target 一起跟随工具链，避免 17/21 不一致告警。
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

configurations.configureEach {
    // androidTest 的 lint 模型（:app:generateDebugAndroidTestLintModel）需要解析
    // androidTestCompileClasspath，其中 espresso-core:3.7.0 传递依赖
    // com.google.errorprone:error_prone_annotations:2.30.0。
    // 该版本不在本机共享 Gradle 缓存中，而构建机网络受限时 dl.google.com 不可达。
    // 该库只提供编译期注解（RetentionPolicy.CLASS），强制到缓存中已有的更新版本
    // 不改变任何运行时行为，也不影响正式 APK 的依赖图。
    if (name.contains("AndroidTest")) {
        resolutionStrategy.force("com.google.errorprone:error_prone_annotations:2.36.0")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ── 单元测试（JVM）──
    // Room/业务/金额/时间：纯 JVM。
    // Compose UI 与布局适配：Robolectric 驱动真实 Compose 布局与节点 bounds。
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.room.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    // ── 仪器测试（需要真实设备/模拟器；本机无设备时为 0 执行）──
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
