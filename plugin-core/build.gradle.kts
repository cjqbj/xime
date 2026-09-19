plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kingzcheung.xime.plugin.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
}

dependencies {
    constraints {
        implementation("org.jetbrains:annotations:23.0.0")
    }
    
    api(kotlin("stdlib"))
    api(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.core)

    api(platform(libs.androidx.compose.bom))
    api("androidx.compose.runtime:runtime")
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.activity.compose)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.lifecycle.runtime.compose)

    // Lua 脚本插件运行时（沙箱执行 main.lua，替代 DEX 加载）
    api("org.luaj:luaj-jse:3.0.1")
    // manifest.yaml 解析（Lua 模式插件元数据），与 app 统一使用 kaml 类型化解析，
    // 避免引入 org.yaml:snakeyaml（其 java.beans 反射在 Android 上不可用）
    implementation(libs.kaml)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
    
    testImplementation("junit:junit:4.13.2")
}
