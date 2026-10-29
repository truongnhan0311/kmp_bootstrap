import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// appkit-ui: component Material 3 + theme sinh từ CSS shadcn (tools/shadcn-theme.mjs) + icon SVG. Chỉ cần appkit-core (bộ đọc SVG).
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

// -PdesktopOnly=true: chỉ dựng JVM (CI, sandbox không có Android SDK/Xcode). Mặc định dựng Android + iOS + desktop.
val desktopOnly = providers.gradleProperty("desktopOnly").orNull == "true"
group = "kmp.bootstrap"   // để includeBuild thay được `kmp.bootstrap:appkit-ui` bằng module này
version = "0.1.0"

if (!desktopOnly) apply(plugin = "com.android.library")
if (desktopOnly) {
    // Môi trường chỉ có Maven Central: bỏ hai thư viện androidx mà metadata Compose còn trỏ tới nhưng bản desktop không dùng.
    configurations.configureEach {
        exclude(group = "androidx.annotation")
        exclude(group = "androidx.lifecycle")
    }
}

kotlin {
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    if (!desktopOnly) {
        androidTarget { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
        iosX64(); iosArm64(); iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":appkit-core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        if (!desktopOnly) {
            val androidMain by getting { dependencies { implementation(libs.androidx.activity.compose) } }
        }
        val desktopMain by getting { dependencies { implementation(compose.desktop.currentOs) } }
    }
}

if (!desktopOnly) apply(from = "android.gradle.kts")
