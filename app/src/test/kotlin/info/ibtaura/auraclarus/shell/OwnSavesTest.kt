package info.ibtaura.auraclarus.shell

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// A screen skips the reload its own save causes, and still loads for anyone else's.

class OwnSavesTest {
    @Test
    fun theFirstCallLoads() {
        assertTrue(OwnSaves().shouldLoad(0))
        assertTrue(OwnSaves().shouldLoad(7, 0))
    }

    @Test
    fun ownBumpsAreSkipped() {
        val own = OwnSaves()
        assertTrue(own.shouldLoad(3))
        own.mark(4)
        assertFalse(own.shouldLoad(4))
        own.mark(5)
        own.mark(6)
        assertFalse(own.shouldLoad(6))
    }

    @Test
    fun anotherScreensBumpLoads() {
        val own = OwnSaves()
        assertTrue(own.shouldLoad(3))
        own.mark(4)
        // 5 came from another screen.
        assertTrue(own.shouldLoad(5))
    }

    @Test
    fun aChangedKeyLoadsEvenAfterAnOwnBump() {
        val own = OwnSaves()
        assertTrue(own.shouldLoad(1, 0))
        // A retry tap.
        assertTrue(own.shouldLoad(1, 1))
        own.mark(2)
        assertTrue(own.shouldLoad(2, 2))
    }

    @Test
    fun theSameVersionAgainLoads() {
        // Nothing new since the last call (a screen coming back): not skipped.
        val own = OwnSaves()
        assertTrue(own.shouldLoad(4))
        assertTrue(own.shouldLoad(4))
    }
}
