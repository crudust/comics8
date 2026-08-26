package com.comics8.core.source

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceOfferTest {
    @Test
    fun bundledCatalogHasNoSiteOffers() {
        assertThat(BuiltinOffers.bundled()).isEmpty()
        assertThat(BuiltinOffers.LOCAL.id).isEqualTo(WorkId.LOCAL_SOURCE)
        assertThat(BuiltinOffers.LOCAL.implementation).isEqualTo(SourceImplementation.BUILTIN_LOCAL)
    }
}
