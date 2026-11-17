# kmp_bootstrap

A shared toolkit for Kotlin Multiplatform apps (Android + iOS, plus a JVM desktop target for tests). **Batteries included, take only what you need**, in the spirit of Goravel for Go backends.

> New here (person or agent)? Read [AGENTS.md](AGENTS.md) first: it pulls and verifies the kit in one command. Then read `docs/<module>.md`. Every example in the docs is copied from code that compiles and runs in the test suite.

Each piece is a separate module. An app declares only the modules it uses; nothing else is pulled in.

> **Start a new app:** `node tools/new-app.mjs --name my-app --id com.acme.myapp --out ../my-app --modules network,push,platform,ui`. See [docs/new-app.md](docs/new-app.md).

## Modules

| Module | What it gives you | Depends on |
|---|---|---|
| `appkit-core` | `Language` (localized strings: en/vi/zh/es), `Navigator` + `DeepLinks`, `AppSettings` (remembered theme and language), `SvgParser`, `AppLog` (rotating log, secrets masked), `AppStorage` (prefs + secure store), `AppCrypto` (AES-256-GCM, SHA-256, HMAC-SHA256, unbiased random strings), `Hex`, `QrCode` (pure-Kotlin QR generator), `Gzip`, `LogExport` (collect logs for upload), `TtlCache`, `Jwt` | coroutines, serialization, datetime, okio, cryptography |
| `appkit-network` | `Session` (checking / signed in / signed out), `AuthedClient` (attaches JWT, refreshes the session for you), `HttpTransport` (failover, timeouts, GET retry), `get/post/put/patch/delete`, typed `ApiError`, GET cache, `Endpoints` (paths declared once), `LogUploader` (signed-URL log upload), `AppKit.start()` | core + Ktor |
| `appkit-push` | FCM push: device registration, navigation when a notification is tapped | network (+ Firebase on Android) |
| `appkit-platform` | open links, clipboard, share, pick image, time zone behind one interface (with a fake for tests); **app lock** with Face ID / fingerprint / device passcode (`AppLock`); **server-verified login** with Face ID / fingerprint / passcode, passkey style (`DeviceKeyLogin`) | core |
| `appkit-iot` | Tuya-style device protocol: device request signing (HMAC), device MQTT password, `header`+`msg` envelope with de-duplication, readable pairing codes, typed data points | core |
| `appkit-mqtt` | MQTT 3.1.1 client: auto-reconnect with backoff, password callback on every connect, Last Will, QoS 0/1 with resend after reconnect, messages as `Flow`; over WebSocket (all platforms) or TCP (JVM) | core + Ktor |
| `appkit-webrtc` | the shared half of a WebRTC call: expiring ICE/TURN servers (re-fetched automatically), SDP helpers (DTLS check, prefer H.264, bitrate cap, redaction for logs), `CallSession` (offer/answer/candidates, timeouts, ICE restart). The real peer plugs in through `RtcPeer` | core |
| `appkit-reminder` | local-notification reminders (fire even when the app is closed), repeating reminders that stay on time across DST | core |
| `appkit-ui` | shadcn-style Material 3 components, theme generated from web CSS, Tailwind-style layout, Bootstrap-style blocks, SVG icons, `QrImage`, a 5-tab starter app | core + Compose |

Only need crypto and logging? Take `appkit-core` alone: no Ktor, no Firebase (verified by `samples/core-only`).

## Install

Not published to Maven yet. Use it as a source library with `includeBuild` (put the repo next to your app, or add it as a git submodule):

```kotlin
// app settings.gradle.kts
includeBuild("../kmp_bootstrap")

// build.gradle.kts of the shared module (commonMain)
commonMain.dependencies {
    implementation("kmp.bootstrap:appkit-network")     // brings appkit-core
    implementation("kmp.bootstrap:appkit-platform")    // optional
}
```

Android: call `AppKitAndroid.init(context)` once in `Application.onCreate`. iOS: nothing extra (push: see `docs/push.md`; device-key login: see below).
No Android SDK / Xcode on the machine (CI, sandbox)? Add `-PdesktopOnly=true` to build and test the JVM target only.

## Use

