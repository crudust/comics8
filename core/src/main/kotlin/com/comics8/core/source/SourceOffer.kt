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

    /** Desktop add-source sheet uses [LOCAL]. Android hides it until SAF (PR 9). */
    fun bundled(): List<SourceOffer> = emptyList()
}
