# Android App Module Contract

## Core Boundaries & Invariants

- **Application Structure**:
  - Package: `com.comics8.monitor`
  - Application class: `com.comics8.monitor.ComicsApp`
  - Main Activity: `com.comics8.monitor.MainActivity`
  - UI Stack: Jetpack Compose + Material3 + Coil Image Loader.
- **Persistence (Room 2.6.1)**:
  - Database: `AppDatabase` compiled via KSP (`kspAndroid`).
  - Schema changes require careful version increment and migration strategy.
  - Entity changes must not silently break existing user reading history or favorited items.
- **Storage Access Framework (SAF) Invariant**:
  - Android 11+ scoped storage requires persistent URI permission grants (`takePersistableUriPermission`).
  - `SafLibraryMirror` & `PersistableOpenDocumentTree`: Retain and validate folder permissions across app restarts without re-prompting users.
- **Background Tasks & Updates**:
  - `AndroidUpdateManager` / `WorkManager`: Background periodic checks for comic updates and app self-updates.
  - No Android emulator execution in standard CI/CD; test verification via `:app:testDebugUnitTest`. Physical devices update via in-app APK installer.
