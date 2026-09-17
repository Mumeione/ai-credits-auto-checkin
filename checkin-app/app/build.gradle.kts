import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名配置从项目根目录 keystore.properties 读取（不入库）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.checkin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.checkin"
        minSdk = 26
        targetSdk = 35
        // versionCode 必须严格递增，否则覆盖安装会报「应用未安装」
        // （v1.0.0 已发布为 versionCode 1；v1.1.x 使用 2；v1.2.0 使用 3；
        //   v1.3.0 使用 4；v1.4.0 使用 5；v1.4.1 使用 6）
        versionCode = 6
        versionName = "1.4.1"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // release 开启 R8：删除无用类/方法/字段 + 资源压缩，保留规则见 proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // 去掉 APK 里的 Play 依赖元数据块
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // 剔除依赖带进来的许可证 / 元数据等无用文件
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/*.md",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 后台任务调度
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // 网络请求
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 数据存储
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    // AndroidX 基础
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
}
