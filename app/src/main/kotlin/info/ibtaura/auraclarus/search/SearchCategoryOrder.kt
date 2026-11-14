package info.ibtaura.auraclarus.search

import info.ibtaura.aura.core.CatalogueSection
import info.ibtaura.aura.core.FieldAvailability

/**
 * The order of Search's categories when they are folded to a few (not in iOS, which lists all 25). The first [SHOWN]
 * are the categories with data for this person, in the catalogue's order, padded with the others in the catalogue's
 * order; "Explore more" then adds the rest, still in the catalogue's order, under them, so no row moves when it opens.
 */
internal object SearchCategoryOrder {
    /** How many categories show before "Explore more". */
    const val SHOWN = 6

    /** Every section id, the shown ones first. Without availability it is simply the catalogue's order. */
    fun ids(sections: List<CatalogueSection>, availability: List<FieldAvailability>?): List<String> {
        val withData = availability?.mapTo(HashSet()) { it.field }.orEmpty()
        val (hasData, noData) = sections.partition { section -> section.parameters.any { it.field in withData } }
        val shown = (hasData + noData).take(SHOWN).map { it.id }
        val shownSet = shown.toSet()
        return shown + sections.map { it.id }.filterNot { it in shownSet }
    }

    /** `sections` in the saved order. Sections the order doesn't know (a new catalogue) go last, in their own order. */
    fun arrange(sections: List<CatalogueSection>, ids: List<String>): List<CatalogueSection> {
        val rank = ids.withIndex().associate { (index, id) -> id to index }
        return sections.withIndex().sortedBy { (index, section) -> rank[section.id] ?: (ids.size + index) }.map { it.value }
    }
}
