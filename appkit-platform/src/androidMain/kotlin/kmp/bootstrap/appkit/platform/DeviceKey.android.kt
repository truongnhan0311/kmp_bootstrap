package kmp.bootstrap.appkit.platform

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kmp.bootstrap.appkit.AppKitAndroid
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

actual fun deviceKeyStore(): DeviceKeyStore? = AndroidDeviceKeyStore

/**
 * Android Keystore, EC P-256, mỗi lần ký đều phải xác thực (CryptoObject trong BiometricPrompt).
 * Mật mã máy ([allowPasscode]) chỉ có từ Android 11 (API 30): khoá ký kèm CryptoObject không nhận DEVICE_CREDENTIAL ở bản cũ hơn,
 * nên trên Android 10 trở xuống chỉ dùng sinh trắc học mạnh.
 * MainActivity phải là FragmentActivity và đã gọi AppKitPlatform.attach(this).
 */
object AndroidDeviceKeyStore : DeviceKeyStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private val passcodeSupported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun authenticators(allowPasscode: Boolean) =
        if (allowPasscode && passcodeSupported) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_STRONG

    override fun isAvailable(allowPasscode: Boolean): Boolean =
        BiometricManager.from(AppKitAndroid.context()).canAuthenticate(authenticators(allowPasscode)) == BiometricManager.BIOMETRIC_SUCCESS

    override fun hasKey(alias: String): Boolean = runCatching { keyStore().containsAlias(alias) }.getOrDefault(false)

    override fun createKey(alias: String, allowPasscode: Boolean): String? = runCatching {
        deleteKey(alias)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            // Thêm khuôn mặt / vân tay mới trong cài đặt máy → khoá mất hiệu lực (phải bật lại).
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (passcodeSupported) {
                    // 0 giây = mỗi lần ký đều phải xác thực.
                    val types = if (allowPasscode) KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL else KeyProperties.AUTH_BIOMETRIC_STRONG
                    setUserAuthenticationParameters(0, types)
                }
            }
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        generator.initialize(spec)
        // X.509 SubjectPublicKeyInfo — đúng dạng server đọc.
        Base64.encodeToString(generator.generateKeyPair().public.encoded, Base64.NO_WRAP)
    }.getOrNull()

    override fun sign(alias: String, message: String, reason: String, allowPasscode: Boolean, callback: (String?, String?) -> Unit) {
        val activity = AppKitPlatform.activity as? FragmentActivity
        if (activity == null) { callback(null, DeviceKeyStore.ERROR_FAILED); return }
        val signature = try {
            val key = keyStore().getKey(alias, null) as? PrivateKey
            if (key == null) { callback(null, DeviceKeyStore.ERROR_INVALIDATED); return }
            Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteKey(alias); callback(null, DeviceKeyStore.ERROR_INVALIDATED); return
        } catch (e: Exception) {
            callback(null, DeviceKeyStore.ERROR_FAILED); return
        }

        var done = false
        fun finish(sig: String?, error: String?) { if (!done) { done = true; callback(sig, error) } }

        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val signed = runCatching {
                    val s = result.cryptoObject!!.signature!!
                    s.update(message.encodeToByteArray())
                    Base64.encodeToString(s.sign(), Base64.NO_WRAP)
                }.getOrNull()
                if (signed != null) finish(signed, null) else finish(null, DeviceKeyStore.ERROR_FAILED)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                finish(null, when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED -> DeviceKeyStore.ERROR_CANCELLED
                    BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> DeviceKeyStore.ERROR_LOCKED_OUT
                    else -> DeviceKeyStore.ERROR_FAILED
                })
            }
            // onAuthenticationFailed (một lần không khớp): hộp thoại vẫn mở cho thử lại.
        })
        val allowed = authenticators(allowPasscode)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(reason)
            .setAllowedAuthenticators(allowed)
            .apply { if (allowed and DEVICE_CREDENTIAL == 0) setNegativeButtonText(android.R.string.cancel.let { activity.getString(it) }) }
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
    }

    override fun deleteKey(alias: String) { runCatching { keyStore().deleteEntry(alias) } }
}
