#!/usr/bin/env node
// Sinh MỘT DỰNG ÁN APP MỚI (Android + iOS + desktop để chạy thử) đã nối sẵn kmp_bootstrap — "bootstrap" đúng nghĩa.
//   node tools/new-app.mjs --name my-app --id com.acme.myapp --out ../my-app [--modules network,push,reminder,platform,ui,mqtt,webrtc] [--css ../web/src/index.css] [--bootstrap ../kmp_bootstrap]
// Chạy xong: mở `my-app` bằng Android Studio (Android) hoặc `cd iosApp && xcodegen && open *.xcodeproj` (iOS); chạy thử desktop: `./gradlew :composeApp:run`.
import { mkdirSync, writeFileSync, cpSync, existsSync, readFileSync } from 'node:fs'
import { join, relative, resolve, dirname } from 'node:path'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const args = process.argv.slice(2)
const opt = (n, d) => { const i = args.indexOf('--' + n); return i >= 0 ? args[i + 1] : d }
const here = dirname(fileURLToPath(import.meta.url)), bootstrap = resolve(opt('bootstrap', join(here, '..')))
const name = opt('name'), id = opt('id'), outArg = opt('out')
if (!name || !id || !outArg) { console.error('Cách dùng: new-app.mjs --name my-app --id com.acme.myapp --out ../my-app [--modules network,push,reminder,platform,ui,mqtt,webrtc] [--css file.css] [--bootstrap đường/dẫn]'); process.exit(2) }
if (!/^[a-z][a-z0-9-]*$/.test(name)) { console.error('--name chỉ gồm chữ thường, số, gạch ngang (ví dụ my-app)'); process.exit(2) }
if (!/^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$/.test(id)) { console.error('--id là package kiểu com.acme.myapp (chữ thường, có ít nhất một dấu chấm)'); process.exit(2) }
const out = resolve(outArg)
if (existsSync(out) && existsSync(join(out, 'settings.gradle.kts'))) { console.error(`${out} đã có dự án — chọn thư mục khác`); process.exit(1) }
const modules = new Set((opt('modules', 'network,ui')).split(',').map(s => s.trim()).filter(Boolean))
const valid = ['network', 'push', 'reminder', 'platform', 'ui', 'mqtt', 'webrtc']
for (const m of modules) if (!valid.includes(m)) { console.error(`Module lạ: ${m} (có: ${valid.join(', ')})`); process.exit(2) }
if (modules.has('push')) modules.add('network')
const pkg = id, pkgPath = pkg.replaceAll('.', '/')
const title = name.split('-').map(w => w[0].toUpperCase() + w.slice(1)).join(' ')
const hasUi = modules.has('ui'), hasNet = modules.has('network') || modules.has('push')
const coords = ['core', ...[...modules].map(m => m)].map(m => `kmp.bootstrap:appkit-${m}`)
const w = (path, content) => { const p = join(out, path); mkdirSync(dirname(p), { recursive: true }); writeFileSync(p, content) }
const rel = relative(out, bootstrap) || '.'

// ---- gradle gốc ----
w('settings.gradle.kts', `val desktopOnly = providers.gradleProperty("desktopOnly").orNull == "true"

pluginManagement {
    val desktopOnlyProp = providers.gradleProperty("desktopOnly").orNull == "true"
    repositories { mavenCentral(); gradlePluginPortal(); if (!desktopOnlyProp) google() }
}
dependencyResolutionManagement { repositories { mavenCentral(); if (!desktopOnly) google() } }

rootProject.name = "${name}"
// kmp_bootstrap là thư viện nguồn: module nào khai ở dependencies mới được kéo vào.
includeBuild("${rel.replaceAll('\\', '/')}")
include(":composeApp")
if (!desktopOnly) include(":androidApp")
`)
w('build.gradle.kts', `buildscript {
    if (providers.gradleProperty("desktopOnly").orNull != "true") {
        repositories { google(); mavenCentral() }
        dependencies { classpath("com.android.tools.build:gradle:\${libs.versions.agp.get()}") }
    }
}
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
}
`)
w('gradle.properties', readFileSync(join(bootstrap, 'gradle.properties'), 'utf8'))
mkdirSync(join(out, 'gradle'), { recursive: true })
cpSync(join(bootstrap, 'gradle/wrapper'), join(out, 'gradle/wrapper'), { recursive: true })
cpSync(join(bootstrap, 'gradle/libs.versions.toml'), join(out, 'gradle/libs.versions.toml'))
for (const f of ['gradlew', 'gradlew.bat']) cpSync(join(bootstrap, f), join(out, f))
w('.gitignore', '.gradle/\nbuild/\n.kotlin/\nlocal.properties\n*.iml\n.idea/\niosApp/*.xcodeproj\nxcuserdata/\nDerivedData/\n')

