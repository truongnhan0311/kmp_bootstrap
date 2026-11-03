package kmp.bootstrap.appkit

import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSLog
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

actual fun platformConsole(level: LogLevel, tag: String, message: String) { NSLog("%@/%@: %@", level.label, tag, message) /* %@ chứ không phải %s: tham số Kotlin được truyền là NSString, %s đọc nó như con trỏ C và crash */ }

actual fun platformLogDir(appName: String): Path {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
    return "$caches/logs/$appName".toPath()
}
