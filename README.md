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

The bugsplat-android library enables posting native crash reports to BugSplat from Android devices. Visit [bugsplat.com](https://www.bugsplat.com) for more information and to sign up for an account.

## Requirements 📋

- **Android Gradle Plugin (AGP)**: 8.5.1 or higher (for 16KB page size support)
- **Android NDK**: r27 or higher recommended
- **minSdkVersion**: 21 or higher
- **targetSdkVersion**: 35 or higher recommended

### 16KB Page Size Support

Starting November 1st, 2025, Google Play requires all new apps and updates targeting Android 15+ to [support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes). The BugSplat Android SDK is built with 16KB ELF alignment to comply with this requirement.

To ensure your app is 16KB compatible:
1. Use AGP 8.5.1 or higher
2. Use `useLegacyPackaging false` in your `packagingOptions` (this enables proper 16KB zip alignment)
3. If you have your own native code, ensure it's compiled with 16KB ELF alignment

## Integration 🏗️

BugSplat supports multiple methods for installing the bugsplat-android library in a project.

### Package Manager

Coming soon!

### Manual Setup

To integrate BugSplat into your Android application using the AAR file:

1. **Download the AAR file**
   - Go to the [Releases](https://github.com/BugSplat-Git/bugsplat-android/releases) page of the BugSplat Android repository
   - Download the latest `bugsplat-android-x.y.z.aar` file (where x.y.z is the version number)

2. **Add the AAR to your project**
   - Create a `libs` directory in your app module if it doesn't already exist
   - Copy the downloaded AAR file into the `libs` directory

3. **Configure your app's build.gradle file**
   - Open your app-level `build.gradle` file
   - Add the following to the dependencies section:

   ```gradle
   dependencies {
       // Other dependencies...
       implementation files('libs/bugsplat-android-x.y.z.aar') // Replace x.y.z with the actual version
   }
   ```

4. **Add the required permissions**
   - Open your `AndroidManifest.xml` file
   - Add the following permissions:

   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
   ```

5. **Sync your project**
   - Click "Sync Now" in the notification that appears, or select "Sync Project with Gradle Files" from the File menu

After completing these steps, you can start using BugSplat in your Android application. See the Usage section below for details on how to initialize and configure BugSplat.

## Usage 🧑‍💻

### Configuration

To configure BugSplat to handle native crashes, simply call `initBugSplat` with the desired arguments. Be sure that the value you provide for `database` matches the value in the BugSplat web app.

```kotlin
BugSplatBridge.initBugSplat(this, database, application, version)
```

You can also add file attributes, and/or file attachments to your crash reports.

Kotlin
```kotlin
val attributes = mapOf(
    "key1" to "value1",
    "key2" to "value2",
    "environment" to "development"
)

val attachmentFileName = "log.txt"
createAttachmentFile(attachmentFileName)
val attachmentPath = applicationContext.getFileStreamPath(attachmentFileName).absolutePath
val attachments = arrayOf(attachmentPath)

BugSplatBridge.initBugSplat(this, "fred", "my-android-crasher", "2.0.0", attributes, attachments)
```

Java
```java
Map<String, String> attributes = new HashMap<>();
attributes.put("key1", "value1");
attributes.put("key2", "value2");
attributes.put("environment", "development");

String attachmentFileName = "log.txt";
createAttachmentFile(attachmentFileName);
String attachmentPath = getApplicationContext().getFileStreamPath(attachmentFileName).getAbsolutePath();
String[] attachments = new String[]{attachmentPath};

BugSplatBridge.initBugSplat(this, "fred", "my-android-crasher", "2.0.0", attributes, attachments);
```

### Symbol Upload

To symbolicate crash reports, you must upload your app's `.so` files to the BugSplat backend. The BugSplat Android SDK provides two ways to upload symbols:

#### 1. Using the Built-in Symbol Uploader

The BugSplat Android SDK includes a built-in symbol uploader that can be used to upload symbols programmatically:

```java
// Upload symbols asynchronously
BugSplat.uploadSymbols(context, "YourDatabase", "YourApp", "1.0.0", nativeLibsDir);

// Or with client credentials (recommended for production)
BugSplat.uploadSymbols(context, "YourDatabase", "YourApp", "1.0.0", 
                      "your_client_id", "your_client_secret", nativeLibsDir);

// Or upload symbols synchronously (blocking)
try {
    BugSplat.uploadSymbolsBlocking(context, "YourDatabase", "YourApp", "1.0.0", nativeLibsDir);
    
    // Or with client credentials
    BugSplat.uploadSymbolsBlocking(context, "YourDatabase", "YourApp", "1.0.0",
                                  "your_client_id", "your_client_secret", nativeLibsDir);
} catch (IOException e) {
    Log.e("YourApp", "Failed to upload symbols", e);
}
```

This approach requires the `symbol-upload` executable to be included in your app's assets directory. See the [Example App README](example/README.md) for more details on how to set this up.

#### 2. Using Gradle Build Tasks

You can also add a Gradle task to your build process to automatically upload symbols when you build your app. Here's an example of how to set this up:

```gradle
// BugSplat configuration
ext {
    bugsplatDatabase = "your_database_name" // Replace with your BugSplat database name
    bugsplatAppName = "your_app_name"       // Replace with your application name
    bugsplatAppVersion = android.defaultConfig.versionName
    // Optional: Add your BugSplat API credentials for symbol upload
    bugsplatClientId = ""     // Replace with your BugSplat API client ID (optional)
    bugsplatClientSecret = "" // Replace with your BugSplat API client secret (optional)
}

// Task to upload debug symbols for native libraries
task uploadBugSplatSymbols {
    doLast {
        // Path to the merged native libraries
        def nativeLibsDir = "${buildDir}/intermediates/merged_native_libs/debug/out/lib"
        
        // Check if the directory exists
        def nativeLibsDirFile = file(nativeLibsDir)
        if (!nativeLibsDirFile.exists()) {
            logger.warn("Native libraries directory not found: ${nativeLibsDir}")
            return
        }
        
        // Path to the symbol-upload executable
        def symbolUploadPath = "path/to/symbol-upload" // Adjust this path
        
        // Build the command with the directory and glob pattern
        def command = [
            symbolUploadPath,
            "-b", project.ext.bugsplatDatabase,
            "-a", project.ext.bugsplatAppName,
            "-v", project.ext.bugsplatAppVersion,
            "-d", nativeLibsDirFile.absolutePath,
            "-f", "**/*.so",
            "-m"  // Run dumpsyms
        ]
        
        // Add client credentials if provided
        if (project.ext.has('bugsplatClientId') && project.ext.bugsplatClientId) {
            command.add("-i")
            command.add(project.ext.bugsplatClientId)
            command.add("-s")
            command.add(project.ext.bugsplatClientSecret)
        }
        
        // Execute the command
        // ... (see example app for full implementation)
    }
}

// Run the symbol upload task after the assembleDebug task
tasks.whenTaskAdded { task ->
    if (task.name == 'assembleDebug') {
        task.finalizedBy(uploadBugSplatSymbols)
    }
}
```

See the [Example App README](example/README.md) for a complete implementation of this approach.

#### 3. Using the Command-Line Tool

You can also use BugSplat's cross-platform tool, [symbol-upload](https://docs.bugsplat.com/education/faq/how-to-upload-symbol-files-with-symbol-upload) directly from the command line:

```sh
# Download the symbol-upload tool
# macOS
curl -sL -O "https://octomore.bugsplat.com/download/symbol-upload-macos" && chmod +x symbol-upload-macos

# Windows
Invoke-WebRequest -Uri "https://app.bugsplat.com/download/symbol-upload-windows.exe" -OutFile "symbol-upload-windows.exe"

# Linux
curl -sL -O  "https://app.bugsplat.com/download/symbol-upload-linux" && chmod +x symbol-upload-linux

# Upload symbols
./symbol-upload-macos -b DATABASE -a APPLICATION -v VERSION -i CLIENT_ID -s CLIENT_SECRET -d NATIVE_LIBS_DIR -f "**/*.so" -m
```

The `-d` argument specifies the directory containing the native libraries, and the `-f` argument specifies a glob pattern to find all the symbol files. The `-m` flag enables multi-threading for faster uploads.

Please refer to our [documentation](https://docs.bugsplat.com/education/faq/how-to-upload-symbol-files-with-symbol-upload) to learn more about how to use `symbol-upload`.

### Native Library Deployment

When integrating BugSplat into your Android application, it's crucial to ensure that the native libraries (.so files) are properly deployed to the device. Here are the necessary configurations to include in your app's build.gradle file:

1. **Match ABI Filters**
   
   Ensure your app uses the same ABI filters as the BugSplat library:
   ```gradle
   android {
       defaultConfig {
           ndk {
               abiFilters 'arm64-v8a', 'x86_64', 'armeabi-v7a'
           }
       }
   }
   ```

2. **Prevent Symbol Stripping**
   
   Configure packaging options to prevent stripping debug symbols from native libraries:
   ```gradle
   android {
       packagingOptions {
           jniLibs {
               keepDebugSymbols += ['**/*.so']
               // Use uncompressed shared libraries with 16KB zip alignment (AGP 8.5.1+)
               // Required for Android 15+ devices with 16KB page sizes
               useLegacyPackaging false
           }
           doNotStrip '**/*.so'
       }
   }
   ```
   
   > **Note:** Starting November 1st, 2025, all new apps and updates submitted to Google Play targeting Android 15+ must support [16 KB page sizes](https://developer.android.com/guide/practices/page-sizes). The BugSplat SDK is built with 16KB ELF alignment. Using `useLegacyPackaging false` with AGP 8.5.1+ ensures proper 16KB zip alignment for uncompressed shared libraries.

3. **Enable Native Library Extraction**
   
   Add the following to your AndroidManifest.xml to ensure native libraries are extracted:
   ```xml
   <application
       android:extractNativeLibs="true"
       ... >
   ```

4. **Enable JNI Debugging (for development)**
   
   For development and testing, enable JNI debugging in your debug build type:
   ```gradle
   android {
       buildTypes {
           debug {
               jniDebuggable true
           }
       }
   }
   ```

5. **Add Storage Permissions (if needed)**
   
   If your app needs to save crash dumps to external storage:
   ```xml
   <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
   <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
   ```

These configurations ensure that the BugSplat native libraries are properly included in your app and can function correctly to capture and report native crashes.

## User Feedback 💬

BugSplat supports collecting non-crashing user feedback such as bug reports and feature requests. Feedback reports appear in BugSplat alongside crash reports with the "User Feedback" type.

### Posting Feedback

Use `BugSplat.postFeedback` to submit feedback asynchronously, or `BugSplat.postFeedbackBlocking` for synchronous submission:

```java
// Async (returns immediately, runs on background thread)
BugSplat.postFeedback(
    "fred",                    // database
    "my-android-crasher",      // application
    "1.0.0",                   // version
    "Login button broken",     // title (required)
    "Nothing happens on tap",  // description
    "Jane",                    // user
    "jane@example.com",        // email
    null                       // appKey
);

// Blocking (returns true on success)
boolean success = BugSplat.postFeedbackBlocking(
    "fred", "my-android-crasher", "1.0.0",
    "Login button broken", "Nothing happens on tap",
    "Jane", "jane@example.com", null
);
```

### File Attachments

Pass a list of `File` objects to attach files to the feedback report:

```java
List<File> attachments = new ArrayList<>();
attachments.add(new File(getFilesDir(), "screenshot.png"));
attachments.add(new File(getFilesDir(), "app.log"));

BugSplat.postFeedback(
    "fred", "my-android-crasher", "1.0.0",
    "Login button broken", "Nothing happens on tap",
    "Jane", "jane@example.com", null,
    attachments
);
```

### Example Feedback Dialog

The example app includes a simple feedback dialog using Android's `AlertDialog`. See [`MainActivity.java`](example/src/main/java/com/bugsplat/example/MainActivity.java) for the implementation. The dialog collects a subject and optional description, then posts feedback using `BugSplat.postFeedbackBlocking` on a background thread.

## Sample Applications 🧑‍🏫

### Example App

The repository includes an example app that demonstrates how to use the BugSplat Android SDK. The example app is located in the `example` directory.

To run the example app:

1. Open the project in Android Studio
2. Select "Example App" from the run configuration dropdown in the toolbar
3. Click the run button to build and run the app on your device or emulator

The example app demonstrates:
- Automatically initializing the BugSplat SDK at app startup
- Triggering a crash for testing purposes
- Submitting user feedback via a dialog
- Handling errors during initialization

For more information, see the [Example App README](example/README.md).

## Building Native Dependencies 🔨

The BugSplat Android SDK includes prebuilt native libraries, but you can also build them from source with 16KB page size support.

### Prerequisites

- **Android NDK 28.2.13676358** or higher (recommended for 16KB page size support)
- **depot_tools** (for building Crashpad) - [Installation guide](https://commondatastorage.googleapis.com/chrome-infra-docs/flat/depot_tools/docs/html/depot_tools_tutorial.html)
- **CMake** and **Ninja**

### NDK 28+ Setup (Required for Crashpad)

NDK 28 removed the legacy standalone toolchain binaries that Crashpad's build system expects (e.g., `aarch64-linux-android-ar`). You need to create symlinks to the LLVM equivalents:

```bash
cd $ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin  # or linux-x86_64 on Linux

# Create symlinks for aarch64 (arm64-v8a)
ln -sf llvm-ar aarch64-linux-android-ar
ln -sf llvm-nm aarch64-linux-android-nm
ln -sf llvm-strip aarch64-linux-android-strip
ln -sf llvm-objcopy aarch64-linux-android-objcopy
ln -sf llvm-ranlib aarch64-linux-android-ranlib
ln -sf llvm-readelf aarch64-linux-android-readelf

# Create symlinks for arm (armeabi-v7a)
ln -sf llvm-ar arm-linux-androideabi-ar
ln -sf llvm-nm arm-linux-androideabi-nm
ln -sf llvm-strip arm-linux-androideabi-strip
ln -sf llvm-objcopy arm-linux-androideabi-objcopy
ln -sf llvm-ranlib arm-linux-androideabi-ranlib
ln -sf llvm-readelf arm-linux-androideabi-readelf

# Create symlinks for x86_64
ln -sf llvm-ar x86_64-linux-android-ar
ln -sf llvm-nm x86_64-linux-android-nm
ln -sf llvm-strip x86_64-linux-android-strip
ln -sf llvm-objcopy x86_64-linux-android-objcopy
ln -sf llvm-ranlib x86_64-linux-android-ranlib
ln -sf llvm-readelf x86_64-linux-android-readelf
```

### Building from Source

1. **Clone with submodules:**
   ```bash
   git clone --recurse-submodules https://github.com/BugSplat-Git/bugsplat-android
   cd bugsplat-android
   ```

2. **Set environment variables (optional):**
   ```bash
   # If your NDK is in a non-standard location
   export ANDROID_NDK="/path/to/your/ndk"
   
   # If depot_tools is in a non-standard location
   export DEPOT_TOOLS="/path/to/depot_tools"
   ```

3. **Run the build script:**
   ```bash
   ./scripts/build-all.sh
   ```

   Or build components individually:
   ```bash
   ./scripts/build-libcurl.sh   # Build libcurl with BoringSSL
   ./scripts/build-crashpad.sh  # Build Crashpad
   ```

### What Gets Built

The build scripts compile the following with 16KB page size alignment:

- **libcurl** - HTTP client library (with BoringSSL for TLS)
- **Crashpad** - Crash reporting framework
  - `libcrashpad_handler.so` - Crash handler process
  - `libclient.a` - Client library for crash capture
  - `libcommon.a` - Common utilities
  - `libutil.a` - Utility functions
  - `libbase.a` - Base library (from mini_chromium)

All libraries are built for:
- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`

### 16KB Page Size Configuration

The build uses these flags for 16KB page size compatibility:

```gn
# In crashpad args.gn
extra_ldflags = "-static-libstdc++ -Wl,-z,max-page-size=16384"
```

```cmake
# In libcurl CMake
-DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
```

## Building the Release AAR 📦

To build the BugSplat Android SDK as a release AAR file:

1. **Navigate to the project directory:**
   ```bash
   cd bugsplat-android
   ```

2. **Build the release AAR:**
   ```bash
   ./gradlew app:assembleRelease
   ```

   Or to build only the AAR bundle:
   ```bash
   ./gradlew app:bundleReleaseAar
   ```

3. **Find the output:**
   
   The AAR file will be located at:
   ```
   app/build/outputs/aar/bugsplat-android-release.aar
   ```

### Build Variants

You can also build the debug variant:
```bash
./gradlew app:assembleDebug
# Output: app/build/outputs/aar/bugsplat-android-debug.aar
```

Or build all variants at once:
```bash
./gradlew app:assemble
```

## Contributing 🤝

BugSplat is an open-source project, and we welcome contributions from the community. Please create an issue or open a pull request if you have a suggestion or need additional help.
