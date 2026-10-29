package kmp.bootstrap.appkit.ui

/**
 * MỌI dữ liệu của các trang mẫu nằm ở đây — một chỗ duy nhất. Mặc định là [StarterData.Dummy] (dữ liệu giả, không gọi mạng).
 * Nối dữ liệu thật: dựng một [StarterData] từ API/DB của bạn rồi truyền vào `StarterApp(data = …)`; các trang không phải sửa.
 * Số liệu (TSS, thể lực…) luôn do code/máy chủ tính sẵn — trang chỉ hiện.
 */
data class StarterUser(val name: String, val email: String)

data class StarterActivity(val title: String, val date: String, val summary: String, val tss: Int)

data class StarterEffort(val title: String, val where: String, val value: String)

data class StarterData(
    val user: StarterUser,
    val greeting: String,
    val weekStats: List<StatItem>,
    val activities: List<StarterActivity>,
    val profileStats: List<StatItem>,
    val searchHistory: List<String>,
    /** TSS theo ngày của tháng đang xem (ngày → TSS). */
    val rides: Map<Int, Int>,
    val zoneMinutes: List<Float>,
    val efforts: List<StarterEffort>,
    val ctl: List<Float>,
    val atl: List<Float>,
    val trendStats: List<StatItem>,
) {
    companion object {
        val Dummy = StarterData(
            user = StarterUser("Nhan Nguyen", "nhan@example.com"),
            greeting = "Chào buổi sáng",
            weekStats = listOf(StatItem("Buổi tập", "4"), StatItem("Tổng thời gian", "5h20"), StatItem("Thể lực", "43"), StatItem("Mệt", "61")),
            activities = listOf(
                StarterActivity("Đạp buổi sáng", "29/10", "1h13 · 32 km", 81),
                StarterActivity("Đạp nhóm Thủ Đức", "27/10", "2h05 · 61 km", 142),
                StarterActivity("Hồi phục", "26/10", "45 phút · 15 km", 28),
                StarterActivity("Nền tảng 90 phút", "24/10", "1h30 · 40 km", 90),
            ),
            profileStats = listOf(StatItem("FTP", "250 W"), StatItem("Cân nặng", "71,5 kg"), StatItem("W/kg", "3,5"), StatItem("Nhịp tim ngưỡng", "168")),
            searchHistory = listOf("đạp nhóm", "hồi phục", "Thủ Đức", "interval"),
            rides = mapOf(1 to 40, 3 to 62, 4 to 95, 6 to 70, 9 to 88, 11 to 130, 15 to 75, 17 to 145, 22 to 92, 24 to 178, 25 to 105, 28 to 40, 29 to 81),
            zoneMinutes = listOf(8f, 31f, 22f, 9f, 3f, 0f, 0f),
            efforts = listOf(StarterEffort("Nước rút", "km 12,4 · 8 giây", "812 W"), StarterEffort("Cú giật", "km 21,0 · 45 giây", "506 W")),
            ctl = listOf(31f, 33f, 36f, 38f, 41f, 40f, 43f),
            atl = listOf(45f, 52f, 40f, 58f, 49f, 66f, 61f),
            trendStats = listOf(StatItem("Thể lực", "43"), StatItem("Mệt", "61"), StatItem("Độ sung", "−18"), StatItem("Tải tuần này", "121")),
        )
    }
}

internal fun StarterActivity.toRow(icon: kmp.bootstrap.appkit.SvgIcon? = AppIcons.Calendar) = ListRow(title, "$date · $summary", trailing = "$tss TSS", icon = icon)
