package kmp.bootstrap.appkit

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSMutableDictionary
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private class DefaultsStore(private val prefix: String) : KeyValueStore {
    private val d = NSUserDefaults.standardUserDefaults
    override fun get(key: String): String? = d.stringForKey(prefix + key)
    override fun put(key: String, value: String?) { if (value == null) d.removeObjectForKey(prefix + key) else d.setObject(value, prefix + key) }
}

actual fun platformPrefs(name: String): KeyValueStore = DefaultsStore("$name.")

/** Keychain (genericPassword), chỉ đọc được sau lần mở khoá đầu tiên và không sang máy khác. */
actual fun platformSecrets(name: String): KeyValueStore = KeychainStore(service = name)

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class KeychainStore(private val service: String) : KeyValueStore {
    private fun query(key: String): NSMutableDictionary = NSMutableDictionary().also {
        it.setObject(CFBridgingRelease(kSecClassGenericPassword), CFBridgingRelease(kSecClass))
        it.setObject(service, CFBridgingRelease(kSecAttrService))
        it.setObject(key, CFBridgingRelease(kSecAttrAccount))
    }

    override fun get(key: String): String? = memScoped {
        val q = query(key).also {
            it.setObject(CFBridgingRelease(kCFBooleanTrue), CFBridgingRelease(kSecReturnData))
            it.setObject(CFBridgingRelease(kSecMatchLimitOne), CFBridgingRelease(kSecMatchLimit))
        }
        val out = alloc<CFTypeRefVar>()
        if (SecItemCopyMatching(CFBridgingRetain(q) as CFDictionaryRef, out.ptr) != errSecSuccess) return null
        (CFBridgingRelease(out.value) as? NSData)?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }
    }

    override fun put(key: String, value: String?) {
        SecItemDelete(CFBridgingRetain(query(key)) as CFDictionaryRef)
        if (value == null) return
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val q = query(key).also {
            it.setObject(data, CFBridgingRelease(kSecValueData))
            it.setObject(CFBridgingRelease(kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly), CFBridgingRelease(kSecAttrAccessible))
        }
        SecItemAdd(CFBridgingRetain(q) as CFDictionaryRef, null)
    }
}
