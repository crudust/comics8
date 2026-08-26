# Desktop App Module Contract

## Core Boundaries & Invariants

- **Runtime & UI Stack**:
  - Compose Multiplatform for Desktop (JVM 17).
  - Main entry: `src/main/kotlin/Main.kt`.
  - Window management: Custom titlebar, stateful window size/position persistence, and keyboard shortcut dispatchers.
- **Local Persistence (SQLite JDBC)**:
  - Database: `DesktopDatabase` using SQLite JDBC connection.
  - Implements `SyncStorageAdapter` for desktop reading progress, favorites, and history sync.
- **Desktop-Specific Capabilities**:
  - `DesktopFolderPicker`: Native system directory chooser for local comic libraries.
  - `DesktopImageLoader`: Image decoding and memory caching optimized for desktop displays and multi-monitor setups.
  - `DesktopUpdateManager`: Background check for latest macOS `.app` / Windows zip and in-app download prompt.
- **Verification Gate**:
  - Test suite runs via `./gradlew :desktop:test`.
  - Development run via `./scripts/run-desktop.sh` or `./gradlew :desktop:run`.
