package info.ibtaura.aura.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// New for Android: Health Connect days to the apple-health-data service (contracts/src/apple-health-data.ts).

private const val dayPath = "/v1/health-connect/days/2030-03-17"
private const val batchPath = "/v1/health-connect/days/batch"
private const val hcConsent = "{\"error\":{\"code\":\"consent_required\",\"message\":\"Health data consent is needed.\"}}"

private fun healthDay(date: String = "2030-03-17", full: Boolean = false) = HealthDay(
    date = date,
    timeZone = "Asia/Kolkata",
    recordedAt = NetRig.now,
    sources = listOf("com.google.android.apps.fitness"),
    metrics = if (full) HealthMetrics(stepCount = 8421, distanceKm = 6.2, restingHrBpm = 58.0, sleepDeepMin = 72) else HealthMetrics(),
    workouts = if (full) {
        listOf(HealthWorkout("w-1", "running", NetRig.now.minusSeconds(3600), NetRig.now.minusSeconds(1800), 30, 310.5, null, null, null))
    } else {
        emptyList()
    },
)

private fun JsonObject.number(name: String): Double = (this[name] as JsonPrimitive).double

class LiveHealthConnectTest {
    @Test
    fun aDayIsPutWithOnlyTheValuesItHas() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(dayPath, netJson(200, "{\"day\":{\"date\":\"2030-03-17\"},\"written\":true}"))
        assertTrue(rig.api.putHealthDay(healthDay(full = true)))
        val call = rig.server.calls(dayPath).first()
        assertEquals("PUT", call.method)
        assertEquals("apple-health-data.stub.test", call.host)
        assertEquals(listOf("metrics", "recordedAt", "sources", "timeZone", "workouts"), call.body.keys.sorted(), "No date in a single PUT")
        assertEquals("Asia/Kolkata", call.string("timeZone"))
        assertEquals(NetRig.now, WireDate.parse(call.string("recordedAt")))
        assertEquals(listOf("com.google.android.apps.fitness"), (call.body["sources"] as JsonArray).map { (it as JsonPrimitive).content })

        val metrics = assertNotNull(call.obj("metrics"))
        assertEquals(listOf("distanceKm", "restingHrBpm", "sleepDeepMin", "stepCount"), metrics.keys.sorted(), "Nulls are left out")
        assertEquals(8421, (metrics["stepCount"] as JsonPrimitive).int)
        assertEquals(6.2, metrics.number("distanceKm"))

        val workout = (call.body["workouts"] as JsonArray).single() as JsonObject
        assertEquals(listOf("activeKcal", "endedAt", "id", "minutes", "startedAt", "type"), workout.keys.sorted())
        assertEquals("running", (workout["type"] as JsonPrimitive).content)
        assertEquals(30, (workout["minutes"] as JsonPrimitive).int)
        assertEquals(310.5, workout.number("activeKcal"))
        assertEquals(NetRig.now.minusSeconds(3600), WireDate.parse((workout["startedAt"] as JsonPrimitive).content))
    }

    @Test
    fun anEmptyDaySendsEmptyMetricsAndWorkouts() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(dayPath, netJson(200, "{\"day\":{},\"written\":false}"))
        assertEquals(false, rig.api.putHealthDay(healthDay()))
        val call = rig.server.calls(dayPath).first()
        assertEquals(JsonObject(emptyMap()), call.obj("metrics"))
        assertEquals(JsonArray(emptyList()), call.body["workouts"])
    }

    @Test
    fun aMissingWrittenReadsAsFalse() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(dayPath, netJson(200, "{\"day\":{}}"))
        assertEquals(false, rig.api.putHealthDay(healthDay()))
    }

    @Test
    fun aBatchCarriesEachDaysDateAndReadsTheResults() = runBlocking<Unit> {
        val rig = NetRig()
        val answer = "{\"results\":[{\"date\":\"2030-03-16\",\"written\":true},{\"date\":\"2030-03-17\",\"written\":false},{\"written\":true}]}"
        rig.server.on(batchPath, netJson(200, answer))
        val results = rig.api.putHealthDays(listOf(healthDay("2030-03-16"), healthDay("2030-03-17")))
        assertEquals(listOf("2030-03-16" to true, "2030-03-17" to false), results, "A result without a date is left out")
        val call = rig.server.calls(batchPath).first()
        assertEquals("POST", call.method)
        assertEquals(listOf("days"), call.body.keys.toList())
        val days = (call.body["days"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf("2030-03-16", "2030-03-17"), days.map { (it["date"] as JsonPrimitive).content })
        assertEquals(listOf("date", "metrics", "recordedAt", "sources", "timeZone", "workouts"), days[0].keys.sorted())
    }

    @Test
    fun moreThan31DaysGoInPieces() = runBlocking<Unit> {
        val rig = NetRig()
        val first = LocalDate.parse("2030-01-01")
        val keys = (0 until 40).map { first.plusDays(it.toLong()).toString() }
        rig.server.respond(batchPath) { call ->
            val dates = (call.body["days"] as JsonArray).map { ((it as JsonObject)["date"] as JsonPrimitive).content }
            netJson(200, "{\"results\":[" + dates.joinToString(",") { "{\"date\":\"$it\",\"written\":true}" } + "]}")
        }
        val results = rig.api.putHealthDays(keys.map { healthDay(it) })
        val sizes = rig.server.calls(batchPath).map { (it.body["days"] as JsonArray).size }
        assertEquals(listOf(31, 9), sizes)
        assertEquals(keys, results.map { it.first })
    }

    @Test
    fun noDaysSendNothing() = runBlocking<Unit> {
        val rig = NetRig()
        assertEquals(emptyList(), rig.api.putHealthDays(emptyList()))
        assertTrue(rig.server.allCalls.isEmpty())
    }

    @Test
    fun noServiceAddressIsNotAvailableWithoutACall() = runBlocking<Unit> {
        val rig = NetRig(config = NetRig.config { it.remove("apple-health-data") })
        val error = netCaught { rig.api.putHealthDay(healthDay()) }
        assertEquals(AuraError.Server("not_available", "Health Connect sync isn't available yet.", 0), error)
        val batchError = netCaught { rig.api.putHealthDays(listOf(healthDay())) }
        assertEquals("not_available", (batchError as? AuraError)?.serverCode)
        assertTrue(rig.server.allCalls.isEmpty())
    }

    @Test
    fun aMissingRouteIsNotAvailable() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(dayPath, netJson(404, "{\"message\":\"Not Found\"}"))
        val error = netCaught { rig.api.putHealthDay(healthDay()) }
        assertEquals(AuraError.Server("not_available", "Health Connect sync isn't available yet.", 404), error)
    }

    @Test
    fun withoutConsentItAsksForIt() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(batchPath, netJson(403, hcConsent))
        assertEquals(AuraError.ConsentRequired, netCaught { rig.api.putHealthDays(listOf(healthDay())) })
    }

    @Test
    fun otherErrorsComeThrough() = runBlocking<Unit> {
        val rig = NetRig()
        val answer = "{\"error\":{\"code\":\"validation_failed\",\"message\":\"days must hold unique dates.\"}}"
        rig.server.on(batchPath, netJson(400, answer))
        val error = netCaught { rig.api.putHealthDays(listOf(healthDay(), healthDay())) }
        assertEquals(AuraError.Server("validation_failed", "days must hold unique dates.", 400), error)
    }
}
