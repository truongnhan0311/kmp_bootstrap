package kmp.bootstrap.appkit

import okio.Path
import okio.Path.Companion.toPath

actual fun platformCacheDir(appName: String): Path = (AppKitAndroid.context().cacheDir.path + "/appkit/$appName").toPath()
