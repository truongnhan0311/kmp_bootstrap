import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// appkit-core: thứ AI CŨNG CẦN, không dính mạng — ngôn ngữ, log xoay vòng, lưu trữ cục bộ, mã hoá, cache TTL, JWT.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

// -PdesktopOnly=true: chỉ dựng JVM (CI, sandbox không có Android SDK/Xcode). Mặc định dựng Android + iOS + desktop.
val desktopOnly = providers.gradleProperty("desktopOnly").orNull == "true"
group = "kmp.bootstrap"   // để includeBuild thay được `kmp.bootstrap:appkit-core` bằng module này
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
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            api(libs.okio)
            api(libs.cryptography.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
        }
        // Nhà cung cấp mã hoá theo nền tảng (không dùng provider-optimal: nó chỉ có từ 0.5.0, build bằng Kotlin 2.2).
        val desktopMain by getting { dependencies { implementation(libs.cryptography.jdk) } }
        if (!desktopOnly) {
            val androidMain by getting { dependencies { implementation(libs.androidx.security.crypto); implementation(libs.cryptography.jdk) } }
            // Source set THEO TỪNG TARGET (không dùng `iosMain by getting`: nó được tạo lười, xem ghi chú ở appkit-network/build.gradle.kts).
            listOf("iosX64Main", "iosArm64Main", "iosSimulatorArm64Main").forEach { name ->
                getByName(name).dependencies { implementation(libs.cryptography.apple) }
            }
        }
    }
}

if (!desktopOnly) apply(from = "android.gradle.kts")
