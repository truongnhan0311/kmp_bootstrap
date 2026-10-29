package kmp.bootstrap.appkit

import okio.Path
import okio.Path.Companion.toPath

actual fun platformCacheDir(appName: String): Path =
    ((System.getProperty("appkit.storage.dir") ?: (System.getProperty("user.home") + "/.appkit")) + "/cache/$appName").toPath()
