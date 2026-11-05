# Tạo app mới (bootstrap)

`tools/new-app.mjs` sinh một dự án app **Android + iOS + desktop (chạy thử)** đã nối sẵn kmp_bootstrap, để bắt đầu từ một màn chạy được thay vì từ con số không.

```sh
node tools/new-app.mjs --name my-app --id com.acme.myapp --out ../my-app --modules network,push,platform,ui
```

| Tham số | Ý nghĩa |
|---|---|
| `--name` | tên dự án, chữ thường + gạch ngang (`my-app`) |
| `--id` | package/bundle id (`com.acme.myapp`) |
| `--out` | thư mục đích (phải chưa có dự án) |
| `--modules` | module dùng, cách nhau dấu phẩy: `network`, `push`, `reminder`, `platform`, `ui`, `mqtt`, `webrtc` (mặc định `network,ui`; `push` tự kéo `network`) |
| `--css` | CSS của web để sinh màu (kèm `--light`/`--dark` nếu selector khác shadcn mặc định) |
| `--bootstrap` | đường dẫn tới kmp_bootstrap (mặc định: thư mục chứa script) |

## Sinh ra gì

```
my-app/
  settings.gradle.kts        includeBuild("../kmp_bootstrap") — module nào khai mới được kéo vào
  composeApp/                mã dùng chung
    commonMain/…/App.kt        startApp() (AppKit.start) + App() (theme + điều hướng + một màn Xin chào)
    commonMain/…/AppColors.kt  màu sáng/tối SINH TỪ CSS (sinh lại bằng tools/shadcn-theme.mjs)
    desktopMain/…/Main.kt      chạy thử: ./gradlew :composeApp:run
    iosMain/…/MainViewController.kt
  androidApp/                MainActivity + MyApplication (AppKitAndroid.init, AppKitPlatform.attach nếu có platform)
  iosApp/                    project.yml (XcodeGen) + SwiftUI bọc Compose
```

## Chạy

```sh
cd ../my-app
./gradlew -PdesktopOnly=true :composeApp:compileKotlinDesktop   # kiểm biên dịch, không cần Android SDK/Xcode
./gradlew :composeApp:run                                          # chạy thử desktop
# Android: mở thư mục bằng Android Studio, chạy androidApp
# iOS:     cd iosApp && xcodegen && open *.xcodeproj
```

## Việc đầu tiên cần sửa

1. `App.kt` → `startApp()`: `baseUrls` và `Endpoints` cho đúng backend (hoặc `EnvelopeContract` nếu backend trả `{code,message,data}`).
2. CSS màu: `node ../kmp_bootstrap/tools/shadcn-theme.mjs <css> --package com.acme.myapp --name App --out composeApp/src/commonMain/kotlin/com/acme/myapp/AppColors.kt`.
3. Màn hình: chép từ `StarterHome.kt`/`StarterPages.kt` (xem [ui.md](ui.md)) rồi nối dữ liệu thật vào `StarterData`.

**Đã kiểm:** dự án sinh ra (đủ 5 module, và chỉ `network`) **biên dịch được** bản desktop với kit; script có test (tạo đủ tệp, từ chối tên/id sai, không ghi đè dự án có sẵn).
**Chưa kiểm:** build Android/iOS của dự án sinh ra, `xcodegen`, chạy `:composeApp:run` (cần Compose runtime).
