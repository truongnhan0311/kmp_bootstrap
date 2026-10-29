// -PdesktopOnly=true: chỉ dựng bản JVM để test ở máy không với tới Google Maven (CI, sandbox). Mặc định dựng cả Android + iOS.
val desktopOnly = providers.gradleProperty("desktopOnly").orNull == "true"

pluginManagement {
    val desktopOnlyProp = providers.gradleProperty("desktopOnly").orNull == "true"
    repositories {
        mavenCentral()
        gradlePluginPortal()
        if (!desktopOnlyProp) google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        if (!desktopOnly) google()
    }
}

rootProject.name = "kmp_bootstrap"
include(":kit", ":ui")
