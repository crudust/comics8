package com.comics8.core.i18n

import java.util.Locale

enum class AppLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
) {
    AUTO("", "시스템 기본값", "System Default"),
    KO("ko", "한국어", "한국어"),
    EN("en", "영어", "English"),
    JA("ja", "일본어", "日本語"),
    ZH_CN("zh-CN", "중국어 간체", "简体中文"),
    ZH_TW("zh-TW", "중국어 번체", "繁體中文");

    companion object {
        fun fromCode(code: String?): AppLanguage {
            val normalized = code?.trim().orEmpty()
            return entries.firstOrNull { it.code.equals(normalized, ignoreCase = true) } ?: AUTO
        }

        fun resolve(
            selected: AppLanguage,
            systemLocaleTag: String = Locale.getDefault().toLanguageTag(),
        ): AppLanguage {
            if (selected != AUTO) return selected
            val tag = systemLocaleTag.lowercase(Locale.ROOT)
            return when {
                tag.startsWith("ko") -> KO
                tag.startsWith("ja") -> JA
                tag.startsWith("zh") -> {
                    if (tag.contains("tw") || tag.contains("hk") || tag.contains("mo") || tag.contains("hant")) {
                        ZH_TW
                    } else {
                        ZH_CN
                    }
                }
                tag.startsWith("en") -> EN
                else -> KO
            }
        }
    }
}
