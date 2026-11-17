package kmp.bootstrap.appkit.platform

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kmp.bootstrap.appkit.AppKitAndroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

actual fun deviceAuth(): DeviceAuth = AndroidDeviceAuth()

/**
 * BiometricPrompt với BIOMETRIC_WEAK | DEVICE_CREDENTIAL: khuôn mặt hoặc vân tay, hệ thống tự cho chọn PIN/hình vẽ/mật khẩu máy.
 * (Tổ hợp này chạy từ API 23 trở lên; BIOMETRIC_STRONG | DEVICE_CREDENTIAL thì không chạy trên API 28–29.)
 * MainActivity phải là FragmentActivity (vd AppCompatActivity) và đã gọi AppKitPlatform.attach(this).
 */
class AndroidDeviceAuth : DeviceAuth {
    private val ctx: Context get() = AppKitAndroid.context()

    override fun available(): DeviceAuthKind {
        if (BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS) return DeviceAuthKind.Biometric
        val keyguard = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return if (keyguard.isDeviceSecure) DeviceAuthKind.Passcode else DeviceAuthKind.None
    }

    override suspend fun authenticate(reason: String): DeviceAuthResult {
        if (available() == DeviceAuthKind.None) return DeviceAuthResult.NotAvailable
        val activity = AppKitPlatform.activity as? FragmentActivity
            ?: return DeviceAuthResult.Failed("MainActivity must be a FragmentActivity and AppKitPlatform.attach(this) must be called")
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(DeviceAuthResult.Success)
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        if (!cont.isActive) return
                        cont.resume(
                            when (code) {
                                BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED -> DeviceAuthResult.Cancelled
                                BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> DeviceAuthResult.LockedOut
                                BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> DeviceAuthResult.NotAvailable
                                else -> DeviceAuthResult.Failed("biometric error $code")
                            },
                        )
                    }
                    // onAuthenticationFailed: một lần không khớp, hộp thoại vẫn mở để thử lại — không kết thúc ở đây.
                })
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(reason)
                    .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                    .build()
                prompt.authenticate(info)
                cont.invokeOnCancellation { prompt.cancelAuthentication() }
            }
        }
    }
}
