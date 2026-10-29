package kmp.bootstrap.appkit

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

/** `preferredLanguages` là danh sách theo thứ tự ưu tiên của người dùng, ví dụ "vi-VN" — lấy phần ngôn ngữ đầu tiên. */
actual fun systemLanguage(): String = (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en"
