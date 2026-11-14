package info.ibtaura.auraclarus.recovery

import info.ibtaura.aura.core.Mode
import info.ibtaura.aura.core.Shelf
import info.ibtaura.auraclarus.shell.InMemoryKeyValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryLayoutTest {
    private val now = RecoverySection.now
    private fun s(shelf: Shelf) = RecoverySection(shelf)

    @Test
    fun defaultLayoutIsNowThenTheShelvesInFigmaOrder() {
        val layout = RecoveryLayout()
        assertEquals(listOf("now", "focus", "sleep", "movement", "breathe", "meditate", "rest", "mind", "outside", "people", "fuel"),
            layout.order.map { it.key })
        assertTrue(layout.isDefault)
        assertEquals(layout.order, layout.visible)
    }

    @Test
    fun movedAndToggledChangeOrderAndVisibility() {
        val layout = RecoveryLayout().moved(0, 3).toggled(s(Shelf.fuel))
        assertEquals(listOf("focus", "sleep", "movement", "now"), layout.order.take(4).map { it.key })
        assertTrue(layout.isHidden(s(Shelf.fuel)))
        assertFalse(s(Shelf.fuel) in layout.visible)
        assertFalse(layout.isDefault)
        assertEquals(layout, layout.moved(0, 99))
        assertTrue(layout.toggled(s(Shelf.fuel)).hidden.isEmpty())
    }

    @Test
    fun decodeDropsUnknownAndRepeatedKeysAndAddsMissingSections() {
        val layout = RecoveryLayout.decode("fuel,bogus,now,fuel", "rest,nope")
        assertEquals(listOf("fuel", "now", "focus"), layout.order.take(3).map { it.key })
        assertEquals(RecoverySection.defaults.size, layout.order.size)
        assertEquals(setOf(s(Shelf.rest)), layout.hidden)
        assertEquals(RecoveryLayout(), RecoveryLayout.decode(null, null))
    }

    @Test
    fun storeKeepsALayoutPerAccountAndForgetsTheDefault() {
        val prefs = InMemoryKeyValues()
        val mine = RecoveryLayoutStore(prefs, "Me@Example.com")
        val other = RecoveryLayoutStore(prefs, "other@example.com")
        val edited = RecoveryLayout().moved(1, 0).toggled(now)
        mine.save(edited)
        assertEquals(edited, mine.load())
        assertEquals(edited, RecoveryLayoutStore(prefs, "me@example.com ").load())
        assertEquals(RecoveryLayout(), other.load())

        mine.save(RecoveryLayout())
        val key = "recovery.layout.${RecoveryLayoutStore.accountKey("me@example.com")}.order"
        assertNull(prefs.getString(key))
    }

    @Test
    fun accountKeyNeverHoldsTheEmail() {
        val key = RecoveryLayoutStore.accountKey("someone@example.com")
        assertFalse(key.contains("someone"))
        assertEquals(16, key.length)
        assertEquals("signed-out", RecoveryLayoutStore.accountKey(null))
        assertNotEquals(key, RecoveryLayoutStore.accountKey("someone-else@example.com"))
    }

    private fun mode(id: String, shelf: Shelf, minutes: Int) = Mode(id = id, name = id, word = id, shelf = shelf, minutes = minutes)

    @Test
    fun lengthBandsSplitTheMinutes() {
        assertTrue(LengthBand.UpTo10.matches(10))
        assertFalse(LengthBand.UpTo10.matches(0))
        assertTrue(LengthBand.From10To20.matches(11))
        assertTrue(LengthBand.From10To20.matches(20))
        assertTrue(LengthBand.Over20.matches(25))
        assertTrue(LengthBand.Open.matches(0))
        assertFalse(LengthBand.Open.matches(5))
    }

    @Test
    fun filterNeedsALengthAndAShelfMatchWhenBothAreSet() {
        val nap = mode("nap", Shelf.sleep, 10)
        val walk = mode("walk", Shelf.movement, 20)
        val flow = mode("flow", Shelf.focus, 0)
        val any = RecoveryFilter()
        assertFalse(any.isActive)
        assertTrue(listOf(nap, walk, flow).all { any.matches(it) })

        val short = any.toggled(LengthBand.UpTo10).toggled(LengthBand.Open)
        assertEquals(listOf(nap, flow), listOf(nap, walk, flow).filter { short.matches(it) })
        val shortFocus = short.toggled(Shelf.focus)
        assertEquals(listOf(flow), listOf(nap, walk, flow).filter { shortFocus.matches(it) })
        assertEquals("≤10 min, Open · Focus", shortFocus.summary)
        assertEquals("3 lengths", any.toggled(LengthBand.UpTo10).toggled(LengthBand.Open).toggled(LengthBand.Over20).summary)
    }

    @Test
    fun filterSaverRoundTrips() {
        val filter = RecoveryFilter(setOf(LengthBand.Over20), setOf(Shelf.sleep, Shelf.fuel))
        val saved = with(RecoveryFilter.saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(filter) }!!
        assertEquals(filter, RecoveryFilter.saver.restore(saved))
        val empty = with(RecoveryFilter.saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(RecoveryFilter()) }!!
        assertEquals(RecoveryFilter(), RecoveryFilter.saver.restore(empty))
    }
}
