package kmp.bootstrap.appkit

actual fun systemLanguage(): String = java.util.Locale.getDefault().language.ifEmpty { "en" }