// ---- composeApp (mã dùng chung) ----
const deps = coords.map(c => `            implementation("${c}")`).join('\n')
w('composeApp/build.gradle.kts', `import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

val desktopOnly = providers.gradleProperty("desktopOnly").orNull == "true"
if (!desktopOnly) apply(plugin = "com.android.library")
if (desktopOnly) configurations.configureEach { exclude(group = "androidx.annotation"); exclude(group = "androidx.lifecycle") }

kotlin {
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    if (!desktopOnly) {
        androidTarget { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
        listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { it.binaries.framework { baseName = "ComposeApp"; isStatic = true } }
    }
    sourceSets {
        commonMain.dependencies {
${deps}
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        val desktopMain by getting { dependencies { implementation(compose.desktop.currentOs); implementation(libs.kotlinx.coroutines.swing) } }
    }
}

if (!desktopOnly) apply(from = "android.gradle.kts")
`)
w('composeApp/android.gradle.kts', `extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
    namespace = "${pkg}.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
`)
if (hasUi) {
  const css = opt('css', join(here, 'templates/default-theme.css'))
  const colorsPath = join(out, `composeApp/src/commonMain/kotlin/${pkgPath}/AppColors.kt`)
  mkdirSync(dirname(colorsPath), { recursive: true })
  execFileSync('node', [join(here, 'shadcn-theme.mjs'), css, '--package', pkg, '--name', 'App', ...(opt('css') ? ['--light', opt('light', ':root'), '--dark', opt('dark', '.dark')] : []), '--out', colorsPath], { stdio: ['ignore', 'inherit', 'inherit'] })
}

const imports = ['import androidx.compose.runtime.Composable', 'import androidx.compose.runtime.remember',
  ...(hasUi ? ['import kmp.bootstrap.appkit.AppSettings', 'import kmp.bootstrap.appkit.Navigator', 'import kmp.bootstrap.appkit.ui.*'] : []),
  ...(hasNet ? ['import kmp.bootstrap.appkit.*'] : ['import kmp.bootstrap.appkit.*'])]
w(`composeApp/src/commonMain/kotlin/${pkgPath}/App.kt`, `package ${pkg}

${[...new Set(imports)].join('\n')}

/** Các màn của app. Thêm màn = thêm một giá trị, rồi một nhánh \`when\` ở App(). */
sealed interface Route {
    data object Home : Route
}

/**
 * Khởi động hạ tầng MỘT lần (gọi từ MainActivity / MainViewController / main()). Đổi địa chỉ máy chủ và Endpoints cho đúng backend của bạn.
${hasNet ? ` * Chưa có backend: giữ nguyên, các lời gọi mạng sẽ báo lỗi mạng (đã có chữ báo lỗi theo ngôn ngữ).` : ''} */
fun startApp() {
${hasNet ? `    AppKit.start(AppKitConfig(
        name = "${name}",
        baseUrls = listOf("https://api.example.com"),                    // TODO: địa chỉ máy chủ của bạn (nhiều địa chỉ = có dự phòng)
        contract = PlainJsonContract(Endpoints.taper("/api/v2")),         // TODO: EnvelopeContract(...) nếu backend trả {code,message,data}
    ))` : `    // app không dùng appkit-network: chỉ có hạ tầng lõi (log, lưu trữ…)`}
}

@Composable
fun App() {
${hasUi ? `    val settings = remember { AppSettings(AppKit.storage.prefs) }         // nhớ giao diện sáng/tối + ngôn ngữ qua các lần mở app
    val nav = remember { Navigator<Route>(Route.Home) }
    AppTheme(light = AppLightColors, dark = AppDarkColors, settings = settings) {
        NavHost(nav) { route ->
            when (route) {
                Route.Home -> AppScaffold(topBar = { AppHeader("${title}") }) {
                    Page { HeroBlock("Xin chào", "Đây là app ${title} — thay nội dung này.") }
                }
            }
        }
    }` : `    // thêm module appkit-ui để có component, theme, điều hướng`}
}
`)
w(`composeApp/src/desktopMain/kotlin/${pkgPath}/Main.kt`, `package ${pkg}

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/** Chạy thử trên máy tính: ./gradlew :composeApp:run (nhanh hơn mở trình giả lập). */
fun main() {
    startApp()
    application { Window(onCloseRequest = ::exitApplication, title = "${title}") { App() } }
}
`)
w(`composeApp/src/iosMain/kotlin/${pkgPath}/MainViewController.kt`, `package ${pkg}

