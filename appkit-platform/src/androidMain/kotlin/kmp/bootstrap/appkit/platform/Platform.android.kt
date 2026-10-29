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
