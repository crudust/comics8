package com.comics8.core.i18n

import java.util.Locale

object I18n {
    fun strings(language: AppLanguage): AppStrings {
        return when (language) {
            AppLanguage.AUTO -> strings(AppLanguage.resolve(AppLanguage.AUTO, Locale.getDefault().toLanguageTag()))
            AppLanguage.KO -> KoStrings
            AppLanguage.EN -> EnStrings
            AppLanguage.JA -> JaStrings
            AppLanguage.ZH_CN -> ZhCnStrings
            AppLanguage.ZH_TW -> ZhTwStrings
        }
    }

    fun strings(language: AppLanguage, systemLocaleTag: String): AppStrings {
        val resolved = AppLanguage.resolve(language, systemLocaleTag)
        return when (resolved) {
            AppLanguage.AUTO, AppLanguage.KO -> KoStrings
            AppLanguage.EN -> EnStrings
            AppLanguage.JA -> JaStrings
            AppLanguage.ZH_CN -> ZhCnStrings
            AppLanguage.ZH_TW -> ZhTwStrings
        }
    }
}
