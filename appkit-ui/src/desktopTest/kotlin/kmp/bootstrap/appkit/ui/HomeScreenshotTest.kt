package kmp.bootstrap.appkit.ui

import kmp.bootstrap.appkit.ThemeMode
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Dựng trang chủ mẫu ra PNG (sáng + tối) tại `build/screenshots/` — bằng chứng nhìn được cho bố cục. */
@OptIn(ExperimentalComposeUiApi::class)
class HomeScreenshotTest {
    private fun shoot(name: String, mode: ThemeMode, route: StarterRoute = StarterRoute.Main()) {
        val scene = ImageComposeScene(width = 780, height = 1500, density = Density(2f)) { StarterApp(dark = TaperDarkColors, light = TaperLightColors, mode = mode, initial = route) }
        try {
            repeat(3) { scene.render(it * 16_000_000L) }
            val png = scene.render(64_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").apply { mkdirs() }.resolve("$name.png").writeBytes(png)
            assertTrue(png.size > 5_000, "ảnh không được trống")
        } finally { scene.close() }
    }

    @Test fun darkHome() = shoot("home-dark", ThemeMode.Dark)
    @Test fun lightHome() = shoot("home-light", ThemeMode.Light)
    @Test fun login() = shoot("login", ThemeMode.Dark, StarterRoute.Login)
    @Test fun detail() = shoot("detail", ThemeMode.Dark, StarterRoute.Detail("Đạp buổi sáng"))
    @Test fun calendar() = shoot("calendar", ThemeMode.Dark, StarterRoute.Calendar)
    @Test fun trends() = shoot("trends", ThemeMode.Dark, StarterRoute.Trends)
}
