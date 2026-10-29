# appkit-ui

Component Material 3 đặt tên và API theo shadcn (Card, Button, Input, Badge, Alert…) cùng theme sinh từ **CSS của web**, để web và app dùng chung một bảng màu.
Không phụ thuộc module nào khác. Thêm component: viết một `@Composable` chỉ đọc `A.colors` / `A.type`, không giữ màu cứng.

```kotlin
commonMain.dependencies { implementation("kmp.bootstrap:appkit-ui") }
```

## Theme từ CSS

`tools/shadcn-theme.mjs` đọc biến `--background`, `--primary`, `--muted-foreground`… (hex, rgb, hsl, **oklch** của shadcn mới, `var()` lồng nhau)
và sinh `AppColors` sáng/tối. Token thiếu rơi về token liên quan; thiếu hẳn thì báo lỗi rõ. Token riêng của app (vùng công suất, màu biểu đồ…)
vào `colors.extra("tên")`. Chạy test của bộ sinh: `node --test tools/*.test.mjs`.

<!-- snippet: ui-theme -->
```kotlin
// 1) sinh màu từ CSS của web (một lần, hoặc mỗi khi đổi màu):
//    node tools/shadcn-theme.mjs ../web/src/index.css --package com.acme --name Acme --out src/commonMain/kotlin/com/acme/AcmeColors.kt
//    (shadcn mặc định: :root sáng, .dark tối;  nếu CSS khác thì thêm --light "<selector>" --dark "<selector>")
// 2) bọc app:
@Composable
fun AcmeApp(dark: AppColors, light: AppColors) {
    AppTheme(light = light, dark = dark, mode = ThemeMode.System) {      // System / Light / Dark
        LoginScreen()
    }
}
```
<!-- /snippet -->

## Layout như viết TSX

Compose đã khai báo giống TSX; kit thêm tên quen thuộc của Tailwind/Bootstrap để khỏi nhớ `Arrangement`, `PaddingValues`…
Thang khoảng cách **1 đơn vị = 4dp** (`p(4)` = 16dp, `gap = 3` = 12dp). Mọi màu lấy từ token theme, không có màu cứng.

| React (TSX) | Compose (kit này) |
|---|---|
| `<div className="flex flex-col gap-3">` | `Stack(gap = 3) { … }` |
| `<div className="flex items-center gap-2">` | `Inline(gap = 2) { … }` (`spread = true` = `justify-between`) |
| `<div className="flex flex-wrap gap-2">` | `Wrap { … }` |
| `<div className="grid grid-cols-2 gap-3">` | `Grid(columns = 2) { cell { … }; cell { … } }` |
| `className="p-4 px-5 pt-2"` | `Modifier.p(4).px(5).pt(2)` |
| `className="rounded-lg border bg-card"` | `Modifier.cardStyle()` hoặc `.rounded(Radius.Lg).bgCard().bordered()` |
| `<Button variant="outline" size="sm">` | `AppButton("Lưu", onClick, variant = ButtonVariant.Outline, size = ButtonSize.Sm)` |
| `<Badge>`, `<Card>`, `<Input>`, `<Alert>` | `Badge`, `AppCard`, `AppField`, `AppAlert` |
| `<hr />`, `max-w-xl mx-auto` | `Divider()`, `Container(maxWidth = 640.dp)` |

<!-- snippet: ui-layout -->
```kotlin
@Composable
fun LayoutDemo() {
    Stack(gap = 4, modifier = Modifier.p(5)) {                       // flex-col gap-4 p-5
        Inline(gap = 2) { Badge("mới"); Txt("Tiêu đề") }              // flex items-center gap-2
        Grid(columns = 2, gap = 3) {                                  // grid grid-cols-2 gap-3
            cell { AppCard { Stat("Thể lực", "43") } }
            cell { AppCard { Stat("Mệt", "61") } }
        }
        Box(Modifier.p(3).cardStyle()) { Txt("Khối tự vẽ trông như thẻ") }   // p-3 rounded-lg border bg-card
        AppButton("Lưu", onClick = {}, variant = ButtonVariant.Outline, size = ButtonSize.Sm, fullWidth = true)
    }
}
```
<!-- /snippet -->