```kotlin
// once, at startup
AppKit.start(AppKitConfig(name = "my-app", baseUrls = listOf("https://api.example.com"), contract = PlainJsonContract(Endpoints.taper("/api/v2"))))

// ANYWHERE: JWT attached and refreshed, @Serializable bodies, failures are ApiError with a kind
val me: User = AppKit.client.get("/api/v2/auth/me")
val feed: Feed = AppKit.client.get("/api/v2/feed", cache = CachePolicy.hours(1))   // cached 1 hour; stale copy when offline
val item: Item = AppKit.client.post("/api/v2/items", NewItem("x"))                  // put / patch alike
val maybe: Item? = AppKit.client.getOrNull("/api/v2/items/9")                        // 404 → null
AppKit.client.delete("/api/v2/items/9")
AppKit.refreshSession()                                                              // refresh now
AppKit.storage.prefs.putBool("dark", true)                                           // local prefs; AppKit.storage.tokens = secure tokens
AppLog.i("home", "opened")                                                           // console + rotating file
val box = AppCrypto.encryptText("secret", "password")                                // AES-256-GCM
val profiles = TtlCache<String, Profile>(1.hours)                                    // standalone cache, not tied to HTTP
profiles.getOrLoad(userId) { api.profile(userId) }                                   // N concurrent callers, one load
AppKit.client.sessionExpired.collect { goToLogin() }                                 // refresh token really dead
```

Password login: `client.raw("POST", AppKit.endpoints.login, body, auth = false)`, then `client.storeLogin(r)`.

## What it handles for you

| Situation | How |
|---|---|
| `Authorization: Bearer` and `X-App-Id` headers | automatic |
| Access token about to expire (< 2 min, read from the JWT `exp`) | refreshed **before** sending |
| Server says "token expired / revoked" | refresh, then **resend exactly once** |
| 8 requests get 401 at the same time | only **one** refresh-token exchange (refresh tokens are single use; several exchanges look like theft) |
| Network drops during refresh | tokens kept, retried next time (no false sign-out) |
| Refresh token really dead | tokens and cache cleared, `sessionExpired` emitted |
| Transient error on a READ (timeout, 5xx, 429) | GET retried (once by default); WRITES are never retried automatically (no duplicate writes) |
| Any failure | one `ApiError` with a `kind` (Network, Timeout, Unauthorized, NotFound, Invalid, Server, Parse…) and one log line with the `requestId` |
| Error text for the user | in the app's language, else the system's, else English (en/vi/zh/es); server messages kept as sent |
| Primary domain down (DNS) | `Failover(listOf(primary, backup))` tries the next host |
| GET cache (opt in per call) | TTL, stale-on-error, separate per user, invalidated on writes, wiped on sign-out |
| Logs | rotating files with age-based cleanup; passwords fully masked, tokens keep their last 5 characters for tracing |

Two backend styles, one mechanism: the **only** difference is the `AuthContract` (`PlainJsonContract` for plain JSON, `EnvelopeContract` for a `{code,message,data}` envelope) plus `Endpoints` (paths). A third backend style means implementing `AuthContract` (6 small functions).

## Face ID / fingerprint / passcode

Two separate features in `appkit-platform`, both using the OS prompt. The app never sees face, fingerprint or passcode data.

| | `AppLock` | `DeviceKeyLogin` |
|---|---|---|
| Purpose | lock the UI, like banking apps | sign in without typing a password |
| Talks to the server | no; the saved session stays as it was | yes; returns a fresh session |
| How | `DeviceAuth` prompt (biometrics, falls back to the device passcode) | ECDSA P-256 key in Android Keystore / iOS Secure Enclave that signs only after the prompt; the server keeps the public key and sends a short-lived, single-use challenge |
| Your part | lifecycle calls `onBackground()` / `onForeground()` | implement `DeviceKeyBackend` (4 calls) with your HTTP client |

```kotlin
val login = DeviceKeyLogin(deviceKeyStore(), backend, AppKit.storage.prefs, deviceId = { installId })
if (login.available()) login.enable(userId, "Minh's iPhone", "ios")      // Account screen, while signed in
when (val r = login.login(login.lastUser ?: userId, reason = "Sign in")) {
    is DeviceKeyLogin.Outcome.Success -> save(r.session)
    DeviceKeyLogin.Outcome.Cancelled -> Unit                              // stay on the sign-in screen
    DeviceKeyLogin.Outcome.Disabled,                                      // revoked on the server, or biometrics changed: key already removed
    DeviceKeyLogin.Outcome.LockedOut, DeviceKeyLogin.Outcome.Unavailable -> showPasswordLogin()
    is DeviceKeyLogin.Outcome.Error -> showRetry()                        // network / server error, feature stays on
}
```

