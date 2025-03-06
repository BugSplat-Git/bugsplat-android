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

To symbolicate crash reports, you must upload your app's `.so` files to the BugSplat backend. There are scripts to help with this.

Download BugSplat's cross-platform tool, [symbol-upload](https://docs.bugsplat.com/education/faq/how-to-upload-symbol-files-with-symbol-upload) by entering the following command in your terminal.

macOS
```sh
curl -sL -O "https://octomore.bugsplat.com/download/symbol-upload-macos" && chmod +x symbol-upload-macos
```

Windows
```ps1
Invoke-WebRequest -Uri "https://app.bugsplat.com/download/symbol-upload-windows.exe" -OutFile "symbol-upload-windows.exe"
```

Linux
```sh
curl -sL -O  "https://app.bugsplat.com/download/symbol-upload-linux" && chmod +x symbol-upload-linux
```

Please refer to our [documentation](https://docs.bugsplat.com/education/faq/how-to-upload-symbol-files-with-symbol-upload) to learn more about how to use `symbol-upload`.

## Sample Applications 🧑‍🏫

Coming soon!

## Contributing 🤝

BugSplat is an open-source project, and we welcome contributions from the community. Please create an issue or open a pull request if you have a suggestion or need additional help.
