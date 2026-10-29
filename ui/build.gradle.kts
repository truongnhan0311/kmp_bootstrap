import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Component Material 3 dùng chung + theme sinh từ biến CSS của shadcn (xem tools/shadcn-theme.mjs).
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

val desktopOnly = providers.gradleProperty("desktopOnly").orNull == "true"
if (!desktopOnly) apply(plugin = "com.android.library")

// Chế độ desktopOnly không với tới Google Maven: bỏ hai thư viện androidx chỉ cần cho biến thể Android.
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
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val desktopMain by getting { dependencies { implementation(compose.desktop.currentOs) } }
    }
}

if (!desktopOnly) apply(from = "android.gradle.kts")