- `allowPasscode = true` (default) also accepts the device passcode (iOS `.userPresence`; Android 11+ `DEVICE_CREDENTIAL`, biometrics only on older Android).
- Wire format, locked by a test vector shared with the Recam server: public key = base64 SPKI DER (Android) or 65-byte point (iOS); signature = base64 ECDSA SHA-256 DER over the challenge string in UTF-8.
- Android: `MainActivity` must be a `FragmentActivity` and call `AppKitPlatform.attach(this)`. iOS: add `NSFaceIDUsageDescription` to `Info.plist`, copy the Swift `SecureEnclaveDeviceKeyStore` from [docs/platform.md](docs/platform.md), and set `IosDeviceKeys.shared.store` at startup. Desktop: not supported (`Unavailable`).

## Layout

```
appkit-core/      Language  Navigator  AppSettings  Log  LogExport  Storage  Stores  Crypto  Gzip  QrCode  Svg  TtlCache  Jwt  → docs/core.md
appkit-network/   HttpTransport  AuthedClient  Session  Requests  Endpoints  AuthContract  Failover  ApiError  Cache  LogUploader  AppKit  → docs/network.md, docs/session.md
appkit-push/      Push  PushNavigation                                                   → docs/push.md
appkit-platform/  PlatformServices  DeviceAuth  AppLock  DeviceKeyStore  DeviceKeyLogin (+ fakes)  → docs/platform.md
appkit-iot/       DeviceSigner  MqttDeviceAuth  Envelope  DuplicateFilter  PairingCode  DpSchema  → docs/iot.md
appkit-mqtt/      MqttClient  MqttCodec  WebSocketConnector  TcpConnector(JVM)           → docs/mqtt.md
appkit-webrtc/    IceConfig  IceConfigSource  Sdp  CallSession  RtcPeer  Signaling       → docs/webrtc.md
appkit-reminder/  Reminders (Android AlarmManager, iOS UNUserNotificationCenter)         → docs/reminder.md
appkit-ui/        Theme  Components  Layout  Blocks  Charts  NavHost  QrImage  Starter*  → docs/ui.md
  each module: src/{commonMain,commonTest,desktopMain,androidMain,iosMain}  (expect/actual for platform parts)
tools/new-app.mjs        generate a new app (Android + iOS + desktop) wired to the kit
tools/shadcn-theme.mjs   generate light/dark AppColors from shadcn CSS
tools/svg-to-kotlin.mjs  generate AppIcons from appkit-ui/icons/*.svg
tools/sync-snippets.mjs  copy `// region:` blocks from *Examples.kt into the docs (--check for CI)
samples/core-only/       sample app that uses appkit-core only
docs/*.md                per-module docs; every example is code that ran in the tests
AGENTS.md                guide for agents: pull, use, change
CHANGELOG.md
```

## Tests

```sh
./gradlew -PdesktopOnly=true desktopTest     # JVM, no Android SDK / Xcode needed (:appkit-ui: compileTestKotlinDesktop only)
./gradlew build                              # with Android Studio / Xcode: full Android + iOS build
node --test tools/*.test.mjs                 # tool tests
node tools/sync-snippets.mjs --check         # docs match the example code
```

Verified on the JVM: 270 tests (core 96, network 66, platform 25, webrtc 23, mqtt 22, iot 16, push 12, reminder 10; the 2 tests against a real MQTT broker skip themselves unless an environment variable is set) plus 9 tool tests. Projects generated by `new-app` compile against the kit, and every docs example is compiled and run.

**Not yet verified** (needs Android Studio, Xcode or a device): every `androidMain` / `iosMain` file (Keystore, Keychain, Secure Enclave, BiometricPrompt, LocalAuthentication, logcat, FCM, AlarmManager, UNUserNotificationCenter, the Swift samples) and the Android/iOS builds. `appkit-ui` **compiles** but has not run or been screenshotted (this build environment cannot download `androidx.collection`). No real backend tested except where a doc says so.

## License

MIT, see [LICENSE](LICENSE).
