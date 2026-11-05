import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// appkit-mqtt: client MQTT 3.1.1 qua WebSocket — Last Will, QoS 0/1, keepAlive, nối lại có chờ ngẫu nhiên, đăng ký lại chủ đề.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// -PdesktopOnly=true: chỉ dựng JVM (CI, sandbox không có Android SDK/Xcode). Mặc định dựng Android + iOS + desktop.
val desktopOnly = providers.gradleProperty("desktopOnly").orNull == "true"
group = "kmp.bootstrap"   // để includeBuild thay được `kmp.bootstrap:appkit-mqtt` bằng module này
version = "0.1.0"

if (!desktopOnly) apply(plugin = "com.android.library")

kotlin {
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    if (!desktopOnly) {
        androidTarget { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
        iosX64(); iosArm64(); iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":appkit-core"))
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopMain by getting { dependencies { implementation(libs.ktor.client.cio) } }
        // Test với WebSocket THẬT trên JVM: một server Ktor nhỏ đóng vai broker (chỉ dùng trong test).
        val desktopTest by getting { dependencies { implementation(libs.ktor.server.cio); implementation(libs.ktor.server.websockets) } }
        if (!desktopOnly) {
            val androidMain by getting { dependencies { implementation(libs.ktor.client.okhttp) } }
            // Lấy source set theo từng target iOS (xem ghi chú trong appkit-network/build.gradle.kts).
            listOf("iosX64Main", "iosArm64Main", "iosSimulatorArm64Main").forEach { name ->
                getByName(name).dependencies { implementation(libs.ktor.client.darwin) }
            }
        }
    }
}

if (!desktopOnly) apply(from = "android.gradle.kts")
