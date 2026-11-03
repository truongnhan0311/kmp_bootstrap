package kmp.bootstrap.appkit.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kmp.bootstrap.appkit.AppKitAndroid
import kotlinx.coroutines.CompletableDeferred
import java.util.TimeZone

/**
 * Gọi MỘT lần trong `MainActivity.onCreate` (trước `setContent`): đăng ký bộ chọn ảnh (Android bắt đăng ký trước khi Activity bắt đầu).
 *     AppKitAndroid.init(applicationContext); AppKitPlatform.attach(this)
 */
object AppKitPlatform {
    internal var activity: ComponentActivity? = null
    internal var launcher: ActivityResultLauncher<String>? = null
    internal var pending: CompletableDeferred<Uri?>? = null
    /** Authority của FileProvider khai trong Manifest (xem docs/platform.md). Mặc định `<applicationId>.files`. */
    var fileAuthority: String? = null

    fun attach(activity: ComponentActivity) {
        this.activity = activity
        launcher = activity.registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> pending?.complete(uri) }
    }
}

actual fun platformServices(): PlatformServices = AndroidPlatformServices()

class AndroidPlatformServices : PlatformServices {
    private val ctx: Context get() = AppKitAndroid.context()

    override fun openUrl(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun copyToClipboard(text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", text))
    }

    override fun shareText(text: String): Boolean = runCatching {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    /** Ghi vào `cache/exports/` rồi chia sẻ qua FileProvider — cần khai provider trong Manifest (docs/platform.md). */
    override fun shareFile(filename: String, bytes: ByteArray, mimeType: String): Boolean = runCatching {
        val dir = java.io.File(ctx.cacheDir, "exports").also { it.mkdirs() }
        val file = java.io.File(dir, java.io.File(filename).name).also { it.writeBytes(bytes) }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, AppKitPlatform.fileAuthority ?: (ctx.packageName + ".files"), file)
        val send = Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        true
    }.getOrDefault(false)

    override suspend fun pickImage(): PickedImage? {
        val launcher = AppKitPlatform.launcher ?: return null            // chưa gọi AppKitPlatform.attach(activity)
        val wait = CompletableDeferred<Uri?>().also { AppKitPlatform.pending = it }
        launcher.launch("image/*")
        val uri = wait.await() ?: return null
        val resolver = ctx.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val name = resolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null } ?: "image"
        return PickedImage(bytes, name, resolver.getType(uri) ?: "image/jpeg")
    }

    override fun timezoneId(): String = TimeZone.getDefault().id
}
