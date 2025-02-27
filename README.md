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

The bugsplat-android library enables posting crash reports to BugSplat from Android devices. Visit [bugsplat.com](https://www.bugsplat.com) for more information and to sign up for an account.

## Integration 🏗️

BugSplat supports multiple methods for installing the bugsplat-android library in a project.

### Package Manager

TODO BG

### Manual Setup

TODO BG

## Usage 🧑‍💻

### Configuration

TODO BG

### Symbol Upload

To symbolicate crash reports, you must upload your app's `.so` files to the BugSplat backend. There are scripts to help with this.

Download BugSplat's cross-platform tool, [symbol-upload-macos](https://docs.bugsplat.com/education/faq/how-to-upload-symbol-files-with-symbol-upload) for Apple Silicon by entering the following command in your terminal.

```sh
curl -sL -O "https://app.bugsplat.com/download/symbol-upload-macos"
```

Alternatively, you can download the Intel version via the following command.

```sh
curl -sL -O "https://app.bugsplat.com/download/symbol-upload-macos-intel"
```

Make `symbol-upload-macos` executable

```sh
chmod +x symbol-upload-macos
```

TODO BG

Please refer to our [documentation](https://docs.bugsplat.com/education/faq/how-to-upload-symbol-files-with-symbol-upload) to learn more about how to use `symbol-upload-macos`.

### Initialization

TODO BG

### Attributes

TODO BG

### Attachments

TODO BG

## Sample Applications 🧑‍🏫

TODO BG

## Contributing 🤝

BugSplat is an open-source project, and we welcome contributions from the community. To configure a development environment, follow the instructions below.

TODO BG

### Releasing

TODO BG
