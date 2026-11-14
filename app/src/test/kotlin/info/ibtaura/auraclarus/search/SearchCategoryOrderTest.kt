package info.ibtaura.auraclarus.search

import info.ibtaura.aura.core.CatalogueParameter
import info.ibtaura.aura.core.CatalogueSection
import info.ibtaura.aura.core.FieldAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchCategoryOrderTest {
    private fun section(id: String, vararg fields: String) =
        CatalogueSection(id, id, fields.map { CatalogueParameter(name = it, field = it, read = "series", unit = "") })

    private val sections = (1..9).map { section("s$it", "f$it") }

    private fun has(vararg fields: String) = fields.map { FieldAvailability(field = it, latestDate = "2026-11-14") }

    @Test
    fun withoutAvailabilityItIsTheCatalogueOrder() {
        assertEquals(sections.map { it.id }, SearchCategoryOrder.ids(sections, null))
    }

    @Test
    fun categoriesWithDataComeFirstPaddedWithTheOthers() {
        val ids = SearchCategoryOrder.ids(sections, has("f8", "f3"))
        assertEquals(listOf("s3", "s8", "s1", "s2", "s4", "s5"), ids.take(SearchCategoryOrder.SHOWN))
        assertEquals(listOf("s6", "s7", "s9"), ids.drop(SearchCategoryOrder.SHOWN))
    }

    @Test
    fun moreThanSixWithDataShowsTheFirstSixAndTheRestStayInCatalogueOrder() {
        val ids = SearchCategoryOrder.ids(sections, has("f9", "f8", "f7", "f6", "f5", "f4", "f3"))
        assertEquals(listOf("s3", "s4", "s5", "s6", "s7", "s8"), ids.take(6))
        assertEquals(listOf("s1", "s2", "s9"), ids.drop(6))
    }

    @Test
    fun arrangeFollowsTheSavedOrderAndPutsUnknownSectionsLast() {
        val saved = listOf("s3", "s1")
        val arranged = SearchCategoryOrder.arrange(sections.take(4), saved).map { it.id }
        assertEquals(listOf("s3", "s1", "s2", "s4"), arranged)
    }
}
