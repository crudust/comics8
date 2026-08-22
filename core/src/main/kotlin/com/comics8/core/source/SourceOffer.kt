package com.comics8.core.source

data class SourceOffer(
    val id: String,
    val displayName: String,
    val implementation: SourceImplementation,
)

enum class SourceImplementation { BUILTIN_LOCAL, JS_PACK }

/** Catalog offers the app can add. Site names are never listed here. */
object BuiltinOffers {
    val LOCAL = SourceOffer("local", "로컬", SourceImplementation.BUILTIN_LOCAL)

    /** PR 4 adds [LOCAL] to the add-source sheet. PR 2 has no catalog offers. */
    fun bundled(): List<SourceOffer> = emptyList()
}
