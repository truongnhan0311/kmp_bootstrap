// Cấu hình Android của module, nạp bằng `apply(from = ...)` từ build.gradle.kts (chỉ khi KHÔNG phải desktopOnly).
//
// KHÔNG viết `extensions.configure<com.android.build.gradle.LibraryExtension>(...)`: script nạp kiểu này không thấy lớp của Android Gradle
// Plugin lúc biên dịch ("Unresolved reference: android" trên CI/Mac), nên cấu hình qua tên thuộc tính (động), không phụ thuộc lớp AGP.
// Khối lồng lấy bằng getter rồi đặt thuộc tính: `"defaultConfig" { }` không dùng được vì nó nhận Action có tham số.
extensions.getByName("android").withGroovyBuilder {
    setProperty("namespace", "kmp.bootstrap.appkit.mqtt")
    setProperty("compileSdk", 36)
    (getProperty("defaultConfig") as Any).withGroovyBuilder { setProperty("minSdk", 26) }
    (getProperty("compileOptions") as Any).withGroovyBuilder {
        setProperty("sourceCompatibility", JavaVersion.VERSION_17)
        setProperty("targetCompatibility", JavaVersion.VERSION_17)
    }
}
