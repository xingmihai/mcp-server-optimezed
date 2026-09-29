plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 版本号可由 CI 注入：./gradlew assembleRelease -PAPP_VERSION_NAME=1.0.1 -PAPP_VERSION_CODE=12
// 本地直接构建时不传参数，回退到下面的默认值
val appVersionName: String = (project.findProperty("APP_VERSION_NAME") as String?) ?: "1.0"
val appVersionCode: Int = ((project.findProperty("APP_VERSION_CODE") as String?) ?: "1").toInt()

// 签名信息全部从环境变量读取（CI 注入），本地无环境变量时自动回退 debug 签名
// 注意：这里必须用 project.file() 构造 File，Kotlin DSL 脚本内不能写 java.io.File
val keystoreFile = System.getenv("SIGNING_KEYSTORE_PATH")
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
val signingReady: Boolean = keystoreFile?.exists() == true

android {
    namespace = "com.mcp.server"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mcp.server"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            if (signingReady) {
                storeFile = keystoreFile
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (signingReady) {
                signingConfigs.getByName("release")
            } else {
                println("⚠️  未检测到签名配置，Release 将使用 debug 签名")
                signingConfigs.getByName("debug")
            }
            // 默认沿用仓库原设置；CI 可用 -PMINIFY=true 打开混淆
            isMinifyEnabled = (project.findProperty("MINIFY") as String?)?.toBoolean() ?: false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.code.gson:gson:2.11.0")
    // Shizuku：以 shell 权限执行系统命令
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // QuickJS：mcp_javascript 内置 JavaScript 引擎（ES2020）
    implementation("io.github.taoweiji.quickjs:quickjs-android:1.4.6")
}
