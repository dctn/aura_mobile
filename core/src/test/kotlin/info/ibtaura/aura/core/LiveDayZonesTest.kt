package info.ibtaura.aura.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Ports of DayZonesTests.swift's live classes (zones and the work schedule).

private const val zonesStale = "{\"error\":{\"code\":\"precondition_failed\",\"message\":\"The profile changed since you read it.\"}}"
private const val emptyZones = "{\"days\":[]}"

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.ints(name: String): List<Int>? = (this[name] as? JsonArray)?.map { (it as JsonPrimitive).int }

class LiveDayZonesTest {
    @Test
    fun zonesDecode() = runBlocking<Unit> {
        val rig = NetRig()
        val sample = """
            {"days":[
              {"date":"2026-11-09","status":"ready","score":71.6,"level":"steady","heavyMinutes":120,
               "reason":"Your capacity score is 72, so about 2 h of heavy work today, first thing.",
               "zones":[
                 {"kind":"heavyWork","start":"2026-11-09T03:30:00.000Z","end":"2026-11-09T05:00:00.000Z"},
                 {"kind":"shortRecovery","start":"2026-11-09T05:00:00Z","end":"2026-11-09T05:15:00Z"},
                 {"kind":"deepWork","start":"2026-11-09T05:15:00Z","end":"2026-11-09T05:45:00Z"},
                 {"kind":"lightWork","start":"later","end":"2026-11-09T07:30:00Z"},
                 {"kind":"heavyRecovery","start":"2026-11-09T07:30:00.000Z","end":"2026-11-09T08:30:00.000Z"},
                 {"kind":"lightWork","start":"2026-11-09T08:30:00Z","end":"2026-11-09T12:30:00Z"}
               ]},
              {"date":"2026-11-10","status":"noScore","score":null,"level":null,"heavyMinutes":0,"reason":null,"zones":[]},
              {"date":"2026-11-11","status":"holiday","score":null,"level":null,"heavyMinutes":0,"reason":null,"zones":[]}
            ]}
        """.trimIndent()
        rig.server.on("/v1/planner/zones", netJson(200, sample))
        val days = rig.api.dayZones("2026-11-09", "2026-11-11")
        assertEquals(3, days.size)

        val ready = days[0]
        assertEquals("2026-11-09", ready.date)
        assertEquals(ZoneStatus.ready, ready.status)
        assertEquals(72, ready.score)
        assertEquals("steady", ready.level)
        assertEquals(120, ready.heavyMinutes)
        assertEquals("Your capacity score is 72, so about 2 h of heavy work today, first thing.", ready.reason)
        val i = Instant::parse
        val expected = listOf(
            Zone(ZoneKind.heavyWork, i("2026-11-09T03:30:00Z"), i("2026-11-09T05:00:00Z")),
            Zone(ZoneKind.shortRecovery, i("2026-11-09T05:00:00Z"), i("2026-11-09T05:15:00Z")),
            Zone(ZoneKind.heavyRecovery, i("2026-11-09T07:30:00Z"), i("2026-11-09T08:30:00Z")),
            Zone(ZoneKind.lightWork, i("2026-11-09T08:30:00Z"), i("2026-11-09T12:30:00Z")),
        )
        assertEquals(expected, ready.zones)

        assertEquals(ZoneDay("2026-11-10", ZoneStatus.noScore), days[1])
        assertEquals(ZoneDay("2026-11-11", ZoneStatus.unknown), days[2])
    }

    /** The level is Capacity's band as the Planner sends it; the app never works it out from the score. */
    @Test
    fun levelIsUsedAsSent() = runBlocking<Unit> {
        val rig = NetRig()
        val sample = "{\"days\":[{\"date\":\"2026-11-09\",\"status\":\"ready\",\"score\":95,\"level\":\"shallow\",\"heavyMinutes\":0," +
            "\"reason\":\"Your capacity score is 95, so keep to lighter work today.\",\"zones\":[]}]}"
        rig.server.on("/v1/planner/zones", netJson(200, sample))
        val day = rig.api.dayZones("2026-11-09", "2026-11-09").first()
        assertEquals(95, day.score)
        assertEquals("shallow", day.level)
        assertEquals(0, day.heavyMinutes)
    }

    @Test
    fun missingFieldsStillRead() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/planner/zones", netJson(200, "{\"days\":[{\"date\":\"2026-11-09\",\"status\":\"needsSchedule\"}]}"))
        assertEquals(listOf(ZoneDay("2026-11-09", ZoneStatus.needsSchedule)), rig.api.dayZones("2026-11-09", "2026-11-09"))
    }

    @Test
    fun asksThePlannerForTheRange() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/planner/zones", netJson(200, emptyZones))
        rig.api.dayZones("2026-11-09", "2026-11-15")
        val call = rig.server.calls("/v1/planner/zones").first()
        assertEquals("GET", call.method)
        assertEquals("planner.stub.test", call.host)
        assertEquals(mapOf("from" to "2026-11-09", "to" to "2026-11-15"), call.query)
    }

    @Test
    fun aRangeOver31DaysIsAskedInPieces() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/planner/zones", netJson(200, emptyZones))
        rig.api.dayZones("2026-11-01", "2026-12-15")
        val calls = rig.server.calls("/v1/planner/zones")
        assertEquals(listOf("2026-11-01", "2026-12-02"), calls.map { it.query["from"] })
        assertEquals(listOf("2026-12-01", "2026-12-15"), calls.map { it.query["to"] })
    }
}

