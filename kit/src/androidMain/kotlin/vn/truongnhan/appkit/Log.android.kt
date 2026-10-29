package vn.truongnhan.appkit

import android.util.Log
import okio.Path
import okio.Path.Companion.toPath

actual fun platformConsole(level: LogLevel, tag: String, message: String) {
    when (level) { LogLevel.Debug -> Log.d(tag, message); LogLevel.Info -> Log.i(tag, message); LogLevel.Warn -> Log.w(tag, message); LogLevel.Error -> Log.e(tag, message) }
}

actual fun platformLogDir(appName: String): Path = (AppKitAndroid.context().filesDir.path + "/logs/$appName").toPath()
