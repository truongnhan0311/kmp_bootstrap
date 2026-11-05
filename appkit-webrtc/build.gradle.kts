import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// appkit-webrtc: API WebRTC trung lập nền tảng + công cụ SDP (thứ tự codec, kiểm DTLS) + hàng đợi ICE candidate + bản giả để test.
// Android cài bằng libwebrtc (androidMain). iOS: app tự cài RtcEngine (chưa có trong kit), xem docs/webrtc.md.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// -PdesktopOnly=true: chỉ dựng JVM (CI, sandbox không có Android SDK/Xcode). Mặc định dựng Android + iOS + desktop.
val desktopOnly = providers.gradleProperty("desktopOnly").orNull == "true"
group = "kmp.bootstrap"   // để includeBuild thay được `kmp.bootstrap:appkit-webrtc` bằng module này
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
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        if (!desktopOnly) {
            val androidMain by getting { dependencies { api(libs.webrtc.android) } }
        }
    }
}

if (!desktopOnly) apply(from = "android.gradle.kts")
