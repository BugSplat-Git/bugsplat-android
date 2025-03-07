# BugSplat Example App

This is an example app that demonstrates how to use the BugSplat Android SDK.

## Running the Example App

There are two ways to run the example app:

### Option 1: Using the Run Configuration

1. Open the project in Android Studio
2. Select "Example App" from the run configuration dropdown in the toolbar
3. Click the run button (green triangle) to build and run the app on your device or emulator

### Option 2: Using Gradle

You can also build and install the example app using Gradle:

```bash
# Build the debug APK
./gradlew :example:assembleDebug

# Install the debug APK on a connected device
./gradlew :example:installDebug

# Build and install in one step
./gradlew :example:installDebug
```

## Using the Example App

The example app automatically initializes BugSplat when it starts. You can:

1. Press the "Crash App" button to trigger a crash and test the crash reporting functionality

## Configuration

The example app uses a single source of truth for BugSplat configuration values (database, application name, and version). These values are defined in the `example/build.gradle` file and are automatically shared with the app code through BuildConfig fields.

To update the BugSplat configuration, edit the following section in the `example/build.gradle` file:

```gradle
// BugSplat configuration - Define at the top level so it can be used throughout the build file
ext {
    bugsplatDatabase = "fred" // Replace with your BugSplat database name
    bugsplatAppName = "my-android-crasher" // Replace with your application name
    bugsplatAppVersion = "1.0.0" // Can be overridden by versionName below
    // Optional: Add your BugSplat API credentials for symbol upload
    bugsplatClientId = ""     // Replace with your BugSplat API client ID (optional)
    bugsplatClientSecret = "" // Replace with your BugSplat API client secret (optional)
}
```

### Important: API Credentials for Symbol Upload

Symbol upload requires authentication with BugSplat API credentials. You must provide both `bugsplatClientId` and `bugsplatClientSecret` in your build.gradle file:

```gradle
ext {
    // ... other configuration
    bugsplatClientId = "your_client_id"         // Required for symbol upload
    bugsplatClientSecret = "your_client_secret" // Required for symbol upload
}
```

You can obtain these credentials from your BugSplat account. Without these credentials, the symbol upload task will exit early with a warning message.

### Important: Enable BuildConfig Generation

Make sure to enable BuildConfig generation in your build.gradle file:

```gradle
android {
    // Enable BuildConfig generation
    buildFeatures {
        buildConfig true
    }
    
    // ... other configurations
}
```

Without this configuration, the BuildConfig fields will not be generated, and the app will fail to compile.

These values are then:
1. Used by the MainActivity to initialize BugSplat
2. Used by the symbol upload task to upload debug symbols
3. Automatically kept in sync with the app's version

## Debugging

To debug the example app and the library code:

1. Set breakpoints in the library code
2. Select the "Example App" run configuration
3. Click the debug button (bug icon) instead of the run button
4. The debugger will stop at your breakpoints, allowing you to step through the code

## Uploading Debug Symbols

The example app includes functionality to automatically upload debug symbols for native libraries (.so files) to the BugSplat service. This is crucial for proper symbolication of native crashes.

### Automatic Symbol Upload

The example app is configured to automatically upload debug symbols when you build the app:

```bash
# For debug build
./gradlew :example:assembleDebug

# For release build
./gradlew :example:assembleRelease
```

This will:
1. Build the app with debug symbols
2. Check if BugSplat API credentials are provided (exits early if missing)
3. Upload debug symbols for each supported ABI (arm64-v8a, x86_64, armeabi-v7a) for the current build type

For each ABI, the process will:
1. Find the .so files for that specific ABI and build type
2. Download the platform-specific symbol-upload executable if it doesn't exist
3. Upload the debug symbols to your BugSplat database

The symbol-upload executable will be downloaded automatically based on your operating system:
- Windows: symbol-upload-windows.exe
- macOS: symbol-upload-macos
- Linux: symbol-upload-linux

### Manual Symbol Upload

You can upload debug symbols manually for all ABIs of a specific build type:

```bash
# For debug build
./gradlew :example:uploadBugSplatSymbolsDebugAllAbis

# For release build
./gradlew :example:uploadBugSplatSymbolsReleaseAllAbis
```

Or for a specific build type and ABI:

```bash
# For debug build
./gradlew :example:uploadBugSplatSymbolsDebugArm64-v8a
./gradlew :example:uploadBugSplatSymbolsDebugX86_64
./gradlew :example:uploadBugSplatSymbolsDebugArmeabi-v7a

# For release build
./gradlew :example:uploadBugSplatSymbolsReleaseArm64-v8a
./gradlew :example:uploadBugSplatSymbolsReleaseX86_64
./gradlew :example:uploadBugSplatSymbolsReleaseArmeabi-v7a
```

Each task will:
1. Check if API credentials are provided (exits early if missing)
2. Find the native libraries directory for the specific build type and ABI
3. Download the symbol-upload executable if needed
4. Upload symbols to your BugSplat database

### Using the Symbol Upload API in Your Own App

If you want to upload symbols programmatically in your own app, you can use the BugSplat API:

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

## Troubleshooting

If you encounter any issues running the example app:

1. Make sure you have the latest Android Studio version
2. Try cleaning and rebuilding the project: `./gradlew clean build`
3. Check that your device or emulator meets the minimum SDK requirements (API 26+)
4. Verify that the native library is being built correctly by checking the build output 

## Native Library Configuration

The example app includes specific configurations to ensure that the BugSplat native libraries (.so files) are properly deployed to the device. These configurations are crucial for the crash reporting functionality to work correctly.

### Build.gradle Configuration

The example app's build.gradle includes the following configurations:

```gradle
android {
    defaultConfig {
        // Match the ABI filters from the library
        ndk {
            abiFilters 'arm64-v8a', 'x86_64', 'armeabi-v7a'
        }
    }

    buildTypes {
        debug {
            minifyEnabled false
            debuggable true
            jniDebuggable true  // Enable JNI debugging
        }
    }

    // Ensure native libraries are not stripped
    packagingOptions {
        jniLibs {
            keepDebugSymbols += ['**/*.so']
            useLegacyPackaging = true
        }
        doNotStrip '**/*.so'
    }
}
```

### AndroidManifest.xml Configuration

The AndroidManifest.xml includes:

```xml
<application
    android:extractNativeLibs="true"
    ... >
```

This ensures that native libraries are extracted to the device's file system rather than being loaded directly from the APK, which is necessary for the crash handler to function properly.

### Debugging Native Libraries

If you're experiencing issues with native libraries not being loaded:

1. Check the logcat output for messages from "BugSplatExample" tag
2. Verify that the native libraries are included in the APK by examining the APK contents:
   ```bash
   unzip -l app-debug.apk | grep .so
   ```
3. Ensure that the library's native code is being properly linked with your application 

### Symbol Upload Troubleshooting

If you're having issues with symbol upload:

1. Make sure you've provided valid BugSplat API credentials (clientId and clientSecret)
2. Check the Gradle output for any errors during the download of the symbol-upload executable
3. Verify that the platform-specific symbol-upload executable has been downloaded to your project root directory
4. Make sure the executable has the proper permissions (should be automatically set)
5. Check that you have the correct database name, application name, and version
6. Verify that the native libraries directory exists and contains .so files for each ABI
7. Look for error messages in the Gradle build output
8. Try running the upload task manually for a specific build type and ABI (e.g., `./gradlew :example:uploadBugSplatSymbolsDebugArm64-v8a --info`) for more detailed logs 