import androidx.compose.ui.window.ComposeUIViewController

/** Swift gọi hàm này để dựng giao diện Compose (xem iosApp/iosApp/ContentView.swift). */
fun MainViewController() = ComposeUIViewController(configure = { startApp() }) { App() }
`)

// ---- androidApp ----
w('androidApp/build.gradle.kts', `plugins {
    id("com.android.application")
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
}
android {
    namespace = "${pkg}"
    compileSdk = 36
    defaultConfig { applicationId = "${pkg}"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
}
`)
w('androidApp/src/main/AndroidManifest.xml', `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application android:name=".MyApplication" android:label="${title}" android:supportsRtl="true">
        <activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
`)
w(`androidApp/src/main/kotlin/${pkgPath}/MyApplication.kt`, `package ${pkg}

import android.app.Application
import kmp.bootstrap.appkit.AppKitAndroid

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppKitAndroid.init(this)      // một lần: lưu trữ, log, cache cần Context
        startApp()
    }
}
`)
w(`androidApp/src/main/kotlin/${pkgPath}/MainActivity.kt`, `package ${pkg}

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
${modules.has('platform') ? 'import kmp.bootstrap.appkit.platform.AppKitPlatform\n' : ''}
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
${modules.has('platform') ? '        AppKitPlatform.attach(this)       // trước setContent: đăng ký bộ chọn ảnh\n' : ''}        setContent { App() }
    }
}
`)

// ---- iosApp (XcodeGen) ----
w('iosApp/project.yml', `name: ${title.replaceAll(' ', '')}
options:
  bundleIdPrefix: ${pkg.split('.').slice(0, -1).join('.')}
targets:
  ${title.replaceAll(' ', '')}:
    type: application
    platform: iOS
    deploymentTarget: "15.0"
    sources: [iosApp]
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: ${pkg}
        FRAMEWORK_SEARCH_PATHS: "$(SRCROOT)/../composeApp/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)"
        OTHER_LDFLAGS: "-framework ComposeApp"
    preBuildScripts:
      - name: Build Kotlin framework
        script: |
          cd "$SRCROOT/.."
          ./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
`)
w('iosApp/iosApp/iOSApp.swift', `import SwiftUI

@main
struct iOSApp: App {
    var body: some Scene { WindowGroup { ContentView() } }
}
`)
w('iosApp/iosApp/ContentView.swift', `import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { MainViewControllerKt.MainViewController() }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View { ComposeView().ignoresSafeArea(.keyboard) }
}
`)
w('README.md', `# ${title}

Sinh bởi \`kmp_bootstrap/tools/new-app.mjs\`. Module đang dùng: ${[...modules].join(', ')}.

- Chạy thử trên máy tính: \`./gradlew :composeApp:run\`
- Android: mở thư mục này bằng Android Studio, chạy \`androidApp\`
- iOS: \`cd iosApp && xcodegen && open *.xcodeproj\` (cần Xcode + XcodeGen)
- Địa chỉ máy chủ và đường dẫn API: \`composeApp/src/commonMain/kotlin/${pkgPath}/App.kt\` (hàm \`startApp\`)
- Màu: sửa CSS rồi sinh lại \`node ${rel}/tools/shadcn-theme.mjs <css> --package ${pkg} --name App --out composeApp/src/commonMain/kotlin/${pkgPath}/AppColors.kt\`
- Hướng dẫn dùng từng module: \`${rel}/docs/\`
`)
console.log(`\nĐã tạo ${out}\nModule: ${[...modules].join(', ')}\n\nBước tiếp:\n  cd ${outArg}\n  ./gradlew -PdesktopOnly=true :composeApp:compileKotlinDesktop   # kiểm biên dịch (không cần Android SDK)\n  ./gradlew :composeApp:run                                          # chạy thử desktop\n`)
