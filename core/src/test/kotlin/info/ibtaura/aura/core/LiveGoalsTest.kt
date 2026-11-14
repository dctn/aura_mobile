package info.ibtaura.aura.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Goals, habits and settings over a real local HTTP server (MockWebServer): path, method, body and headers.

class LiveGoalsTest {
    private lateinit var server: MockWebServer
    private lateinit var api: LiveApi
    private val routes = mutableMapOf<String, MutableList<MockResponse>>()
    private val today = DayKey.string(NetRig.now)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = "${request.method} ${request.requestUrl?.encodedPath}"
                val queue = synchronized(routes) { routes[key] }
                    ?: return MockResponse().setResponseCode(500).setBody("{\"error\":{\"code\":\"no_stub\",\"message\":\"$key\"}}")
                return synchronized(routes) { if (queue.size > 1) queue.removeAt(0) else queue.first() }
            }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val config = NetRig.config.copy(services = ServiceName.entries.associate { it.raw to base })
        api = LiveApi(config, NetTokens(), OkHttpClient(), sleep = {}, now = { NetRig.now })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun on(key: String, vararg answers: Pair<Int, String>) {
        synchronized(routes) {
            routes[key] = answers.map { (status, body) ->
                MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
            }.toMutableList()
        }
    }

    private fun requests(): List<RecordedRequest> = (0 until server.requestCount).map { server.takeRequest() }

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject

    private val me = """{"sub":"u1","version":7,"settings":{"units":"imperial","focus.dailyGoalMinutes":150,"x":true},
        "healthGoals":{"stepsPerDay":9000,"activeKcalPerDay":null,"sleepNeedMinutes":450,"standHoursPerDay":10,
        "bedtime":"23:30","wakeTime":"07:15","waterGoalMl":2500}}"""

    // MARK: Identity

    @Test
    fun goalsProfileReadsGoalsSettingsAndVersion() = runBlocking<Unit> {
        on("GET /v1/me", 200 to me)
        val profile = api.goalsProfile()
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/me", request.requestUrl?.encodedPath)
        assertEquals("Bearer access-1", request.getHeader("Authorization"))
        assertEquals(7, profile.version)
        assertEquals(HealthGoals(9000, null, 450, 10, "23:30", "07:15", 2500), profile.healthGoals)
        assertEquals(UnitSystem.imperial, profile.units)
        assertEquals(150, profile.focusGoalMinutes)
        assertEquals(3, profile.settings.size)
    }

    @Test
    fun profileWithoutHealthGoalsHasNone() = runBlocking<Unit> {
        on("GET /v1/me", 200 to """{"version":2,"settings":{}}""")
        val profile = api.goalsProfile()
        assertNull(profile.healthGoals)
        assertEquals(UnitSystem.metric, profile.units)
        assertNull(profile.focusGoalMinutes)
    }

    @Test
    fun putHealthGoalsSendsOnlyTheChangesWithIfMatch() = runBlocking<Unit> {
        on("PUT /v1/me/profile", 200 to me.replace("\"version\":7", "\"version\":8"))
        val answer = api.putHealthGoals(mapOf(HealthGoals.STEPS to JsonPrimitive(10000), HealthGoals.BEDTIME to null), 7)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/me/profile", request.requestUrl?.encodedPath)
        assertEquals("\"v7\"", request.getHeader("If-Match"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = request.json()
        assertEquals(listOf("healthGoals"), body.keys.toList())
        val goals = body["healthGoals"]!!.jsonObject
        assertEquals(10000, goals["stepsPerDay"]!!.jsonPrimitive.content.toInt())
        assertEquals(JsonNull, goals["bedtime"])
        assertEquals(2, goals.size)
        assertEquals(8, answer.version)
    }

    @Test
    fun putHealthGoalsReadsTheVersionAgainAfter412() = runBlocking<Unit> {
        on("PUT /v1/me/profile",
            412 to """{"error":{"code":"version_mismatch","message":"Changed."}}""",
            200 to me)
        on("GET /v1/me", 200 to me.replace("\"version\":7", "\"version\":11"))
        api.putHealthGoals(mapOf(HealthGoals.WATER_ML to JsonPrimitive(3000)), 7)
        val calls = requests()
        assertEquals(listOf("PUT", "GET", "PUT"), calls.map { it.method })
        assertEquals("\"v7\"", calls[0].getHeader("If-Match"))
        assertEquals("\"v11\"", calls[2].getHeader("If-Match"))
    }

    @Test
    fun refusedHealthGoalsBecomeAClearError() = runBlocking<Unit> {
        on("PUT /v1/me/profile", 400 to """{"error":{"code":"invalid_request","message":"Unrecognized key: \"healthGoals\""}}""")
        val error = netCaught { api.putHealthGoals(mapOf(HealthGoals.STEPS to JsonPrimitive(9000)), 7) }
        assertEquals("health_goals_refused", (error as? AuraError.Server)?.code)
    }

    @Test
    fun meSettingsReturnsTheSettingsObject() = runBlocking<Unit> {
        on("GET /v1/me", 200 to me)
        val settings = api.meSettings()
        assertEquals("imperial", settings["units"].settingText())
        assertEquals(150.0, settings["focus.dailyGoalMinutes"].settingNumber())
        assertEquals(true, settings["x"].settingFlag())
    }

    @Test
    fun putMeSettingsSendsTheBodyAsGiven() = runBlocking<Unit> {
        on("PUT /v1/me/settings", 200 to me)
        api.putMeSettings(mapOf("units" to JsonPrimitive("imperial"), "old.key" to null))
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/me/settings", request.requestUrl?.encodedPath)
        assertNull(request.getHeader("If-Match"))
        val body = request.json()
        assertEquals("imperial", body["units"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["old.key"])
    }

    // MARK: Progress

    @Test
    fun progressReadsTheGoalColumnsWaterAndFocus() = runBlocking<Unit> {
        on("GET /v1/metrics/daily", 200 to """{"days":[{"date":"$today","metrics":{"stepCount":6000,"stepGoalProgressPct":75,
            "activeKcal":250,"activeKcalGoalProgressPct":50,"standHours":6,"standHoursGoalProgressPct":null}}],"cursor":null}""")
        on("GET /v1/logs", 200 to """{"logs":[{"id":"a","type":"water","date":"$today","data":{"ml":250}},
            {"id":"b","type":"water","date":"$today","data":{"ml":500}},{"id":"c","type":"water","date":"$today","data":{}}]}""")
        val start = WireDate.string(NetRig.now.minusSeconds(3600))
        on("GET /v1/sessions", 200 to """{"sessions":[{"id":"s1","kind":"focus","startedAt":"$start","activeMinutes":40},
            {"id":"s2","kind":"recovery","startedAt":"$start","activeMinutes":10}],"cursor":null}""")
        val progress = api.goalProgressToday()
        assertEquals(6000.0, progress.steps)
        assertEquals(75.0, progress.stepsPct)
        assertEquals(50.0, progress.activeKcalPct)
        assertNull(progress.standHoursPct)
        assertEquals(750.0, progress.waterMl)
        assertEquals(40, progress.focusMinutes)
        val calls = requests()
        val daily = calls.first { it.requestUrl?.encodedPath == "/v1/metrics/daily" }
        assertEquals(today, daily.requestUrl?.queryParameter("from"))
        assertEquals(today, daily.requestUrl?.queryParameter("to"))
        assertTrue(daily.requestUrl?.queryParameter("fields")!!.contains("stepGoalProgressPct"))
        val logs = calls.first { it.requestUrl?.encodedPath == "/v1/logs" }
        assertEquals("water", logs.requestUrl?.queryParameter("type"))
    }

    @Test
    fun progressWithoutWaterLogsStillLoads() = runBlocking<Unit> {
        on("GET /v1/metrics/daily", 200 to """{"days":[],"cursor":null}""")
        on("GET /v1/logs", 404 to """{"message":"Not Found"}""")
        on("GET /v1/sessions", 200 to """{"sessions":[],"cursor":null}""")
        val progress = api.goalProgressToday()
        assertNull(progress.steps)
        assertNull(progress.waterMl)
        assertEquals(0, progress.focusMinutes)
    }

    // MARK: Habits

    private val habitJson = """{"id":"h1","name":"Walk","cue":"","icon":"figure.walk","colorHex":"34C759","frequency":"daily",
        "specificDays":[],"timesPerWeek":3,"weeklyDay":0,"targetType":"yesNo","targetCount":1,"targetMinutes":20,
        "reminder":{"enabled":false,"time":"08:00"},"goal":{"enabled":true,"targetCount":30,"deadline":null},
        "isPaused":false,"isArchived":false,"isNegative":false,"showStreak":true,"sortOrder":0,
        "streak":{"current":4,"best":9,"aliveThrough":"$today"},"version":3}"""

    @Test
    fun habitsFollowsEveryPage() = runBlocking<Unit> {
        on("GET /v1/habits",
            200 to """{"habits":[$habitJson],"deleted":[],"cursor":"next+1"}""",
            200 to """{"habits":[${habitJson.replace("\"h1\"", "\"h2\"").replace("\"sortOrder\":0", "\"sortOrder\":1")}],"deleted":[],"cursor":null}""")
        val habits = api.habits(includeArchived = false)
        assertEquals(listOf("h1", "h2"), habits.map { it.id })
        assertEquals(HabitStreak(4, 9, today), habits[0].streak)
        assertEquals(3, habits[0].version)
        assertTrue(habits[0].goal.enabled)
        val calls = requests()
        assertEquals(listOf("GET", "GET"), calls.map { it.method })
        assertNull(calls[0].requestUrl?.queryParameter("cursor"))
        assertNull(calls[0].requestUrl?.queryParameter("includeArchived"))
        assertEquals("next+1", calls[1].requestUrl?.queryParameter("cursor"))
    }

    @Test
    fun createHabitSendsAClientIdAndTheFields() = runBlocking<Unit> {
        on("POST /v1/habits", 201 to """{"habit":$habitJson,"created":true}""")
        val habit = api.createHabit(HabitDraft(name = "  Walk  ", cue = "After lunch", frequency = HabitFrequency.specificDays,
            specificDays = listOf(4, 0, 4)))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/habits", request.requestUrl?.encodedPath)
        val body = request.json()
        assertNotNull(runCatching { UUID.fromString(body["id"]!!.jsonPrimitive.content) }.getOrNull())
        assertEquals("Walk", body["name"]!!.jsonPrimitive.content)
        assertEquals("After lunch", body["cue"]!!.jsonPrimitive.content)
        assertEquals(listOf("0", "4"), body["specificDays"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", body["showStreak"]!!.jsonPrimitive.content)
        assertTrue(body.containsKey("goal") && body.containsKey("reminder"))
        assertEquals("h1", habit.id)
    }

    @Test
    fun updateHabitPatchesOnlyChangesWithIfMatch() = runBlocking<Unit> {
        on("PATCH /v1/habits/h1", 200 to """{"habit":$habitJson}""")
        val habit = Habit(id = "h1", name = "Walk", icon = "figure.walk", version = 3,
            goal = HabitGoal(true, 30, null))
        api.updateHabit(habit, HabitDraft(habit).copy(name = "Walk 15 min", isPaused = true))
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("\"v3\"", request.getHeader("If-Match"))
        assertEquals(setOf("name", "isPaused"), request.json().keys)
    }

    @Test
    fun updateHabitWithNothingChangedSendsNothing() = runBlocking<Unit> {
        val habit = Habit(id = "h1", name = "Walk", version = 3)
        api.updateHabit(habit, HabitDraft(habit))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun deleteHabitEscapesTheId() = runBlocking<Unit> {
        on("DELETE /v1/habits/a%20b", 204 to "")
        api.deleteHabit("a b")
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/habits/a%20b", request.requestUrl?.encodedPath)
    }

    @Test
    fun logHabitSendsTheDayAndReadsTheStreak() = runBlocking<Unit> {
        on("POST /v1/habits/h1/logs", 201 to """{"log":{"id":"l1","habitId":"h1","date":"$today","count":1,"minutes":0,
            "value":"","note":"","mood":0,"source":"phone","createdAt":"2030-03-17T17:46:40Z"},
            "streak":{"current":5,"best":9,"aliveThrough":"$today"},"day":{"date":"$today","count":1,"minutes":0,"kept":true},"created":true}""")
        val result = api.logHabit("h1", today, 1, 0)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = request.json()
        assertEquals(today, body["date"]!!.jsonPrimitive.content)
        assertEquals("phone", body["source"]!!.jsonPrimitive.content)
        assertEquals("1", body["count"]!!.jsonPrimitive.content)
        assertNotNull(runCatching { UUID.fromString(body["id"]!!.jsonPrimitive.content) }.getOrNull())
        assertEquals("l1", result.log.id)
        assertEquals(5, result.streak.current)
        assertTrue(result.dayKept)
        assertTrue(result.created)
    }

    @Test
    fun pausedHabitLogIs409() = runBlocking<Unit> {
        on("POST /v1/habits/h1/logs", 409 to """{"error":{"code":"habit_paused","message":"Paused."}}""")
        val error = netCaught { api.logHabit("h1", today, 1, 0) }
        assertEquals("habit_paused", (error as? AuraError.Server)?.code)
    }

    @Test
    fun deleteHabitLogUsesBothIds() = runBlocking<Unit> {
        on("DELETE /v1/habits/h1/logs/l1", 204 to "")
        api.deleteHabitLog("h1", "l1")
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/habits/h1/logs/l1", request.requestUrl?.encodedPath)
    }

    @Test
    fun habitLogsAsksTheRange() = runBlocking<Unit> {
        on("GET /v1/habit-logs", 200 to """{"logs":[{"id":"l1","habitId":"h1","date":"$today","count":2,"minutes":0}],"cursor":null}""")
        val logs = api.habitLogs(today, today)
        val request = server.takeRequest()
        assertEquals(today, request.requestUrl?.queryParameter("from"))
        assertEquals(today, request.requestUrl?.queryParameter("to"))
        assertEquals(listOf(HabitLog("l1", "h1", today, 2, 0)), logs)
    }

    @Test
    fun habitStatsReadsRateLogsAndMilestones() = runBlocking<Unit> {
        on("GET /v1/habits/h1/stats", 200 to """{"habitId":"h1","streak":{"current":4,"best":9,"aliveThrough":null},
            "completionRate30d":0.533,"totalLogs":16,"milestones":[{"count":7,"reachedOn":"2030-03-01"},{"count":30,"reachedOn":null}]}""")
        val stats = api.habitStats("h1")
        assertEquals("GET", server.takeRequest().method)
        assertEquals(0.533, stats.completionRate30d)
        assertEquals(16, stats.totalLogs)
        assertEquals(listOf(HabitMilestone(7, "2030-03-01"), HabitMilestone(30, null)), stats.milestones)
    }

    // MARK: Pure parts

    @Test
    fun draftChangesAreEmptyForAnUnchangedHabit() {
        val habit = Habit(id = "h", name = "Read", frequency = HabitFrequency.perWeek, timesPerWeek = 4)
        assertTrue(HabitDraft(habit).changes(habit).isEmpty())
        assertFalse(HabitDraft(habit).copy(timesPerWeek = 5).changes(habit).isEmpty())
    }

    @Test
    fun draftProblems() {
        assertNotNull(HabitDraft(name = " ").problem)
        assertNotNull(HabitDraft(name = "Read", frequency = HabitFrequency.specificDays).problem)
        assertNull(HabitDraft(name = "Read").problem)
    }

    @Test
    fun streakEndsAfterAliveThrough() {
        val streak = HabitStreak(5, 9, "2030-03-10")
        assertEquals(5, streak.currentOn("2030-03-10"))
        assertEquals(0, streak.currentOn("2030-03-11"))
    }

    @Test
    fun stubKeepsGoalsHabitsAndSettings() = runBlocking<Unit> {
        val stub = StubApi(now = { Instant.now() })
        val profile = stub.goalsProfile()
        val saved = stub.putHealthGoals(mapOf(HealthGoals.STEPS to JsonPrimitive(12000)), profile.version)
        assertEquals(12000, saved.healthGoals?.stepsPerDay)
        val stale = netCaught { stub.putHealthGoals(mapOf(HealthGoals.STEPS to JsonPrimitive(1)), profile.version) }
        assertEquals(412, (stale as? AuraError.Server)?.status)
        stub.putMeSettings(mapOf("units" to JsonPrimitive("imperial")))
        assertEquals(UnitSystem.imperial, stub.goalsProfile().units)
        val habits = stub.habits(false)
        assertEquals(3, habits.size)
        val made = stub.createHabit(HabitDraft(name = "Stretch"))
        val day = DayKey.string(Instant.now())
        val log = stub.logHabit(made.id, day, 1, 0)
        assertTrue(stub.habitLogs(day, day).any { it.id == log.log.id })
        stub.deleteHabitLog(made.id, log.log.id)
        assertFalse(stub.habitLogs(day, day).any { it.id == log.log.id })
        stub.deleteHabit(made.id)
        assertEquals(3, stub.habits(false).size)
        assertNotNull(stub.goalProgressToday().stepsPct)
        assertEquals(30, stub.habitStats("h-walk").milestones.first { it.count == 30 }.count)
    }
}
