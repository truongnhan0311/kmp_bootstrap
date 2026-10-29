package kmp.bootstrap.appkit.push

actual fun platformDeviceInfo() = DeviceInfo(name = System.getProperty("user.name") ?: "desktop", model = "desktop", os = System.getProperty("os.name") ?: "", platform = "desktop")

/** Desktop không có FCM: không có token thì PushManager không làm gì. */
actual fun platformPushTokenSource() = PushTokenSource { null }
