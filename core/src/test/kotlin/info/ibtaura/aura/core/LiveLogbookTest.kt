package info.ibtaura.aura.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The logbook's routes on the fake network: /v1/logs, /v1/checkins, /v1/me and /v1/me/settings.

class LiveLogbookTest {
    private val at = NetRig.now

    private val logReply =
        "{\"log\":{\"id\":\"l1\",\"type\":\"water\",\"date\":\"2030-03-17\",\"takenAt\":\"2030-03-17T08:15:00.000Z\"," +
            "\"data\":{\"ml\":250},\"createdAt\":\"2030-03-17T08:15:01.000Z\"},\"created\":true}"

    @Test
    fun logsAsksTheRangeAndType() = runBlocking<Unit> {
        val rig = NetRig()
        val list = "{\"logs\":[" +
            "{\"id\":\"a\",\"type\":\"food\",\"date\":\"2030-03-17\",\"takenAt\":null,\"data\":{\"meal\":\"lunch\",\"kcal\":640," +
            "\"name\":\"Bowl\",\"vegan\":false},\"createdAt\":\"2030-03-17T13:05:00Z\"}," +
            "{\"id\":\"b\",\"type\":\"telepathy\",\"date\":\"2030-03-17\",\"data\":{},\"createdAt\":\"2030-03-17T13:05:00Z\"}," +
            "{\"nope\":1}]}"
        rig.server.on("/v1/logs", netJson(200, list))
        val logs = rig.api.logs("2030-03-11", "2030-03-17", LogType.food)
        val call = rig.server.calls("/v1/logs").single()
        assertEquals("GET", call.method)
        assertEquals("health-data.stub.test", call.host)
        assertEquals(mapOf("from" to "2030-03-11", "to" to "2030-03-17", "type" to "food"), call.query)
        assertEquals("Bearer access-1", call.header("Authorization"))
        assertEquals(1, logs.size, "An unknown type and an unreadable item are left out")
        val log = logs[0]
        assertEquals(LogType.food, log.type)
        assertNull(log.takenAt)
        assertEquals(640.0, log.number("kcal"))
        assertEquals("Bowl", log.text("name"))
        assertEquals(false, log.flag("vegan"))
        assertEquals(WireDate.parse("2030-03-17T13:05:00Z"), log.at)
    }

