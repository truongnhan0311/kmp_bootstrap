package kmp.bootstrap.appkit.push

import platform.UIKit.UIDevice

actual fun platformDeviceInfo(): DeviceInfo {
    val d = UIDevice.currentDevice
    return DeviceInfo(name = d.name, model = d.model, os = "${d.systemName()} ${d.systemVersion}", platform = "ios")
}

/**
 * Firebase Messaging cho iOS chỉ có bản Swift, Kotlin/Native không gọi trực tiếp được: `AppDelegate.swift` ghi token vào
 * `PushTokens.shared.set(...)` (xem docs/push.md). Ở đây chỉ đọc lại giá trị đó.
 */
actual fun platformPushTokenSource() = PushTokenSource { PushTokens.token.value }
