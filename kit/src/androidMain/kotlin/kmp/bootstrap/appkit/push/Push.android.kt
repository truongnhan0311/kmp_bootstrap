package kmp.bootstrap.appkit.push

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kmp.bootstrap.appkit.AppLog
import kmp.bootstrap.appkit.Msg
import kmp.bootstrap.appkit.Strings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

actual fun platformDeviceInfo() = DeviceInfo(name = Build.DEVICE, model = Build.MODEL, os = "Android ${Build.VERSION.RELEASE}", platform = "android")

actual fun platformPushTokenSource() = PushTokenSource {
    suspendCancellableCoroutine { c ->
        FirebaseMessaging.getInstance().token.addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resume(null) }
    }
}

/** Cấu hình hiện thông báo trên Android. Gọi một lần ở `Application.onCreate` trước khi có push. */
object AppKitPush {
    internal var activityClass: Class<out Activity>? = null
    internal var smallIcon: Int = android.R.drawable.ic_dialog_info
    internal var channelId = "default"
    internal var channelName = Strings.text(Msg.NotificationChannel)
    /** Dữ liệu phụ chạy mỗi khi có push (kể cả lúc app ở nền): tải trước dữ liệu, cập nhật số chưa đọc… */
    internal var onMessage: (PushMessage) -> Unit = {}

    fun configure(activity: Class<out Activity>, smallIcon: Int = android.R.drawable.ic_dialog_info,
                  channelId: String = "default", channelName: String = Strings.text(Msg.NotificationChannel), onMessage: (PushMessage) -> Unit = {}) {
        this.activityClass = activity; this.smallIcon = smallIcon; this.channelId = channelId; this.channelName = channelName; this.onMessage = onMessage
    }

    /** Android 13+ cần xin quyền hiện thông báo. Gọi từ Activity khi hợp lý (ví dụ sau đăng nhập). */
    fun requestPermission(activity: Activity, requestCode: Int = 4242) {
        if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestCode)
    }

    /** Gọi trong `onCreate`/`onNewIntent` của Activity: nếu intent mở từ thông báo thì chuyển thành điều hướng. */
    fun handleIntent(intent: Intent?) {
        val type = intent?.getStringExtra(EXTRA_TYPE) ?: return
        PushNavigation.request(PushMessage(type, intent.getStringExtra(EXTRA_ID), null, null, false))
        intent.removeExtra(EXTRA_TYPE)
    }

    internal const val EXTRA_TYPE = "appkit_push_type"
    internal const val EXTRA_ID = "appkit_push_id"
}

/**
 * Khai trong AndroidManifest:
 *   <service android:name="kmp.bootstrap.appkit.push.AppKitMessagingService" android:exported="false">
 *     <intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT"/></intent-filter>
 *   </service>
 */
class AppKitMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushTokens.set(token)
        AppLog.d("push", "có token FCM mới")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val m = PushMessage.fromData(message.data)
        runCatching { AppKitPush.onMessage(m) }
        if (!m.silent) show(m)
    }

    private fun show(m: PushMessage) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(AppKitPush.channelId, AppKitPush.channelName, NotificationManager.IMPORTANCE_DEFAULT))
        val target = AppKitPush.activityClass ?: return
        val intent = Intent(this, target).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            m.type?.let { putExtra(AppKitPush.EXTRA_TYPE, it) }; m.id?.let { putExtra(AppKitPush.EXTRA_ID, it) }
        }
        // MỖI thông báo một id + requestCode riêng theo id đối tượng (bài học qt-depot): dùng chung thì đè nhau và mở nhầm.
        val id = m.id?.hashCode() ?: 1001
        val pending = PendingIntent.getActivity(this, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id, NotificationCompat.Builder(this, AppKitPush.channelId)
            .setContentTitle(m.title ?: "").setContentText(m.body.orEmpty()).setSmallIcon(AppKitPush.smallIcon)
            .setContentIntent(pending).setAutoCancel(true).build())
    }
}
