# I18n & Translation Domain Contract

## 1. Scope & Canonical Owners

- **Role**: 안드로이드의 `strings.xml`이나 데스크톱 번들에만 의존하지 않고, 공통 Kotlin 코어에서 Android 및 Desktop UI 전반에 단일 인터페이스로 다국어 리소스를 제공하고 계층형 폴백을 소유합니다.
- **Canonical Flow**: `Locale Selection -> I18n.current (AppStrings) -> Targeted Language Implementation -> Fallback Chain (EN -> KO) -> Compose UI Text`.
- **Key Entry Points**:
  - Language Enums: `core/src/main/kotlin/com/comics8/i18n/AppLanguage.kt`
  - String Contract: `core/src/main/kotlin/com/comics8/i18n/AppStrings.kt`
  - Dispatcher & Fallback: `core/src/main/kotlin/com/comics8/i18n/I18n.kt`
  - Locale Implementations: `core/src/main/kotlin/com/comics8/i18n/locales/` (`KoStrings`, `EnStrings`, `JaStrings`, `ZhCnStrings`, `ZhTwStrings`)

## 2. Core Invariants

### 1. Supported Languages & Canonical Enums
- **Language Coverage**: 한국어(`KO`, 기본값), 영어(`EN`), 일본어(`JA`), 중국어 간체(`ZH_CN`), 중국어 번체(`ZH_TW`)의 5개 언어를 정규 지원합니다.

### 2. Centralized Interface & Completeness Contract
- **AppStrings Single Interface**: 모든 로컬라이즈 키는 `AppStrings` 인터페이스에 함수/프로퍼티로 정의됩니다.
- **All-Locale Implementation Requirement**: 신규 UI 문자열 추가 시 `AppStrings`에 인터페이스를 정의하고 5개 언어 구현체(`KoStrings`, `EnStrings`, `JaStrings`, `ZhCnStrings`, `ZhTwStrings`) 전부에 값을 명시해야 컴파일됩니다.

### 3. Graceful Fallback Chain
- **Hierarchical Fallback Order**: 특정 언어 팩에서 키가 비어있거나 누락된 경우, `지정 언어 -> EnStrings(영어) -> KoStrings(한국어)` 순으로 단계적 폴백이 작동하여 UI에 빈 텍스트나 크래시가 발생하는 것을 방지합니다.

## 3. Cross-Boundary Contracts

- **Core Module Contracts**: 순수 Kotlin multiplatform 다국어 처리는 [modules/core_module.md](../modules/core_module.md)를 따릅니다.
- **Desktop UI Strings**: 데스크톱 Compose 뷰의 다국어 렌더링은 [modules/desktop_app.md](../modules/desktop_app.md)를 따릅니다.
- **Android UI Strings**: 안드로이드 Compose 화면의 다국어 렌더링은 [modules/android_app.md](../modules/android_app.md)를 따릅니다.
- **Release Notes Policy**: 릴리즈 노트는 다국어 호환성을 위해 영문 작성 원칙을 따르며 [modules/release_pipeline.md](../modules/release_pipeline.md)를 따릅니다.
