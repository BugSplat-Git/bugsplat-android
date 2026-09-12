[![bugsplat-github-banner-basic-outline](https://user-images.githubusercontent.com/20464226/149019306-3186103c-5315-4dad-a499-4fd1df408475.png)](https://bugsplat.com)
<br/>

# <div align="center">BugSplat</div>

### **<div align="center">Crash and error reporting built for busy developers.</div>**

<div align="center">
    <a href="https://bsky.app/profile/bugsplatco.bsky.social"><img alt="Follow @bugsplatco on Bluesky" src="https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fpublic.api.bsky.app%2Fxrpc%2Fapp.bsky.actor.getProfile%2F%3Factor%3Dbugsplatco.bsky.social&query=%24.followersCount&style=social&logo=bluesky&label=Follow%20%40bugsplatco.bsky.social"></a>
    <a href="https://discord.gg/bugsplat"><img alt="Join BugSplat on Discord" src="https://img.shields.io/discord/664965194799251487?label=Join%20Discord&logo=Discord&style=social"></a>
</div>

<br/>

## Introduction 👋

bugsplat-android 9 is BugSplat's Android SDK, built on [bugsplat-native](https://github.com/BugSplat-Git/bugsplat-native): native crashes are captured **out of process** by `BugSplatMonitor` (Crashpad), uncaught JVM exceptions, ANRs and main-thread hangs are reported too, and every report carries a client-side crash signature and the device's `environment` ("Android 14 (API 34) arm64-v8a; Google Pixel 8"). Visit [bugsplat.com](https://www.bugsplat.com) for more information and to sign up for an account.

The Kotlin source lives in [`bugsplat-native/bindings/kotlin`](https://github.com/BugSplat-Git/bugsplat-native/tree/main/bindings/kotlin); this repository packages, tests and publishes it and carries the example app.

## Requirements 📋

- Android Gradle Plugin 8.5.1+ (16 KB page-size packaging), `minSdk 26` (API 29+ recommended)
- Kotlin 1.9+ (the SDK is Kotlin; Java callers use the `@JvmStatic` API)
- Every `.so` is 16 KB aligned (NDK r28), as Google Play requires for Android 15+

## Integration 🏗️

```kotlin
dependencies {
    implementation("com.bugsplat:bugsplat-android:9.0.0")
}
```

Or download `bugsplat-android-x.y.z.aar` from the [Releases](https://github.com/BugSplat-Git/bugsplat-android/releases) page and add `implementation(files("libs/bugsplat-android-x.y.z.aar"))`. The AAR declares the `INTERNET` permission it needs.

## Usage 🧑‍💻

Initialize once, in `Application.onCreate()`:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        BugSplat.init(this, BugSplatOptions("fred", "MyApp", BuildConfig.VERSION_NAME).apply {
            uploadPolicy = UploadPolicy.DIALOG   // ask on the next launch; QUIET; MANUAL
            hangTimeoutMs = 5000                 // main-thread hang detection
            user = "ada"
            email = "ada@example.com"
            attribute("build", BuildConfig.BUILD_TYPE)
            attachment(File(filesDir, "app.log"))
        })
    }
}
```

Everything can change later, from any thread, and the values in force when the app crashes are what the report carries:

```kotlin
BugSplat.user = "ada"
BugSplat.setAttribute("level", "3")
BugSplat.addAttachment(File(filesDir, "session.log"))
BugSplat.environment = "Android 14 (API 34) arm64-v8a; Google Pixel 8 (kiosk build)"
```

| Report | Call |
|---|---|
| Native crash (SIGSEGV, abort, stack overflow…) | automatic, out of process |
| Uncaught JVM exception | automatic |
| ANR | automatic, imported on the next launch (API 30+) |
| Main-thread hang | `hangTimeoutMs > 0` |
| Snapshot without crashing | `BugSplat.captureReport()` |
| Handled exception | `BugSplat.postReport(throwable)` |
| User feedback | `BugSplat.postFeedback(title, description, attachments)` |
| Pending reports (MANUAL policy) | `BugSplat.pendingReports()` → `send()` / `discard()` |

`BugSplat.init` returns a result code; `ERR_MONITOR_NOT_FOUND` means the APK does not contain `libBugSplatMonitor.so` for the device's ABI.

## Symbols 🔣

Upload Breakpad symbols for your unstripped `.so` files with the same version you pass to `BugSplatOptions`:

```
symbol-upload -b fred -a MyApp -v 1.2.3 -d app/build/intermediates/merged_native_libs/release/out/lib -f "**/*.so" -m
```

See [docs.bugsplat.com](https://docs.bugsplat.com/education/getting-started/integrations/mobile/android).

## Migrating from 1.x ⬆️

| 1.x | 9.x |
|---|---|
| `BugSplat.init(activity, database, app, version, attributes, attachments)` | `BugSplat.init(context, BugSplatOptions(database, app, version).apply { … })` from `Application.onCreate()` |
| Attributes/attachments fixed at init | mutable at any time: `setAttribute`, `addAttachment`, `user`, `email`, … |
| Crashpad uploads directly to `crashpad.php` | BugSplat's presigned upload flow |
| Native crashes only | plus uncaught JVM exceptions, ANRs, hangs, handled exceptions, feedback |
| `libcrashpad_handler.so` + `libcurl.so` | `libBugSplatMonitor.so` (no libcurl) |
| `BugSplat.crash()` | crash from your own JNI code (see the example's `Crasher`) |
| `SymbolUploader` | unchanged |

There is no compatibility layer: the 1.x API is gone.

## Building from source 🔧

```
git clone --recursive https://github.com/BugSplat-Git/bugsplat-android.git
./gradlew -Pbugsplat.crashpadRoot=/path/to/crashpad :bugsplat:assembleRelease :example:assembleDebug
```

`crashpadRoot` is a Crashpad checkout with GN builds in `out/android-<arm64|arm|x64>-release`; `.github/workflows/build.yml` shows the exact commands, and `bugsplat-native/docs/platforms/android.md` explains the pieces.

## Contributing 🤝

BugSplat loves open source! Please open an issue or pull request here or in bugsplat-native.
