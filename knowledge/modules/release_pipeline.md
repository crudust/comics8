# Release Pipeline Module Contract

## 1. Scope & Canonical Owners

- **Role**: 멀티플랫폼 배포 파이프라인으로, Mac 오케스트레이션 -> Linux 원격 Android 빌드 위임, GitHub Actions 클라우드 데스크톱 패키징(macOS .app / Windows portable zip), 배포 아티팩트 보관 정책, 오픈소스 공개 레포지토리 동기화를 소유합니다.
- **Canonical Flow**: `./scripts/deploy-release.sh [versionName] [versionCode] "[notes]" -> SSH to Linux Builder (APK/AAB) -> SCP to dist/ -> Git Tag Push -> GitHub Actions (Mac/Win Desktop) -> GitHub Releases Upload -> sync-public-repo.sh`.
- **Key Entry Points**:
  - Main Release Orchestrator: `scripts/deploy-release.sh`
  - Public Repo Mirroring: `scripts/sync-public-repo.sh`
  - Cloud CI Packaging: `.github/workflows/release.yml`
  - App Version & Signing Gradle: `app/build.gradle.kts` & `desktop/build.gradle.kts`

## 2. Core Invariants

### 1. Mac Orchestration & Linux Builder Offload
- **Zero Local Build Load**: 무거운 Android Release 빌드(`assembleStandaloneRelease bundlePlayRelease`)는 고성능 Linux 서버(`crudust@192.168.0.154`)에 SSH로 위임하여 실행하며 Mac 로컬에 빌드 부하를 주지 않습니다.
- **Immediate Retrieval & Fetch Mode**: 빌드된 정식 서명 APK/AAB는 Mac 로컬 `dist/`로 즉시 자동 수거(`scp`)되며, 재빌드 없이 산출물만 다시 가져오는 fetch 모드(`./scripts/deploy-release.sh --fetch`)를 지원합니다.
- **English Release Notes**: 릴리즈 노트는 다국어 클라이언트 호환성을 위해 영문 작성을 강제합니다. 인자 생략 시 이전 태그 이후의 Git 커밋 히스토리로부터 자동 생성됩니다.

### 2. Autonomous Cloud CI Desktop Packaging
- **GitHub Actions macOS Builder**: macOS 패키징은 GitHub Actions(`macos-latest`)에서 태그 푸시를 감지해 자율적으로 수행합니다 (`./gradlew :desktop:createDistributable`).
- **POSIX & Symlink Preservation**: 심볼릭 링크와 POSIX 권한 속성을 보존하여 `Comics8-mac.zip`으로 패키징 후 GitHub Releases에 직접 업로드합니다.
- **Windows Portable Zip**: Windows 바이너리는 `./gradlew :desktop:packageWindowsZip`을 통해 포터블 압축 파일(`Comics8-win.zip`)로 빌드되어 함께 배포됩니다.

### 3. Artifact Retention & Signing Secrets
- **Local Dist Single-Version Policy**: `dist/`에는 이전 버전을 누적하지 않고 최신 1개 버전만 유지합니다 (새 버전 수거 시 구버전 자동 삭제).
- **GitHub Releases 2-Version Policy**: GitHub Releases는 최신 2개 버전만 유지하여 릴리즈 스토리지 용량을 최적화합니다.
- **Keystore Isolation**: 서명 키스토어 및 자격증명은 레포지토리 외부에 위치하며 `COMICS8_SIGNING_*` 환경 변수를 통해서만 주입됩니다. Play Store AAB는 보안상 GitHub에 업로드하지 않고 로컬 `dist/`에만 보관합니다.

### 4. Confidentiality & Public Repo Sync (`sync-public-repo.sh`)
- **Strict Sync Filter**: 공개 레포지토리(`crudust/comics8`)로 동기화 시 비공개 플러그인, 내부 테스트 픽스처, 상용 백엔드(`server/`), 내부 지식 베이스(`knowledge/`)는 제외됩니다.
- **Clean Open Source Surface**: 공통 코어, 데스크톱, 오픈 플러그인 예제(`peppercarrot.js`), 공개 문서만 깨끗하게 미러링됩니다.

### 5. Release Execution Operations
- **Pre-Release Preconditions**:
  - Git 작업 트리가 깨끗해야 함.
  - 사전 검증 테스트 통과: `./gradlew :core:test` 및 `./gradlew :app:testDebugUnitTest`.
  - 버전 번호 확인: `app/build.gradle.kts`의 `versionName`, `versionCode`.
- **Standard Command**:
  ```bash
  ./scripts/deploy-release.sh [versionName] [versionCode] "[releaseNotes]"
  # 타깃별 옵션: --android, --desktop, --all (기본값)
  # 산출물만 재수거: ./scripts/deploy-release.sh --fetch
  ```

## 3. Cross-Boundary Contracts

- **Android App Artifacts**: Release APK 및 Play Store AAB 설정은 [modules/android_app.md](android_app.md)를 따릅니다.
- **Desktop App Artifacts**: macOS 및 Windows 데스크톱 패키징 설정은 [modules/desktop_app.md](desktop_app.md)를 따릅니다.
- **Core Module Verification**: 릴리즈 전 단위 테스트 검증은 [modules/core_module.md](core_module.md)를 따릅니다.
