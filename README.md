# Comics8

<p align="center">
  <strong>Modern, lightweight, cross-platform comic & webtoon reader with custom plugin support.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/License-GPL%20v3-blue.svg" alt="License: GPL v3">
  <img src="https://img.shields.io/badge/Platform-macOS%20%7C%20Windows%20%7C%20Android-green.svg" alt="Platforms">
  <img src="https://img.shields.io/badge/Kotlin-JVM%2017-purple.svg" alt="Kotlin JVM 17">
  <img src="https://img.shields.io/badge/UI-Compose%20Multiplatform-orange.svg" alt="Compose Multiplatform">
</p>

---

## ✨ Features

- **📖 Advanced Dual-Spread Reader**:
  - Seamless two-page spread viewing with automatic panoramic wide-page detection.
  - Right-to-Left (Japanese Manga) and Left-to-Right (Western Comic / Webtoon) reading direction support.
  - Continuous vertical scroll mode with infinite preloading.
- **🔌 Extensible JavaScript Plugin Architecture**:
  - Add your own custom sources using standard JavaScript plugins.
  - Multi-language catalog support, live search, and high-resolution image fallback resolution.
- **⚡ High Performance & Zero-Flicker Rendering**:
  - Deterministic layout pipeline with fast header probing and asynchronous pre-decoding.
  - Native Compose Multiplatform UI hardware-accelerated with Skiko.
- **🔄 Universal History & Progress Sync**:
  - SQLite database sync across devices.
- **📦 Completely Portable**:
  - No installer needed for Windows. Clean Homebrew cask for macOS.

---

## 📥 Installation & Downloads

### macOS (Recommended: Homebrew)
Install effortlessly via Homebrew Tap (bypasses Gatekeeper quarantine automatically):
```bash
brew install crudust/tap/comics8
```

Or download `Comics8-mac.zip` from [GitHub Releases](https://github.com/crudust/comics8/releases/latest), unzip, and drag `Comics8.app` to `/Applications`.

### Windows
Download `Comics8-win.zip` from [GitHub Releases](https://github.com/crudust/comics8/releases/latest), extract anywhere, and run `Comics8.exe`. (No installation required).

### Android
Download `comics8-latest.apk` directly from [GitHub Releases](https://github.com/crudust/comics8/releases/latest) on your mobile device.

---

## 🛠️ Plugin Development

Comics8 supports custom source plugins written in JavaScript.

- **Developer Guide**: [Plugin Development Documentation](docs/PLUGIN_GUIDE.md)
- **Reference Example**: [Pepper & Carrot Plugin](examples/sources/peppercarrot.js)

To import a plugin into Comics8 Desktop:
1. Go to **Sources** tab.
2. Click **Add Source** -> **Import JavaScript File (`.js`)**.
3. Select your `.js` file to start reading.

---

## 🏗️ Building from Source

### Prerequisites
- JDK 17 (e.g. OpenJDK 17)
- Kotlin / Gradle

### Run Desktop App locally (macOS / Windows / Linux)
```bash
./gradlew :desktop:run
```

### Build Distribution Packages
- **Windows Portable Zip**: `./gradlew :desktop:packageWindowsZip`
- **macOS App Distribution**: `./gradlew :desktop:createDistributable`

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See the [LICENSE](LICENSE) file for details.
