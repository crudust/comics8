# I18n & Translation Domain Contract

## Responsibilities & Boundaries

Provides centralized multi-language support across the shared Kotlin core, Android Compose UI, and Desktop Compose UI without relying solely on platform-specific string XML files.

## Core Invariants

- **Supported Languages (`AppLanguage.kt`)**:
  - `KO`: Korean (Default)
  - `EN`: English
  - `JA`: Japanese
  - `ZH_CN`: Simplified Chinese
  - `ZH_TW`: Traditional Chinese
- **Data Structure (`AppStrings.kt`)**:
  - `AppStrings` interface defines all localized keys.
  - Implementations: `KoStrings`, `EnStrings`, `JaStrings`, `ZhCnStrings`, `ZhTwStrings`.
- **Fallback Chain (`I18n.kt`)**:
  - If a key is missing or blank in a given locale, fallback proceeds to `EnStrings` -> `KoStrings`.
  - Adding a new UI string requires adding the key to `AppStrings` and supplying values in all locale implementations.
