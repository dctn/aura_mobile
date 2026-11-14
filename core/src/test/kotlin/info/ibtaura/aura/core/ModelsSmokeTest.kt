package info.ibtaura.aura.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsSmokeTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test
    fun modesCatalogueLoads() {
        val modes = Mode.loadCatalogue()
        assertEquals(65, modes.size)
        assertEquals(65, modes.map { it.id }.toSet().size)
        val box = modes.first { it.id == "box" }
        assertEquals(Shelf.breathe, box.shelf)
        assertEquals(RunKind.breathe, box.run)
        assertEquals("In 4 · Hold 4 · Out 4 · Hold 4", box.pattern?.line)
        assertEquals("BREATHE · 5 MIN", box.kicker(minutes = 5))
        assertEquals("flowtime", modes.first { it.id == "flow" }.sessionMode)
        assertEquals("meeting", modes.first { it.id == "meeting" }.sessionMode)
        assertEquals("FOCUS · OPEN", modes.first { it.id == "flow" }.kicker(minutes = 0))
        assertTrue(Mode.nowCards(modes, hour = 10).size <= 4)
        assertEquals(emptyList(), Mode.decodeCatalogue("not json"))
    }

    @Test
    fun modeLogic() {
        val nap = Mode(id = "power-nap", name = "Nap", word = "Nap", shelf = Shelf.rest, minutes = 20)
        assertEquals(RecoveryType.nap, nap.recoveryType)
        val walk = Mode(id = "x", name = "Walk", word = "Walk", shelf = Shelf.movement, minutes = 10)
        assertEquals(RecoveryType.walk, walk.recoveryType)
        assertNull(walk.copy(kind = "focus").recoveryType)
        assertEquals("Open", Mode.lengthText(0))
        assertEquals("1h 30m", Mode.lengthText(90))
        assertEquals("8h", Mode.lengthText(480))
        assertTrue(walk.matches("WAL"))
        assertEquals("In 5 · Out 5", BreathPattern.coherent.line)

        val dayOff = Mode(id = "day-off", name = "Day off", word = "Off", shelf = Shelf.rest, minutes = 0, run = RunKind.plan)
        val morning = ZonedDateTime.of(2026, 11, 14, 8, 30, 0, 0, kolkata).toInstant()
        val slot = dayOff.planSlot(morning, kolkata)
        assertEquals(ZonedDateTime.of(2026, 11, 14, 9, 0, 0, 0, kolkata).toInstant(), slot.start)
        assertEquals(slot.start.plusSeconds(3600), slot.end)
        val late = dayOff.planSlot(morning.plusSeconds(3600), kolkata)
        assertEquals(ZonedDateTime.of(2026, 11, 15, 9, 0, 0, 0, kolkata).toInstant(), late.start)
    }

    @Test
    fun dayKeyRoundTrips() {
        val noon = ZonedDateTime.of(2026, 3, 9, 12, 0, 0, 0, kolkata).toInstant()
        assertEquals("2026-03-09", DayKey.string(noon, kolkata))
        val start = DayKey.date("2026-03-09", kolkata)
        assertEquals(ZonedDateTime.of(2026, 3, 9, 0, 0, 0, 0, kolkata).toInstant(), start)
        assertEquals("2026-03-09", DayKey.string(start!!, kolkata))
        assertEquals("2026-03-09", DayKey.string(DayKey.date("2026-03-09T10:00:00Z", kolkata)!!, kolkata))
        assertEquals(LocalDate.of(2026, 1, 5), DayKey.localDate("2026-1-5"))
        assertNull(DayKey.date("2026-03"))
        assertNull(DayKey.date("nope"))
        val today = DayKey.string(Instant.now())
        assertEquals(today, DayKey.string(DayKey.date(today)!!))
    }

    @Test
    fun computedProperties() {
        assertNull(PlanWarnings().note)
        assertEquals("1 of your blocks was on recovery time; the recovery moved to free time.", PlanWarnings(1, 1).note)
        assertEquals("3 of your blocks were on recovery time; 2 moved to free time.", PlanWarnings(3, 2).note)

        val option = RecoveryOption("walk", "Walk", 10, 9.0, 50, "")
        assertEquals("+9, fades within 50 min", option.hint)
        assertEquals("+10, fades within 2 h", option.copy(maxPoints = 10.0, fadesAfterMinutes = 120).hint)
        assertEquals("+2.5, does not fade", option.copy(maxPoints = 2.5, fadesAfterMinutes = 0).hint)
        assertEquals(RecoveryType.walk, option.type)

        assertEquals(540, WorkSchedule.minutes("09:00"))
        assertNull(WorkSchedule.minutes("9:00"))
        assertEquals("07:05", WorkSchedule.clock(425))
        assertNull(WorkSchedule.suggested.problem)
        assertEquals("Choose each work day once.", WorkSchedule.suggested.copy(workDays = listOf(1, 1)).problem)

        val block = PlanBlock(null, null, Instant.EPOCH, Instant.EPOCH, 0.5, reason = "", part = PlanPart(1, 2))
        assertEquals("Write (1/2)", block.title("Write"))
        assertEquals("Recovery", block.copy(kind = "recovery").title("Write"))
        assertEquals("pvtB", QuickCheckRun(takenAt = Instant.EPOCH, durationSeconds = 180, reactionTimesMs = emptyList(), falseStarts = 0).kind)
        assertEquals("none/Steps", CatalogueParameter("Steps", null, "series", "").id)
        assertTrue(SleepSample.Kind.asleepREM.isAsleep)
        assertFalse(SleepSample.Kind.inBed.isAsleep)
        assertEquals("health-data", ServiceName.healthData.raw)
    }

    @Test
    fun eventDraft() {
        val now = ZonedDateTime.of(2026, 11, 14, 10, 12, 40, 0, kolkata).toInstant()
        val draft = EventDraft.new(now, kolkata)
        assertEquals(ZonedDateTime.of(2026, 11, 14, 10, 30, 0, 0, kolkata).toInstant(), draft.start)
        assertEquals("Give it a title.", draft.problem)
        val event = CalendarEvent("e1", "Write", draft.start, draft.end, location = "Desk")
        val edit = EventDraft(event).copy(location = "  ")
        assertNull(edit.problem)
        val body = edit.changes(event)
        assertEquals(setOf("location"), body.keys)
        assertEquals(JsonNull, body["location"])
        val apple = event.copy(source = "apple")
        assertEquals(setOf("kind"), EventDraft(apple).copy(title = "Other", kind = "meeting").changes(apple).keys)
        assertEquals("Write", edit.createBody()["title"]?.jsonPrimitive?.content)
        assertFalse(edit.createBody().containsKey("location"))
    }

    @Test
    fun errorsAndTokens() {
        val error: Exception = AuraError.Server("work_limit_reached", "Too much work.", 409)
        assertIs<AuraError>(error)
        assertEquals("Too much work.", error.message)
        assertEquals("work_limit_reached", error.serverCode)
        assertNull(AuraError.Offline.serverCode)

        // {"sub":"abc","email":"A@B.C","exp":2000000000}
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"abc","email":"A@B.C","exp":2000000000}""".toByteArray())
        val token = "h.$payload.s"
        val tokens = AuthTokens.issued(token, token, "r", expiresIn = 10, now = Instant.EPOCH)
        assertEquals(Instant.ofEpochSecond(2_000_000_000), tokens.expiresAt)
        assertEquals("abc", tokens.sub)
        assertEquals("a@b.c", tokens.email)
        assertEquals(Instant.ofEpochSecond(10), AuthTokens.issued("x", "y", "r", 10, Instant.EPOCH).expiresAt)

        val store = InMemoryTokenStore()
        store.save(tokens)
        assertEquals(tokens, store.load())
        assertEquals(tokens, Json.decodeFromString(AuthTokens.serializer(), Json.encodeToString(AuthTokens.serializer(), tokens)))
        store.clear()
        assertNull(store.load())

        val config = AppConfig.decode(
            """{"stage":"dev","region":"us-west-1","userPoolId":"p","clientId":"c","platform":"android",
               "appVersion":"1","consentPolicyVersion":"v1","services":{"health-data":"https://example.com/"},"extra":1}""",
        )
        assertEquals("https://example.com/", config.baseURL(ServiceName.healthData).toString())
        assertNull(config.baseURL(ServiceName.planner))
    }
}