    @Test
    fun logsWithoutATypeSendsNone() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/logs", netJson(200, "{\"logs\":[]}"))
        rig.api.logs("2030-03-11", "2030-03-17")
        assertFalse(rig.server.calls("/v1/logs").single().query.containsKey("type"))
    }

    @Test
    fun addLogPostsTheEntry() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/logs", netJson(201, logReply))
        val long = "x".repeat(600)
        val saved = rig.api.addLog(LogType.water, "2030-03-17", at,
            mapOf("ml" to JsonPrimitive(250), "note" to JsonPrimitive(long), "cold" to JsonPrimitive(true), "gone" to JsonNull))
        val call = rig.server.calls("/v1/logs").single()
        assertEquals("POST", call.method)
        assertEquals("application/json", call.request.body?.contentType()?.toString())
        assertEquals("android/0.1.0", call.header("X-Aura-App"))
        assertNotNull(runCatching { UUID.fromString(call.string("id")) }.getOrNull())
        assertEquals("water", call.string("type"))
        assertEquals("2030-03-17", call.string("date"))
        assertEquals(at, WireDate.parse(call.string("takenAt")))
        val data = call.obj("data")!!
        assertEquals(250, data["ml"]!!.jsonPrimitive.int)
        assertEquals(500, data["note"]!!.jsonPrimitive.content.length, "Text is cut at the server's 500")
        assertEquals(JsonPrimitive(true), data["cold"])
        assertEquals(JsonNull, data["gone"])
        assertEquals("l1", saved.id)
        assertEquals(250.0, saved.number("ml"))
    }

    @Test
    fun addLogKeepsTheCallersIdAndLeavesOutAMissingTime() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/logs", netJson(200, logReply))
        rig.api.addLog(LogType.food, "2030-03-17", null, mapOf("pattern" to JsonPrimitive("Vegan")), id = "my-id")
        val call = rig.server.calls("/v1/logs").single()
        assertEquals("my-id", call.string("id"))
        assertFalse(call.body.containsKey("takenAt"))
    }

    @Test
    fun addLogRefusalComesThrough() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/logs", netJson(400, "{\"error\":{\"code\":\"invalid_request\",\"message\":\"Bad.\"}}"))
        val error = netCaught { rig.api.addLog(LogType.water, "2030-03-17", at, mapOf("ml" to JsonPrimitive(1))) }
        assertEquals("invalid_request", (error as? AuraError)?.serverCode)
    }

    @Test
    fun checkinsAsksTheRangeAndKind() = runBlocking<Unit> {
        val rig = NetRig()
        val list = "{\"checkins\":[{\"id\":\"c1\",\"kind\":\"insomnia\",\"takenAt\":\"2030-03-17T08:00:00Z\",\"date\":\"2030-03-17\"," +
            "\"alertness\":null,\"stress\":null,\"exhaustion\":null,\"insomniaScore\":9,\"effort\":null,\"mood\":null," +
            "\"connection\":null},{\"id\":\"c2\",\"kind\":\"dream\",\"takenAt\":\"2030-03-17T08:00:00Z\",\"date\":\"2030-03-17\"}]}"
        rig.server.on("/v1/checkins", netJson(200, list))
        val checkins = rig.api.checkins("2030-03-11", "2030-03-17", CheckinKind.insomnia)
        val call = rig.server.calls("/v1/checkins").single()
        assertEquals("GET", call.method)
        assertEquals(mapOf("from" to "2030-03-11", "to" to "2030-03-17", "kind" to "insomnia"), call.query)
        assertEquals(1, checkins.size)
        assertEquals(9, checkins[0].insomniaScore)
        assertEquals("2030-03-17", checkins[0].date)
    }

    @Test
    fun addCheckinSendsTheAnswersAtTheTop() = runBlocking<Unit> {
        val rig = NetRig()
        val reply = "{\"checkin\":{\"id\":\"c1\",\"kind\":\"insomnia\",\"takenAt\":\"2030-03-17T08:00:00Z\"," +
            "\"date\":\"2030-03-17\",\"insomniaScore\":7},\"created\":true}"
        rig.server.on("/v1/checkins", netJson(201, reply))
        val items = JsonArray(listOf(1, 1, 1, 1, 1, 1, 1).map { JsonPrimitive(it) })
        val saved = rig.api.addCheckin(CheckinKind.insomnia, at,
            mapOf("insomnia" to items, "kind" to JsonPrimitive("mood"), "skipped" to JsonNull))
        val call = rig.server.calls("/v1/checkins").single()
        assertEquals("POST", call.method)
        assertEquals("insomnia", call.string("kind"), "An answer can't replace the kind")
        assertEquals(at, WireDate.parse(call.string("takenAt")))
        assertEquals(items, call.body["insomnia"])
        assertFalse(call.body.containsKey("skipped"), "A null answer is left out")
        assertNotNull(runCatching { UUID.fromString(call.string("id")) }.getOrNull())
        assertEquals(7, saved.insomniaScore)
    }

    @Test
    fun theMoodCheckinStillWorks() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/checkins", netJson(201, "{}"))
        rig.api.logMood(4, at)
        val call = rig.server.calls("/v1/checkins").single()
        assertEquals("mood", call.string("kind"))
        assertEquals(4, call.int("mood"))
    }

    @Test
    fun meSettingsReadsTheProfilesSettings() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(200,
            "{\"sub\":\"s\",\"version\":3,\"settings\":{\"nutrition.calorieGoal\":2200,\"units\":\"metric\"}," +
                "\"healthGoals\":{\"waterGoalMl\":2500,\"bedtime\":\"23:00\"}}"))
        val settings = rig.api.meSettings()
        assertEquals(JsonPrimitive(2200), settings["nutrition.calorieGoal"])
        assertEquals(JsonPrimitive("metric"), settings["units"])
        assertEquals(2500, rig.api.waterGoalMl())
        val calls = rig.server.calls("/v1/me")
        assertEquals(listOf("GET", "GET"), calls.map { it.method })
        assertEquals("identity.stub.test", calls[0].host)
    }

    @Test
    fun missingSettingsAndGoalAreEmpty() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(200, "{\"sub\":\"s\",\"version\":1,\"healthGoals\":{\"waterGoalMl\":\"lots\"}}"))
        assertTrue(rig.api.meSettings().isEmpty())
        assertNull(rig.api.waterGoalMl(), "An unreadable goal counts as none")
    }

    @Test
    fun putMeSettingsSendsTheChangesWithNullToRemove() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me/settings", netJson(200, "{\"sub\":\"s\",\"version\":4,\"settings\":{}}"))
        rig.api.putMeSettings(mapOf("nutrition.calorieGoal" to JsonPrimitive(1800), "old.key" to null))
        val call = rig.server.calls("/v1/me/settings").single()
        assertEquals("PUT", call.method)
        assertEquals("identity.stub.test", call.host)
        assertNull(call.header("If-Match"), "Settings are merged, so no version is sent")
        assertEquals(JsonObject(mapOf("nutrition.calorieGoal" to JsonPrimitive(1800), "old.key" to JsonNull)), call.body)
    }

    @Test
    fun noSettingChangesSendNothing() = runBlocking<Unit> {
        val rig = NetRig()
        rig.api.putMeSettings(emptyMap())
        assertTrue(rig.server.allCalls.isEmpty())
    }
}
