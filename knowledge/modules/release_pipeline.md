# Release Pipeline Module Contract

## Core Boundaries & Invariants

- **Single Release Authority (Linux Server)**:
  - All official releases MUST be initiated from the Linux development server using `./scripts/deploy-release.sh [versionName] [versionCode] [releaseNotes]`.
  - Never run standalone release tags directly from local dev clones without going through the canonical deployment script.
- **Mac Builder Topology**:
  - Mac packaging is performed remotely over SSH to the builder Mac via `./scripts/update-mac-app-remote.sh`.
  - The builder Mac pulls `origin/main` into the dedicated builder clone (`Documents/Mac/Comics8`).
  - **Never touch the active coding clone (`Documents/Comics8`)** during automated builds.
  - The resulting `.app` is overwritten into `/Applications/Comics8.app` on the builder Mac, zipped, and uploaded to the server for distribution.
  - Running apps receive in-app updates; the script does not launch or restart the running app process.
- **Windows Portable Zip**:
  - Windows release is packaged as a standalone portable zip via `./gradlew :desktop:packageWindowsZip`.
  - Output artifact: `desktop/build/compose/binaries/main/Comics8-win.zip`.
- **Android Release APK**:
  - Signed release APK is built via `./gradlew assembleRelease`.
  - Output artifact: `app/build/outputs/apk/release/app-release.apk`.
  - Distributed as `comics8-latest.apk` on GitHub Releases.
- **Homebrew Cask Formula**:
  - Formula generated at `build/homebrew/comics8.rb` with auto-calculated SHA-256 hash of the macOS zip bundle.
- **Open-Source Repository Sync (`scripts/sync-public-repo.sh`)**:
  - Target: `crudust/comics8` public GitHub repository.
  - Excluded from public sync: Internal plugins, private test fixtures (`hitomi`, `eleven`, `rawkuma`), proprietary server backend, and internal `knowledge/` documentation.
  - Only clean core, desktop, examples (`peppercarrot.js`), and open docs are mirrored.
