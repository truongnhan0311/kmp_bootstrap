import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// appkit-mqtt: client MQTT 3.1.1 viết bằng Kotlin — tự nối lại, Last Will, QoS 0/1 (QoS 1 gửi lại sau khi nối lại), nhận tin dạng Flow.
// Đường truyền: MQTT qua WebSocket bằng Ktor (Android, iOS, desktop; TLS do engine lo) và TCP thường (chỉ JVM, để test/công cụ).
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

val desktopOnly = providers.gradleProperty("desktopOnly").orNull == "true"
group = "kmp.bootstrap"   // để includeBuild thay được `kmp.bootstrap:appkit-mqtt` bằng module này
version = "0.1.0"

if (!desktopOnly) apply(plugin = "com.android.library")
if (desktopOnly) {
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
            api(libs.ktor.client.core)   // gồm plugin WebSockets
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopMain by getting { dependencies { implementation(libs.ktor.client.cio) } }
        val desktopTest by getting {
            dependencies {
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
                implementation(project(":appkit-iot"))   // thử với broker thật của Recam: mật khẩu thiết bị
            }
        }
        if (!desktopOnly) {
            val androidMain by getting { dependencies { implementation(libs.ktor.client.okhttp) } }
            // Source set THEO TỪNG TARGET (xem ghi chú ở appkit-network/build.gradle.kts).
            listOf("iosX64Main", "iosArm64Main", "iosSimulatorArm64Main").forEach { name ->
                getByName(name).dependencies { implementation(libs.ktor.client.darwin) }
            }
        }
    }
}

if (!desktopOnly) apply(from = "android.gradle.kts")
