# Comics8

<p align="center">
  <strong>Modern, lightweight, foldable-optimized cross-platform comic & webtoon reader.</strong><br>
  <em>Featuring dual-spread viewing, network storage (SMB/WebDAV/ZIP), cloud sync, and JavaScript plugin architecture.</em>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License: MIT">
  <img src="https://img.shields.io/badge/Platform-macOS%20%7C%20Windows%20%7C%20Android-green.svg" alt="Platforms">
  <img src="https://img.shields.io/badge/Kotlin-JVM%2017-purple.svg" alt="Kotlin JVM 17">
  <img src="https://img.shields.io/badge/UI-Compose%20Multiplatform-orange.svg" alt="Compose Multiplatform">
</p>

---

## ✨ Features

### 📱 Foldable & Large-Screen Optimized Reader
- **Advanced Dual-Spread View**: Seamless two-page spread viewing designed specifically for foldable devices (e.g. Galaxy Z Fold) and wide landscape screens on tablets & desktops.
- **Smart Wide-Page / Panorama Detection**: Automatically detects merged two-page spreads and presents them in full-width single views, while grouping standard portrait pages in pairs.
- **Versatile Reading Modes**:
  - Right-to-Left (Japanese Manga), Left-to-Right (Western Comics / Books), and Continuous Vertical Scroll (Webtoons).
  - Multiple scaling and cropping modes: Fit to Screen, Original Aspect Ratio, Split Mode, and more.
- **Zero-Flicker & High-Performance Rendering**: Fast image pre-decoding and Skiko hardware acceleration ensure smooth, flicker-free page transitions.

### 🌐 Local & Network Library (SMB / WebDAV / ZIP)
- **Network Storage Integration**: Connect directly to **SMB** and **WebDAV** shares on your NAS or private file servers to stream comics without downloading.
- **Direct Archive Reading (ZIP / CBZ)**: Open and read images inside compressed archives instantly without extraction.
- **Local Directory Binding**: Mount local folders on your device into unified comic libraries.

### 🔄 Real-Time Cloud Sync & Convenience
- **Cross-Platform Progress Sync**: Real-time automatic synchronization of reading progress, current page, read status, and favorites across Android, macOS, and Windows.
- **Effortless Device Pairing**: Instantly link devices using simple 6-digit one-time pairing codes or master recovery keys without mandatory account sign-ups.
- **Reading History & Resumption**: Visual progress indicators, quick resumption of recently read chapters, and persistent history tracking.
- **Dedicated Network & Proxy Controls**: Direct connection, server routing proxy, and custom HTTP / SOCKS5 proxy configurations with built-in latency testing.

### 🔌 Extensible JavaScript Plugin Engine
- **Infinite Source Expansion**: Easily add new online comic/webtoon sources using standard JavaScript (`.js`) plugins.
- **Catalog & Live Search**: Built-in support for multi-language catalogs, keyword suggestions, live search, and automatic updates.

---

## 📥 Installation & Downloads

### macOS (Recommended: Homebrew)
Install effortlessly via Homebrew Tap (automatically handles Gatekeeper quarantine):
```bash
brew install crudust/tap/comics8
```

Or download `Comics8-mac.zip` from [GitHub Releases](https://github.com/crudust/comics8/releases/latest), extract it, and drag `Comics8.app` to `/Applications`.

### Windows
Download `Comics8-win.zip` from [GitHub Releases](https://github.com/crudust/comics8/releases/latest), extract anywhere, and run `Comics8.exe` (portable, no installation required).

### Android
Download `comics8-latest.apk` directly from [GitHub Releases](https://github.com/crudust/comics8/releases/latest) on your mobile device.

---

## 🛠️ Plugin Development

Comics8 supports custom source plugins written in JavaScript.

- **Developer Guide**: [Plugin Development Documentation](docs/PLUGIN_GUIDE.md)
- **Reference Example**: [Pepper & Carrot Plugin](examples/sources/peppercarrot.js)

To import a plugin into Comics8 Desktop:
1. Navigate to **Sources**.
2. Click **Add Source** -> **Import JavaScript File (`.js`)**.
3. Select your `.js` plugin file to start reading.

---

## 🏗️ Building from Source

### Prerequisites
- JDK 17 (e.g. OpenJDK 17)
- Android SDK (minSdk 26, targetSdk 35)

### Run Desktop App locally (macOS / Windows / Linux)
```bash
./gradlew :desktop:run
```

### Build Distribution Packages
- **Android Release APK**: `./gradlew assembleRelease`
- **Windows Portable Zip**: `./gradlew :desktop:packageWindowsZip`
- **macOS App Distribution**: `./gradlew :desktop:createDistributable`

---

## 📄 License

This project is licensed under the **MIT License**. See the [LICENSE](LICENSE) file for details.
