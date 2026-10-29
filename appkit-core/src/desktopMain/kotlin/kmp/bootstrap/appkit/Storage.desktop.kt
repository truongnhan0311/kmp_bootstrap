package kmp.bootstrap.appkit

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/** Thư mục dữ liệu; đổi bằng `-Dappkit.storage.dir=…` (test dùng thư mục tạm). */
private fun baseDir(): File = File(System.getProperty("appkit.storage.dir") ?: (System.getProperty("user.home") + "/.appkit")).also { it.mkdirs() }

actual fun platformPrefs(name: String): KeyValueStore = FileKeyValueStore(File(baseDir(), "$name.prefs"), secret = false)

/** Desktop KHÔNG mã hoá: file chỉ chủ sở hữu đọc được (0600). Đủ để chạy thử, không phải két sắt. */
actual fun platformSecrets(name: String): KeyValueStore = FileKeyValueStore(File(baseDir(), "$name.secrets"), secret = true)

/** Lưu cặp khoá–giá trị vào một file .properties; ghi qua file tạm rồi đổi tên để không bao giờ để lại file ghi dở. */
class FileKeyValueStore(private val file: File, private val secret: Boolean) : KeyValueStore {
    private val lock = Any()
    private val props = Properties().also { p -> if (file.exists()) file.inputStream().use { runCatching { p.load(it) } } }

    override fun get(key: String): String? = synchronized(lock) { props.getProperty(key) }

    override fun put(key: String, value: String?) = synchronized(lock) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { props.store(it, null) }
        if (secret) runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Unit
    }
}