## Khung màn hình

`AppScaffold` (thanh trên + nội dung + thanh dưới; `TabItem` nhận `icon`) → `AppHeader` (tiêu đề, nút quay lại, nút bên phải) → `Page` (cuộn dọc có lề) → `TabBar` (tab dưới).
Rút từ UI của Taper nên khớp cách app thật dựng màn. Còn có `Section`, `ListItem`, `EmptyState`.

## Block (kiểu Bootstrap)

Mỗi block là một khối trang hoàn chỉnh — đưa dữ liệu vào, ghép chồng trong `Page { }`. Muốn khác thì chép block ra sửa (đều ngắn).

| Block | Dùng cho |
|---|---|
| `HeroBlock` | câu chào lớn + phụ đề serif + nút hành động |
| `StatGridBlock` | lưới ô chỉ số (nhãn + số mono) |
| `FeatureGridBlock` | lưới thẻ lối tắt / tính năng, bấm được |
| `ListBlock` | danh sách trong một thẻ, có đường kẻ, có trạng thái rỗng |
| `CtaBlock` | thẻ kêu gọi hành động viền màu chính |
| `BadgesBlock` | hàng nhãn tự xuống dòng |

<!-- snippet: ui-blocks -->
```kotlin
@Composable
fun HomeWithBlocks() {
    Page {
        HeroBlock("Chào buổi sáng", "Hôm nay bạn muốn làm gì?", action = { AppButton("Bắt đầu", onClick = {}) })
        StatGridBlock(title = "Tuần này", items = listOf(StatItem("Buổi tập", "4"), StatItem("Thể lực", "43")))
        ListBlock("Gần đây", items = listOf(ListRow("Đạp buổi sáng", "1h13 · 32 km", trailing = "81 TSS")))
        CtaBlock("Nối nguồn dữ liệu", "Buổi tập sẽ tự về.", actionLabel = "Nối ngay", onAction = {})
    }
}
```
<!-- /snippet -->

## Icon (SVG)

Icon là **file `.svg`**, không vẽ tay trong code. `appkit-ui/icons/` có 14 icon sẵn (home, list, search, user, settings, calendar, chart, bell, plus, chevron-right, check, x, arrow-left, sun).
`tools/svg-to-kotlin.mjs` biến thư mục SVG thành object `AppIcons` (`chevron-right.svg` → `AppIcons.ChevronRight`); bộ đọc SVG (`SvgParser`, ở `appkit-core`, có test)
đổi path/line/polyline/polygon/circle/ellipse/rect thành đường path và `AppIcon` vẽ bằng màu của theme (`stroke="currentColor"`). Không hỗ trợ gradient, transform, text, CSS (gặp thì bỏ qua, không ném lỗi).

**Thêm icon của bạn** (Lucide, Heroicons, Feather, Tabler dùng được nguyên file):

```sh
cp ~/Downloads/heart.svg appkit-ui/icons/
node tools/svg-to-kotlin.mjs appkit-ui/icons --package kmp.bootstrap.appkit.ui --out appkit-ui/src/commonMain/kotlin/kmp/bootstrap/appkit/ui/AppIcons.kt
# → AppIcons.Heart
```
App có thư mục icon riêng: chạy script với `--package` và `--object` của app (ví dụ `--object MyIcons`) để không lẫn với bộ của kit.

<!-- snippet: ui-icons -->
```kotlin
@Composable
fun IconsDemo() {
    Inline(gap = 3) {
        AppIcon(AppIcons.Home)                                                  // 20dp, màu chữ (icon là file .svg → AppIcons)
        AppIcon(AppIcons.Search, size = 24.dp, color = A.colors.primary)        // đổi cỡ và màu
        AppButton("Thêm buổi", onClick = {}, icon = AppIcons.Plus)              // nút có icon (icon cùng màu chữ nút)
    }
    TabBar(listOf(TabItem("home", "Trang chủ", AppIcons.Home), TabItem("me", "Hồ sơ", AppIcons.User)), selected = "home") { }   // tab có icon
    AppIcon("<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\"><path d=\"M5 12h14\"/></svg>")           // hoặc SVG bất kỳ dạng chuỗi
}
```
<!-- /snippet -->

