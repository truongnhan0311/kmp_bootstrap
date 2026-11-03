package kmp.bootstrap.appkit

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding

private class DefaultsStore(private val prefix: String) : KeyValueStore {
    private val d = NSUserDefaults.standardUserDefaults
    override fun get(key: String): String? = d.stringForKey(prefix + key)
    override fun put(key: String, value: String?) { if (value == null) d.removeObjectForKey(prefix + key) else d.setObject(value, prefix + key) }
}

actual fun platformPrefs(name: String): KeyValueStore = DefaultsStore("$name.")

/** Keychain (genericPassword), chỉ đọc được sau lần mở khoá đầu tiên và không sang máy khác. */
actual fun platformSecrets(name: String): KeyValueStore = KeychainStore(service = name)

/**
 * Keychain bằng API CoreFoundation THUẦN (từ điển CF + `CFDictionaryAddValue`), không qua `NSMutableDictionary.setObject`: các hằng `kSec*` là `CFStringRef`,
 * ép sang `NSCopyingProtocol` không biên dịch được ở Kotlin/Native (lỗi "Argument type mismatch: Any? / NSCopyingProtocol" trên Codemagic) và
 * `CFBridgingRelease` lên hằng số dùng chung là nhả nhầm. Chuỗi/dữ liệu của mình thì `CFBridgingRetain` (+1) rồi `CFRelease` sau khi từ điển đã giữ bản của nó.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class KeychainStore(private val service: String) : KeyValueStore {
    private fun addOwned(dict: CFMutableDictionaryRef?, key: COpaquePointer?, value: Any?) {
        val cf = CFBridgingRetain(value)
        CFDictionaryAddValue(dict, key, cf)
        CFRelease(cf)
    }

    /** Truy vấn chung: lớp genericPassword + dịch vụ + tài khoản (= khoá). Người gọi phải `CFRelease`. */
    private fun newQuery(key: String): CFMutableDictionaryRef? {
        val dict = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        addOwned(dict, kSecAttrService, service)
        addOwned(dict, kSecAttrAccount, key)
        return dict
    }

    override fun get(key: String): String? = memScoped {
        val query = newQuery(key)
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val out = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, out.ptr)
        CFRelease(query)
        if (status != errSecSuccess) return@memScoped null
        val data = CFBridgingRelease(out.value) as? NSData ?: return@memScoped null // kết quả của SecItemCopyMatching là +1: nhận quyền sở hữu
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    override fun put(key: String, value: String?) {
        val old = newQuery(key)
        SecItemDelete(old)
        CFRelease(old)
        if (value == null) return
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val query = newQuery(key)
        addOwned(query, kSecValueData, data)
        // Chỉ đọc được sau lần mở khoá đầu tiên và không sang máy khác (không vào bản sao lưu iCloud).
        CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        SecItemAdd(query, null)
        CFRelease(query)
    }
}
