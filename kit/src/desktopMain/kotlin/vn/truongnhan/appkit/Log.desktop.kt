package vn.truongnhan.appkit

import okio.Path
import okio.Path.Companion.toPath

actual fun platformConsole(level: LogLevel, tag: String, message: String) { println("${level.label}/$tag: $message") }

actual fun platformLogDir(appName: String): Path =
    ((System.getProperty("appkit.storage.dir") ?: (System.getProperty("user.home") + "/.appkit")) + "/logs/$appName").toPath()
