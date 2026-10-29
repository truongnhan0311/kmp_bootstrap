plugins { kotlin("jvm") version "2.1.21"; application }
repositories { mavenCentral() }
dependencies {
    implementation("kmp.bootstrap:appkit-core")      // chỉ ngôn ngữ, log, lưu trữ, mã hoá, cache — KHÔNG có Ktor/Firebase
}
application { mainClass.set("MainKt") }
