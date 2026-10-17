# Android App Module Contract

## 1. Scope & Canonical Owners

- **Role**: Android 클라이언트 모듈로, Jetpack Compose UI, Room 2.6.1 기반 로컬 영속성, SAF(Storage Access Framework) 지속적 권한 관리, WorkManager 백그라운드 동기화, Coil 이미지 로더 통합을 소유합니다.
- **Canonical Flow**: `MainActivity -> Compose Navigation / Screen ViewModels -> AndroidToonRepository -> Room AppDatabase / AndroidSyncManager -> Core Models & Sources`.
- **Key Entry Points**:
  - Application & Main Activity: `app/src/main/kotlin/com/comics8/monitor/ComicsApp.kt` & `app/src/main/kotlin/com/comics8/monitor/MainActivity.kt`
  - Persistence & DAOs: `app/src/main/kotlin/com/comics8/monitor/data/db/AppDatabase.kt` & `app/src/main/kotlin/com/comics8/monitor/data/db/dao/`
  - SAF Storage Mirroring: `app/src/main/kotlin/com/comics8/monitor/data/local/SafLibraryMirror.kt`
  - Background Worker & In-App Update: `app/src/main/kotlin/com/comics8/monitor/data/sync/` & `app/src/main/kotlin/com/comics8/monitor/data/update/AndroidUpdateManager.kt`

## 2. Core Invariants

### 1. Persistence & Room Schema Evolution
- **KSP-Compiled Room Database**: `AppDatabase`는 Room 2.6.1 및 KSP(`kspAndroid`)로 컴파일되며, 엔티티/DAO 스키마 변경 시 명시적 버전 업그레이드와 마이그레이션 경로를 작성해야 합니다.
- **User Data Integrity**: 스키마 마이그레이션 시 기존 사용자의 북마크, 열람 이력(`history`), 즐겨찾기(`favorites`) 데이터가 유실되지 않도록 엄격히 보호합니다 (`fallbackToDestructiveMigration()` 금지).

### 2. Scoped Storage & SAF Permission Persistence
- **Persistent URI Grants**: Android 11+ 스코프드 스토리지 환경에서 로컬 만화 폴더 접근 시 `takePersistableUriPermission`을 즉시 획득합니다.
- **Persistable Document Tree**: `SafLibraryMirror`는 앱 재구동 시 사용자에게 권한을 재요청하지 않고 기존 부여된 URI 권한을 유지·검증하여 로컬 라이브러리를 복원합니다.

### 3. Background Sync & Physical Device Delivery
- **WorkManager Invariant**: 정기 만화 신간 알림 및 동기화는 AndroidX WorkManager의 제약조건(네트워크 연결 상태, 배터리 절약 모드)을 준수하며 실행됩니다.
- **No Emulator & Direct APK Distribution**: CI/CD 및 자동화에서 에뮬레이터를 구동하지 않으며, 단위 테스트는 `:app:testDebugUnitTest`로 검증합니다. 실기기는 `AndroidUpdateManager`의 자체 인앱 업데이트 다이얼로그를 통해 신규 Release APK를 다운로드받아 설치합니다.

## 3. Cross-Boundary Contracts

- **Core Module Contracts**: 도메인 모델, 이미지 메모리 파티션(Coil 바인딩), 와이어 코덱은 [modules/core_module.md](core_module.md)를 따릅니다.
- **Cloud Sync Wire**: AndroidSyncManager 및 Room 동기화 플래그는 [domains/sync_protocol_wire.md](../domains/sync_protocol_wire.md)를 따릅니다.
- **Local CBZ / Storage**: SAF URI 기반 파일 열거 및 압축 해제는 [domains/local_network_library.md](../domains/local_network_library.md)를 따릅니다.
- **Release Packaging**: Release APK 및 Play Store AAB 원격 빌드는 [modules/release_pipeline.md](release_pipeline.md)를 따릅니다.
