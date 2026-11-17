package kmp.bootstrap.appkit.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorBiometryLockout
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import kotlin.coroutines.resume

actual fun deviceAuth(): DeviceAuth = IosDeviceAuth()

/**
 * LocalAuthentication, chính sách deviceOwnerAuthentication: Face ID / Touch ID, hệ thống tự cho nhập passcode.
 * Info.plist PHẢI có NSFaceIDUsageDescription, không thì app bị dừng khi gọi Face ID.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDeviceAuth : DeviceAuth {
    override fun available(): DeviceAuthKind {
        if (LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, null)) return DeviceAuthKind.Biometric
        return if (LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)) DeviceAuthKind.Passcode else DeviceAuthKind.None
    }

    override suspend fun authenticate(reason: String): DeviceAuthResult {
        if (available() == DeviceAuthKind.None) return DeviceAuthResult.NotAvailable
        val context = LAContext()
        return suspendCancellableCoroutine { cont ->
            context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, reason) { ok, error ->
                if (!cont.isActive) return@evaluatePolicy
                cont.resume(
                    when {
                        ok -> DeviceAuthResult.Success
                        error == null -> DeviceAuthResult.Failed("unknown")
                        error.code == LAErrorUserCancel || error.code == LAErrorAppCancel || error.code == LAErrorSystemCancel -> DeviceAuthResult.Cancelled
                        error.code == LAErrorBiometryLockout -> DeviceAuthResult.LockedOut
                        error.code == LAErrorPasscodeNotSet -> DeviceAuthResult.NotAvailable
                        else -> DeviceAuthResult.Failed("LAError ${error.code}")
                    },
                )
            }
            cont.invokeOnCancellation { context.invalidate() }
        }
    }
}
