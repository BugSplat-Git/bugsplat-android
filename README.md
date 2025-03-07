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
            "-m"  // Enable multi-threading
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
./symbol-upload -b DATABASE -a APPLICATION -v VERSION -i CLIENT_ID -s CLIENT_SECRET -d NATIVE_LIBS_DIR -f "**/*.so" -m
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
               useLegacyPackaging = true
           }
           doNotStrip '**/*.so'
       }
   }
   ```

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
- Handling errors during initialization

For more information, see the [Example App README](example/README.md).

## Contributing 🤝

BugSplat is an open-source project, and we welcome contributions from the community. Please create an issue or open a pull request if you have a suggestion or need additional help.
