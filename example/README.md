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

Note: You may need to update the database name in the `MainActivity.java` file to match your actual BugSplat database name:

```java
BugSplat.init(this, "YourDatabase", "BugSplatExample", "1.0.0");
```

## Debugging

To debug the example app and the library code:

1. Set breakpoints in the library code
2. Select the "Example App" run configuration
3. Click the debug button (bug icon) instead of the run button
4. The debugger will stop at your breakpoints, allowing you to step through the code

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