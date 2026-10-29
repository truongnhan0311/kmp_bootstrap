import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// appkit-reminder: thông báo đẩy (FCM) — đăng ký thiết bị, điều hướng khi bấm thông báo. Cần appkit-network.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

// -PdesktopOnly=true: chỉ dựng JVM (CI, sandbox không có Android SDK/Xcode). Mặc định dựng Android + iOS + desktop.
val desktopOnly = providers.gradleProperty("desktopOnly").orNull == "true"
group = "kmp.bootstrap"   // để includeBuild thay được `kmp.bootstrap:appkit-reminder` bằng module này
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
        commonMain.dependencies { api(project(":appkit-core")) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        if (!desktopOnly) {
            val androidMain by getting { dependencies { implementation(libs.androidx.core) } }
        }
    }
}

if (!desktopOnly) apply(from = "android.gradle.kts")
