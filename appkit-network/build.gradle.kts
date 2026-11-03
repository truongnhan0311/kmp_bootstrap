import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// appkit-network: gọi API — JWT tự gắn + tự làm mới phiên, thử lại, failover, lỗi có phân loại, cache GET, đường dẫn tập trung.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

// -PdesktopOnly=true: chỉ dựng JVM (CI, sandbox không có Android SDK/Xcode). Mặc định dựng Android + iOS + desktop.
val desktopOnly = providers.gradleProperty("desktopOnly").orNull == "true"
group = "kmp.bootstrap"   // để includeBuild thay được `kmp.bootstrap:appkit-network` bằng module này
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
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.okio.fakefilesystem)
        }
        val desktopMain by getting { dependencies { implementation(libs.ktor.client.cio) } }
        if (!desktopOnly) {
            val androidMain by getting { dependencies { implementation(libs.ktor.client.okhttp) } }
            // KHÔNG dùng `val iosMain by getting`: `iosMain` là source set trung gian của default hierarchy template, được tạo lười SAU khối
            // `kotlin {}` nên báo "KotlinSourceSet with name 'iosMain' not found" khi cấu hình trên Mac/CI. Source set THEO TỪNG TARGET có ngay
            // khi khai báo target. (Engine Ktor chỉ cần có trong classpath; HttpClient tự chọn lúc chạy.)
            listOf("iosX64Main", "iosArm64Main", "iosSimulatorArm64Main").forEach { name ->
                getByName(name).dependencies { implementation(libs.ktor.client.darwin) }
            }
        }
    }
}

if (!desktopOnly) apply(from = "android.gradle.kts")
