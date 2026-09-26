import java.net.URI
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名密钥不进仓库。四个环境变量给全了才签：
//   HYPO_KEYSTORE_FILE / HYPO_KEYSTORE_PASSWORD / HYPO_KEY_ALIAS / HYPO_KEY_PASSWORD
// 没给就完全不配签名：debug 落回 AGP 自己生成的 debug keystore（换机器或换 CI runner 后签名
// 不一致，覆盖安装要先卸载），release 出未签名包。
// CI 用法见 .github/workflows/nightly.yml，本地见 README。
val keystorePath = System.getenv("HYPO_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "app.hypochlorite"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.hypochlorite"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.9.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        keystorePath?.let { path ->
            create("external") {
                storeFile = rootProject.file(path)
                storePassword = System.getenv("HYPO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HYPO_KEY_ALIAS")
                keyPassword = System.getenv("HYPO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("external")
        }
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("external")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// 识曲指纹是上游（网易）编出来的私有 wasm，**不进仓库**（见 .gitignore）。构建时从上游公开地址取一次，
// 写进 assets —— 上游把它以 base64 内嵌在 afp.wasm.js 里，解出来就是原始 wasm。
//
// 取不到只警告、不让构建失败：App 会优雅降级成假指纹（识别必然「没听出来」，识别页上有明显提示），
// 本地断网也照常能编。CI 那边由 workflow 里的一步断言文件存在，保证发出去的包不是半残。
val fingerprintWasm = layout.projectDirectory.file("src/main/assets/netease/afp.query.wasm")

// 同一份文件的两个来源：GitHub raw 为主，jsDelivr 兜底（国内网络常只通其中一个）。
val fingerprintWasmSources = listOf(
    "https://raw.githubusercontent.com/neteasecloudmusicapienhanced/api-enhanced/main/public/audio_match_demo/afp.wasm.js",
    "https://cdn.jsdelivr.net/gh/neteasecloudmusicapienhanced/api-enhanced@main/public/audio_match_demo/afp.wasm.js",
)
val fingerprintWasmBinary = Regex("WASM_BINARY\\s*=\\s*\"([A-Za-z0-9+/=]+)\"")
val minFingerprintWasmBytes = 100_000

val fetchFingerprintWasm by tasks.registering {
    group = "build"
    description = "从上游取听歌识曲的指纹 wasm 写进 assets（已存在就跳过）"
    outputs.file(fingerprintWasm)
    doLast {
        val target = fingerprintWasm.asFile
        if (target.isFile && target.length() > minFingerprintWasmBytes) {
            logger.lifecycle("指纹 wasm 已在本地，跳过下载")
            return@doLast
        }
        val source = fingerprintWasmSources.firstNotNullOfOrNull { url ->
            runCatching { URI(url).toURL().readBytes().toString(Charsets.UTF_8) }
                .onFailure { logger.info("取指纹 wasm 失败：$url（${it.message}）") }
                .getOrNull()
        }
        val encoded = source?.let { fingerprintWasmBinary.find(it)?.groupValues?.get(1) }
        val bytes = encoded?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        if (bytes == null || bytes.size < minFingerprintWasmBytes) {
            logger.warn("没取到指纹 wasm：识曲会退化成「没听出来」（界面上会写明资源缺失）")
            return@doLast
        }
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
        logger.lifecycle("指纹 wasm 已取到：${bytes.size} 字节 → ${target.relativeTo(projectDir)}")
    }
}

tasks.named("preBuild") { dependsOn(fetchFingerprintWasm) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media:media:1.7.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 听歌识曲的指纹：纯 JVM 的 wasm 解释器，跑上游那段网易的 C++→wasm 指纹生成器（零 native 代码）。
    implementation("com.dylibso.chicory:runtime:1.7.5")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.3")
    // 一起听扫邀请二维码。解码仍用上面的 zxing core，这层只提供相机页。
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar 里的 org.json 是桩，单元测试要换成真实现。
    testImplementation("org.json:json:20240303")
    // 识曲引擎是协程状态机，需要虚拟时钟测阶段流转与取消。
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // 只在手动跑「真歌端到端探针」时用：JVM 侧解 MP3，喂给指纹层验证整条链路。
    testImplementation("com.googlecode.soundlibs:jlayer:1.0.1.4")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
