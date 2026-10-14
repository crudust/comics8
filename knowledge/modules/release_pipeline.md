# Release Pipeline Module Contract

## Core Boundaries & Invariants

- **Release Pipeline Architecture (Mac Orchestration & Linux Builder Offload)**:
  - Official releases are orchestrated from the Mac workstation via `./scripts/deploy-release.sh [versionName] [versionCode] [releaseNotes]`.
  - Heavy Android release builds (`assembleStandaloneRelease bundlePlayRelease`) are offloaded to the Linux build server (`crudust@192.168.0.154`) via SSH, keeping the Mac workstation completely unburdened.
  - Resulting signed APK and AAB binaries are immediately fetched back to the Mac workstation's local `dist/` directory via `scp`.
  - Artifacts can be retrieved without rebuilding at any time using `./scripts/deploy-release.sh --fetch`.
  - Release notes MUST be written in English for multi-language client compatibility. If omitted, the deployment script automatically derives English release notes from Git commit history since the previous release tag (falling back to "Bug fixes and performance improvements").
- **macOS CI Builder Topology (GitHub Actions)**:
  - macOS packaging is performed autonomously in cloud CI via `.github/workflows/release.yml` on `macos-latest`.
  - When release tags (`v*`) are pushed to GitHub, GitHub Actions compiles the macOS distributable (`./gradlew :desktop:createDistributable`), packages `release-artifacts/Comics8-mac.zip` preserving symlinks and POSIX attributes, and uploads directly to GitHub Releases.
  - Zero local SSH builder dependencies; running Mac apps receive in-app updates directly from the GitHub Release CDN.
- **Windows Portable Zip**:
  - Windows release is packaged as a standalone portable zip via `./gradlew :desktop:packageWindowsZip`.
  - Output artifact: `desktop/build/compose/binaries/main/Comics8-win.zip`.
- **Android Release APK & AAB**:
  - Signed standalone APK is built via `./gradlew assembleStandaloneRelease`; Play AAB uses `./gradlew bundlePlayRelease`.
  - Artifacts are collected in `dist/comics8-v{version}.apk` and `dist/comics8-v{version}.aab`. Older builds in `dist/` are automatically pruned so that only the latest version is retained.
  - Signing credentials and the keystore MUST remain outside the repository and are supplied through `COMICS8_SIGNING_*` environment variables (or the equivalent private Gradle properties).
  - Standalone APK is distributed as `comics8-latest.apk` on GitHub Releases. Play Store AAB is preserved locally in `dist/` only.
- **Open-Source Repository Sync (`scripts/sync-public-repo.sh`)**:
  - Target: `crudust/comics8` public GitHub repository.
  - Excluded from public sync: Internal plugins, private test fixtures (`hitomi`, `eleven`, `rawkuma`), proprietary server backend, and internal `knowledge/` documentation.
  - Only clean core, desktop, examples (`peppercarrot.js`), and open docs are mirrored.
