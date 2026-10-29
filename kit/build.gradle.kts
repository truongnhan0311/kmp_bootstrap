import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Phần BẮT BUỘC giống nhau của mọi app: gọi API có token, tự làm mới phiên (một lần dù nhiều request 401 cùng lúc),
// báo hết phiên, tên miền dự phòng. Không UI, không phụ thuộc backend cụ thể — khác biệt hợp đồng nằm ở AuthContract.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

val desktopOnly = providers.gradleProperty("desktopOnly").orNull == "true"
if (!desktopOnly) apply(plugin = "com.android.library")

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
            api(libs.ktor.client.core)
            api(libs.okio)
            api(libs.cryptography.core)
            implementation(libs.cryptography.optimal)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.okio.fakefilesystem)
        }
        val desktopMain by getting { dependencies { implementation(libs.ktor.client.cio) } }
        if (!desktopOnly) {
            val androidMain by getting { dependencies { implementation(libs.ktor.client.okhttp); implementation(libs.androidx.security.crypto); implementation(libs.firebase.messaging) } }
            val iosMain by getting { dependencies { implementation(libs.ktor.client.darwin) } }
        }
    }
}

if (!desktopOnly) apply(from = "android.gradle.kts")
