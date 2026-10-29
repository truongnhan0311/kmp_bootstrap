extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
    namespace = "vn.truongnhan.appkit.ui"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