class LiveWorkScheduleTest {
    private val unset = "{\"sub\":\"sub-1\",\"timezone\":null,\"workSchedule\":null,\"version\":7}"
    private val hasSchedule = "{\"sub\":\"sub-1\",\"timezone\":\"Asia/Kolkata\",\"version\":8," +
        "\"workSchedule\":{\"workdayStart\":\"08:30\",\"workdayEnd\":\"17:00\",\"lunchStart\":\"12:30\",\"lunchMinutes\":45,\"workDays\":[1,2,3,4,5]}}"
    private val weekdays = WorkSchedule("08:30", "17:00", "12:30", 45, listOf(1, 2, 3, 4, 5))

    @Test
    fun noScheduleIsNull() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(200, unset))
        assertNull(rig.api.workSchedule())
        assertEquals("identity.stub.test", rig.server.calls("/v1/me").first().host)
    }

    @Test
    fun aMissingScheduleIsNull() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(200, "{\"sub\":\"sub-1\",\"timezone\":null,\"version\":7}"))
        assertNull(rig.api.workSchedule())
    }

    @Test
    fun readsTheSchedule() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(200, hasSchedule))
        assertEquals(weekdays, rig.api.workSchedule())
    }

    @Test
    fun aScheduleItCantReadLeavesTheTimeZoneWorking() = runBlocking<Unit> {
        val rig = NetRig()
        val odd = "{\"sub\":\"sub-1\",\"timezone\":null,\"workSchedule\":{\"workDays\":\"weekdays\"},\"version\":7}"
        rig.server.on("/v1/me", netJson(200, odd))
        rig.server.on("/v1/me/profile", netJson(200, odd))
        assertNull(rig.api.workSchedule())
        rig.api.setTimeZoneIfMissing("Europe/Paris")
        assertEquals("\"v7\"", rig.server.calls("/v1/me/profile").first().header("If-Match"))
    }

    @Test
    fun sendsOnlyTheScheduleWithTheVersionRead() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(200, unset))
        rig.server.on("/v1/me/profile", netJson(200, hasSchedule))
        rig.api.setWorkSchedule(weekdays)
        val put = rig.server.calls("/v1/me/profile").first()
        assertEquals("PUT", put.method)
        assertEquals("identity.stub.test", put.host)
        assertEquals("\"v7\"", put.header("If-Match"))
        assertEquals(listOf("workSchedule"), put.body.keys.toList())
        val sent = assertNotNull(put.obj("workSchedule"))
        assertEquals(listOf("lunchMinutes", "lunchStart", "workDays", "workdayEnd", "workdayStart"), sent.keys.sorted())
        assertEquals("08:30", sent.text("workdayStart"))
        assertEquals("17:00", sent.text("workdayEnd"))
        assertEquals("12:30", sent.text("lunchStart"))
        assertEquals(45, (sent["lunchMinutes"] as JsonPrimitive).int)
        assertEquals(listOf(1, 2, 3, 4, 5), sent.ints("workDays"))
        assertEquals("GET", rig.server.calls("/v1/me").first().method)
    }

    @Test
    fun aStaleVersionIsReadAgainOnce() = runBlocking<Unit> {
        val rig = NetRig()
        val newer = "{\"sub\":\"sub-1\",\"timezone\":null,\"workSchedule\":null,\"version\":9}"
        rig.server.on("/v1/me", netJson(200, unset), netJson(200, newer))
        rig.server.on("/v1/me/profile", netJson(412, zonesStale), netJson(200, hasSchedule))
        rig.api.setWorkSchedule(WorkSchedule.suggested)
        val puts = rig.server.calls("/v1/me/profile")
        assertEquals(listOf("\"v7\"", "\"v9\""), puts.map { it.header("If-Match") })
        assertEquals(2, rig.server.calls("/v1/me").size)
        assertEquals(listOf(1, 2, 3, 4, 5), puts.last().obj("workSchedule")?.ints("workDays"))
    }

    @Test
    fun aSecondStaleVersionThrows() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me", netJson(200, unset))
        rig.server.on("/v1/me/profile", netJson(412, zonesStale))
        val error = netCaught { rig.api.setWorkSchedule(WorkSchedule.suggested) }
        assertEquals("precondition_failed", (error as? AuraError)?.serverCode)
        assertEquals(2, rig.server.calls("/v1/me/profile").size)
    }
}
