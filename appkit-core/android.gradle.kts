extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
    namespace = "kmp.bootstrap.appkit"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
