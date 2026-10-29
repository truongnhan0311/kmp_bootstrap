# AGENTS.md — hướng dẫn cho agent (và người) dùng hoặc sửa kmp_bootstrap

`kmp_bootstrap` là **SDK nội bộ** cho app Kotlin Multiplatform (Android + iOS): các module độc lập, cần gì lấy nấy. Đọc file này là đủ để dùng nó; không cần hỏi thêm.

## Pull về và kiểm tra trong 1 lệnh

```sh
git clone https://github.com/truongnhan0311/kmp_bootstrap && cd kmp_bootstrap
./gradlew -PdesktopOnly=true desktopTest      # cần JDK 17+; KHÔNG cần Android SDK/Xcode/tài khoản nào
node --test tools/*.test.mjs                            # bộ sinh theme (cần Node 18+)
node tools/sync-snippets.mjs --check          # tài liệu khớp với ví dụ đã biên dịch
```
Lần đầu tải thư viện từ Maven Central mất vài phút. Nếu gặp `429 Too Many Requests` khi tải: đợi rồi chạy lại.
`:appkit-ui` biên dịch được nhưng test runtime của nó không chạy ở môi trường chỉ có Maven Central (thiếu `androidx.collection`) — dùng `compileTestKotlinDesktop` cho module đó.

## Dùng trong một app

1. `settings.gradle.kts` của app: `includeBuild("../kmp_bootstrap")` (hoặc git submodule).
2. Khai module cần dùng: `implementation("kmp.bootstrap:appkit-network")` (xem bảng dưới). Module khác không bị kéo vào.
3. Android: `AppKitAndroid.init(context)` một lần ở `Application.onCreate`.
4. Đọc `docs/<module>.md` — mỗi mục có ví dụ chạy được. **Chép ví dụ, đừng tự nghĩ lại.**

| Cần | Module | Tài liệu |
|---|---|---|
| ngôn ngữ, log, lưu trữ, mã hoá, cache, timer | `appkit-core` | `docs/core.md` |
| gọi API (JWT, làm mới phiên, lỗi, cache GET) | `appkit-network` | `docs/network.md` |
| thông báo đẩy FCM | `appkit-push` | `docs/push.md` |
| nhắc giờ cục bộ | `appkit-reminder` | `docs/reminder.md` |
| mở link, copy, chia sẻ, chọn ảnh, múi giờ | `appkit-platform` | `docs/platform.md` |
| điều hướng, deep link | `appkit-core` + `appkit-ui` | `docs/navigation.md` |
| phiên đăng nhập, giao diện/ngôn ngữ được nhớ | `appkit-network` + `appkit-core` | `docs/session.md` |
| **tạo app mới** | `tools/new-app.mjs` | `docs/new-app.md` |
| component Material 3 + theme từ CSS | `appkit-ui` | `docs/ui.md` |

Mẫu app tối thiểu: `samples/core-only/` (chỉ lấy `appkit-core`; chạy `./gradlew -PdesktopOnly=true run` trong thư mục đó).

## Quy tắc khi SỬA (giữ SDK ổn định)

- **Không đưa đường dẫn API, chữ hiện cho người dùng, hay màu vào code lẻ.** Đường dẫn → `Endpoints`; chữ cho người dùng → `Language`/`Msg` (đủ en/vi/zh/es,
  test bắt thiếu bản dịch); màu → token CSS qua `tools/shadcn-theme.mjs`.
- **Dòng log và thông báo cho lập trình viên (`require`/`check`) luôn tiếng Anh.** Mật khẩu không bao giờ vào log; token chỉ qua `Redactor`.
- Phụ thuộc đi một chiều: `core ← network ← push`, `core ← reminder`, `core ← platform`, `core ← ui` (`ui` KHÔNG biết `network`: tải ảnh qua tham số `fetch`). Đừng để `core` biết gì về mạng.
- Cái gì theo nền tảng thì `expect/actual` (`androidMain`, `iosMain`, `desktopMain`); phần chung viết ở `commonMain` và **phải có test chạy được trên JVM**.
- Thêm tính năng → thêm test, và thêm ví dụ vào file `*Examples.kt` (đoạn giữa `// region: tên` … `// endregion`) rồi đặt cặp thẻ `snippet` trong `docs/*.md` (cú pháp: đầu file `tools/sync-snippets.mjs`; xem mẫu trong `docs/core.md`),
  chạy `node tools/sync-snippets.mjs`. Tài liệu chép từ code đã chạy nên không bao giờ lệch.
- Đổi API công khai (tên, tham số, hành vi) → ghi vào `CHANGELOG.md`. Đừng phá API đã có nếu không bắt buộc; thêm tham số có giá trị mặc định thay vì đổi chữ ký.
- Commit bằng tiếng Việt, đứng tên chủ dự án; không thêm `Co-Authored-By`/`Claude-Session`.

## Trung thực về những gì đã kiểm

- **Đã kiểm (JVM, mỗi lần đổi code):** toàn bộ logic chung. Chạy `./gradlew -PdesktopOnly=true desktopTest`.
- **Chưa kiểm — đừng báo "chạy được" khi chưa thử trên máy thật:** mọi file `androidMain`/`iosMain` (Keystore, Keychain, logcat, FCM, AlarmManager, UNUserNotificationCenter, Swift),
  build Android/iOS, runtime của `appkit-ui`. Khi sửa các phần này, nói rõ là chưa chạy trên thiết bị.