## App mẫu 5 tab (điểm xuất phát)

`StarterApp` ghép khung màn + icon + các block với dữ liệu giả, năm tab: **Trang chủ, Hoạt động, Tìm kiếm, Hồ sơ, Cài đặt** (mỗi tab một màn mẫu).
**Chép `StarterHome.kt` (khung + điều hướng) và `StarterPages.kt` (các trang) vào app rồi thay dữ liệu thật.** Không có logic, không gọi mạng.
Thêm tab = thêm giá trị vào `StarterTab` + một nhánh `when` ở `StarterApp`; thêm trang con = thêm một `StarterRoute`.

| Trang | Hàm | Ghép từ |
|---|---|---|
| Đăng nhập, Đăng ký | `StarterLoginPage`, `StarterRegisterPage` | `HeroBlock`, `AppField`, `AppAlert`, `AppButton` (toàn màn hình, không có header/tab) |
| Trang chủ | `StarterHomeScreen` | `HeroBlock`, `StatGridBlock`, `FeatureGridBlock`, `ListBlock`, `CtaBlock` |
| Hoạt động | `StarterActivitiesScreen` | `ScreenHeader`, `ListBlock` (có trạng thái rỗng) |
| Tìm kiếm | `StarterSearchScreen` | `AppField`, `BadgesBlock`, `ListBlock`, `EmptyState` |
| Chi tiết | `StarterDetailPage` | `HeroBlock`, `StatGridBlock`, `BarChart`, `ListBlock`, ô ghi chú |
| Lịch | `StarterCalendarPage` | `Grid(columns = 7)` ô ngày có chấm mức tải, `ListBlock` của ngày chọn |
| Xu hướng | `StarterTrendsPage` | `Segmented`, `LineChart`, `StatGridBlock`, `CtaBlock` |
| Hồ sơ, Cài đặt | `StarterProfileScreen`, `StarterSettingsScreen` | `Avatar`, `Segmented`, `ListBlock` có mũi tên |

Biểu đồ (`BarChart`, `LineChart` trong `Charts.kt`) chỉ **vẽ** số đã tính sẵn; muốn zoom/dò giá trị thì tự viết thêm trong app.

<!-- snippet: ui-scaffold -->
```kotlin
@Composable
fun AppFrame(dark: AppColors, light: AppColors) {
    StarterHome(dark, light)        // trang chủ mẫu đầy đủ: header + nội dung (các block) + tab dưới — chép StarterHome.kt rồi thay dữ liệu
}
```
<!-- /snippet -->

## Component

Quy ước chữ (đổi được qua `AppTypography`): `sans` cho giao diện, `mono` cho **mọi con số**, `serif` cho câu do người/AI viết.

<!-- snippet: ui-components -->
```kotlin
@Composable
fun LoginScreen() {
    ScreenHeader("Đăng nhập", subtitle = "Dùng tài khoản của bạn")       // tiêu đề + câu phụ (chữ serif)
    AppCard {                                                            // thẻ shadcn: nền card, viền border
        AppField(label = "Email", value = "", onChange = {})             // numeric = true cho ô số (chữ mono)
        Gap(8)
        AppButton("Đăng nhập", onClick = {})                             // variant: Default / Outline / Destructive / Ghost
        AppAlert("Sai mật khẩu", destructive = true)                     // thông báo lỗi
    }
    Badge("beta"); Stat("FTP", "250 W")                                  // nhãn nhỏ, ô chỉ số
}
```
<!-- /snippet -->

**Chưa kiểm:** module biên dịch được (kể cả `StarterHome` và các block) nhưng chưa chạy lúc runtime / chưa chụp màn hình ở môi trường dựng (`androidx.collection` chỉ có trên Google Maven, bị chặn). `HomeScreenshotTest` có sẵn: chạy ở máy có Google Maven sẽ ra `build/screenshots/home-dark.png` và `home-light.png`.
