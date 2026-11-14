package info.ibtaura.aura.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A port of ModesCatalogTests.swift and ModeRecoveryTypeTests (BetaScoreTests.swift). The catalogue is core's
 * modes.json resource, read by `Mode.loadCatalogue()`. AppConfigFileTests is not here: it needs aura-dev.json and
 * LiveServices, which belong to the live layer.
 */
class ModesCatalogTest {
    private val modes: List<Mode> by lazy { Mode.loadCatalogue() }

    @Test
    fun has65Modes() {
        assertEquals(65, modes.size)
    }

    @Test
    fun idsAndWordsAreUnique() {
        assertEquals(modes.size, modes.map { it.id }.toSet().size)
        assertEquals(modes.size, modes.map { it.word }.toSet().size)
    }

    @Test
    fun everyShelfHasModes() {
        for (shelf in Shelf.entries) {
            assertFalse(modes.none { it.shelf == shelf }, "${shelf.raw} is empty")
        }
    }

    @Test
    fun everyKindIsOneTheBackendTakes() {
        val allowed = setOf("focus", "meeting", "recovery", "walk")
        for (mode in modes) {
            assertTrue(mode.kind in allowed, "${mode.id} has kind ${mode.kind}")
        }
    }

    @Test
    fun breatheModesHaveAPattern() {
        for (mode in modes.filter { it.run == RunKind.breathe }) {
            assertNotNull(mode.pattern, "${mode.id} has no pattern")
        }
    }

    @Test
    fun guidedModesHavePrompts() {
        for (mode in modes.filter { it.run == RunKind.guided }) {
            assertFalse(mode.prompts.isNullOrEmpty(), "${mode.id} has no prompts")
        }
    }

    // MARK: ModeRecoveryTypeTests

    @Test
    fun theRecoveryTypeMappingRules() {
        assertEquals(RecoveryType.nap, Mode(id = "nap-20", name = "Nap", word = "Nap", shelf = Shelf.sleep, minutes = 20).recoveryType)
        assertEquals(RecoveryType.nap, Mode(id = "caffeine-nap", name = "C", word = "C", shelf = Shelf.rest, minutes = 20).recoveryType)
        assertEquals(RecoveryType.outside, Mode(id = "green-sit", name = "G", word = "G", shelf = Shelf.outside, minutes = 20).recoveryType)
        assertEquals(RecoveryType.social, Mode(id = "social-break", name = "S", word = "S", shelf = Shelf.people, minutes = 15).recoveryType)
        assertEquals(RecoveryType.walk, Mode(id = "stretch", name = "S", word = "S", shelf = Shelf.movement, minutes = 10).recoveryType)
        assertEquals(RecoveryType.rest, Mode(id = "box", name = "B", word = "B", shelf = Shelf.breathe, minutes = 5).recoveryType)
        assertEquals(RecoveryType.rest, Mode(id = "hydration", name = "H", word = "H", shelf = Shelf.fuel, minutes = 5).recoveryType)
        assertNull(Mode(id = "timer", name = "T", word = "T", shelf = Shelf.focus, minutes = 25, kind = "focus").recoveryType)
        assertNull(Mode(id = "urban-walk", name = "W", word = "W", shelf = Shelf.movement, minutes = 20, kind = "walk").recoveryType)
        assertNull(Mode(id = "nap-meeting", name = "M", word = "M", shelf = Shelf.focus, minutes = 20, kind = "meeting").recoveryType)
    }

    @Test
    fun everyRecoveryModeInTheCatalogueHasAType() {
        for (mode in modes) {
            if (mode.kind == "recovery") {
                assertNotNull(mode.recoveryType, "${mode.id} has no recovery type")
            } else {
                assertNull(mode.recoveryType, "${mode.id} is ${mode.kind} and sends no recovery type")
            }
        }
        val byId = modes.associateBy { it.id }
        assertEquals(RecoveryType.nap, byId["nap-10"]?.recoveryType)
        assertEquals(RecoveryType.outside, byId["outdoor-break"]?.recoveryType)
        assertEquals(RecoveryType.social, byId["call-a-friend"]?.recoveryType)
        assertEquals(RecoveryType.rest, byId["recovery-break"]?.recoveryType)
    }
}
