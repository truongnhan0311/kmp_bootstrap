rootProject.name = "core-only"
// Lấy kmp_bootstrap như một "thư viện nguồn": module nào dùng thì khai ở dependencies, module khác không bị kéo vào.
includeBuild("../..")
