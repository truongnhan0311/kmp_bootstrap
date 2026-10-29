package kmp.bootstrap.appkit

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Gọi MỘT lần ở `Application.onCreate` (hoặc `MainActivity.onCreate`) trước khi tạo `AppStorage`. */
object AppKitAndroid {
    internal lateinit var appContext: Context
    fun init(context: Context) { appContext = context.applicationContext }
    fun context(): Context {
        check(::appContext.isInitialized) { "Call AppKitAndroid.init(context) before using AppStorage" }
        return appContext
    }
}

private class PrefsStore(private val sp: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = sp.getString(key, null)
    override fun put(key: String, value: String?) { sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
}

actual fun platformPrefs(name: String): KeyValueStore = PrefsStore(AppKitAndroid.context().getSharedPreferences("$name.prefs", Context.MODE_PRIVATE))

/** Mã hoá bằng khoá nằm trong Android Keystore (AES-256-GCM). */
actual fun platformSecrets(name: String): KeyValueStore {
    val ctx = AppKitAndroid.context()
    val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    val sp = EncryptedSharedPreferences.create(
        ctx, "$name.secrets", key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    return PrefsStore(sp)
}
