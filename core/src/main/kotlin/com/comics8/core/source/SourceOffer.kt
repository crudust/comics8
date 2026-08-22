package com.comics8.core.source

data class SourceOffer(
    val id: String,
    val displayName: String,
    val implementation: SourceImplementation,
)

enum class SourceImplementation { BUILTIN_LOCAL, JS_PACK }

/** Catalog offers the app can add. Local is builtin, not an offer. Site names are never listed here. */
object BuiltinOffers {
    val LOCAL = SourceOffer("local", "저장소", SourceImplementation.BUILTIN_LOCAL)

    /** JS packs are imported as files; they are not catalog offers. */
    fun bundled(): List<SourceOffer> = emptyList()
}
