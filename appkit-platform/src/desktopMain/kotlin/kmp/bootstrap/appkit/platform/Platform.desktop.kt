package kmp.bootstrap.appkit.platform

import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.util.TimeZone

actual fun platformServices(): PlatformServices = DesktopPlatformServices()

class DesktopPlatformServices : PlatformServices {
    override fun openUrl(url: String) { runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url)) } }
    override fun copyToClipboard(text: String) { runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) } }
    /** Desktop không có hộp thoại chia sẻ chung: trả false để app rơi về copy. */
    override fun shareText(text: String): Boolean = false
    override suspend fun pickImage(): PickedImage? = runCatching {
        val dialog = FileDialog(null as Frame?, "Chọn ảnh", FileDialog.LOAD).apply { setFilenameFilter { _, n -> n.lowercase().let { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".webp") } }; isVisible = true }
        val name = dialog.file ?: return null
        val file = File(dialog.directory, name)
        PickedImage(file.readBytes(), name, contentTypeOf(name))
    }.getOrNull()
    override fun timezoneId(): String = TimeZone.getDefault().id

    internal companion object {
        fun contentTypeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
    }
}
