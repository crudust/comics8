# Release Pipeline Module Contract

## Core Boundaries & Invariants

- **Single Release Authority (Linux Server)**:
  - All official releases MUST be initiated from the Linux development server using `./scripts/deploy-release.sh [versionName] [versionCode] [releaseNotes]`.
  - Never run standalone release tags directly from local dev clones without going through the canonical deployment script.
- **macOS CI Builder Topology (GitHub Actions)**:
  - macOS packaging is performed autonomously in cloud CI via `.github/workflows/release.yml` on `macos-latest`.
  - When release tags (`v*`) are pushed to GitHub, GitHub Actions compiles the macOS distributable (`./gradlew :desktop:createDistributable`), packages `release-artifacts/Comics8-mac.zip` preserving symlinks and POSIX attributes, and uploads directly to GitHub Releases.
  - Zero local SSH builder dependencies; running Mac apps receive in-app updates directly from the GitHub Release CDN.
- **Windows Portable Zip**:
  - Windows release is packaged as a standalone portable zip via `./gradlew :desktop:packageWindowsZip`.
  - Output artifact: `desktop/build/compose/binaries/main/Comics8-win.zip`.
- **Android Release APK**:
  - Signed standalone APK is built via `./gradlew assembleStandaloneRelease`; Play AAB uses `./gradlew bundlePlayRelease`.
  - Output artifacts: `app/build/outputs/apk/standalone/release/app-standalone-release.apk` and `app/build/outputs/bundle/playRelease/app-play-release.aab`.
  - Signing credentials and the keystore MUST remain outside the repository and are supplied through `COMICS8_SIGNING_*` environment variables (or the equivalent private Gradle properties).
  - Distributed as `comics8-latest.apk` on GitHub Releases.
- **Homebrew Cask Formula**:
  - Formula generated at `build/homebrew/comics8.rb` with auto-calculated SHA-256 hash of the macOS zip bundle.
- **Open-Source Repository Sync (`scripts/sync-public-repo.sh`)**:
  - Target: `crudust/comics8` public GitHub repository.
  - Excluded from public sync: Internal plugins, private test fixtures (`hitomi`, `eleven`, `rawkuma`), proprietary server backend, and internal `knowledge/` documentation.
  - Only clean core, desktop, examples (`peppercarrot.js`), and open docs are mirrored.
