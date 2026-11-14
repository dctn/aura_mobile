package info.ibtaura.aura.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ports of QuickCheckAPITests.swift's QuickCheckLiveTests.

private const val checksPath = "/v1/checks"

/** A valid full check as Health Data answers it, with the day's scores after it. */
private val fullAnswer = """
    {"check":{"id":"check-1","takenAt":"2030-03-17T03:10:00.000Z","date":"2030-03-17","kind":"full","valid":true,
      "invalidReason":null,"minutesSinceWake":42,
      "attention":{"meanRtMs":291.4,"medianRtMs":284.5,"lapses":2,"falseStarts":1,"speed":3.52,"performance":0.9524},
      "qualifiesForNormal":true},
     "scores":{"date":"2030-03-17",
      "attention":{"value":96,"status":"ready","checksCounted":14,"checksNeeded":14,"vsNormal":"usual","at":"2030-03-17T03:10:00.000Z"},
      "memory":{"value":null,"status":"learning","checksCounted":3,"checksNeeded":14,"vsNormal":null,"at":null},
      "thinking":{"value":null,"status":"noData","checksCounted":0,"checksNeeded":14,"vsNormal":null,"at":null},
      "focus":{"value":null,"status":"noData","checksCounted":0,"checksNeeded":14,"vsNormal":null,"at":null}},
     "created":true}
""".trimIndent()

/** A run that was too short: not valid, with the server's reason. */
private val invalidAnswer = """
    {"check":{"id":"check-2","takenAt":"2030-03-17T03:10:00.000Z","date":"2030-03-17","kind":"pvtB","valid":false,
      "invalidReason":"not_3_minutes","minutesSinceWake":null,
      "attention":{"meanRtMs":301,"medianRtMs":299,"lapses":0,"falseStarts":0,"speed":3.3,"performance":1},
      "qualifiesForNormal":false},
     "scores":{"date":"2030-03-17",
      "attention":{"value":null,"status":"learning","checksCounted":2,"checksNeeded":14,"vsNormal":null,"at":null},
      "memory":{"value":null,"status":"noData","checksCounted":0,"checksNeeded":14,"vsNormal":null,"at":null},
      "thinking":{"value":null,"status":"noData","checksCounted":0,"checksNeeded":14,"vsNormal":null,"at":null},
      "focus":{"value":null,"status":"noData","checksCounted":0,"checksNeeded":14,"vsNormal":null,"at":null}},
     "created":false}
""".trimIndent()

private const val consentRequiredAnswer = "{\"error\":{\"code\":\"consent_required\",\"message\":\"Health data consent is needed.\"}}"

private val bodyKeys = listOf("device", "id", "kind", "memory", "minutesSinceWake", "pvt", "switching", "takenAt")

/** A run of `answers` reactions of `ms` each. */
private fun makeRun(
    id: String = UUID.randomUUID().toString(),
    takenAt: Instant = NetRig.now,
    seconds: Int = 180,
    answers: Int = 60,
    ms: Double = 300.0,
    falseStarts: Int = 0,
    memory: SpanResult? = null,
) = QuickCheckRun(id, takenAt, seconds, List(answers) { ms }, falseStarts, memory)

class LiveQuickCheckTest {
    @Test
    fun aReactionOnlyCheckIsPostedToHealthData() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(checksPath, netJson(201, fullAnswer))
        val run = QuickCheckRun("6f1c2a3e-1111-4222-8333-944455556666", NetRig.now, 180, listOf(312.4, 287.6, 10_000.0), 1)
        rig.api.submitCheck(run)

        val call = rig.server.calls(checksPath).first()
        assertEquals("POST", call.method)
        assertEquals("health-data.stub.test", call.host)
        assertEquals("/v1/checks", call.url.encodedPath)
        assertEquals(bodyKeys, call.body.keys.sorted())
        assertEquals("6f1c2a3e-1111-4222-8333-944455556666", call.string("id"))
        assertEquals("phone", call.string("device"))
        assertEquals("pvtB", call.string("kind"))
        assertTrue(call.isNull("minutesSinceWake"), "The key is there, as null")
        assertTrue(call.isNull("switching"), "The key is there, as null")
        assertTrue(call.isNull("memory"), "No memory part: null")

        val takenAt = assertNotNull(call.string("takenAt"))
        assertEquals(NetRig.now, WireDate.parse(takenAt))
        assertTrue(takenAt.endsWith("Z") || takenAt.contains("+") || takenAt.drop(19).contains("-"), "An instant with an offset")

        val pvt = assertNotNull(call.obj("pvt"))
        assertEquals(listOf("durationSeconds", "falseStarts", "reactionTimesMs"), pvt.keys.sorted())
        assertEquals(180, (pvt["durationSeconds"] as JsonPrimitive).int)
        assertEquals(1, (pvt["falseStarts"] as JsonPrimitive).int)
        val times = (pvt["reactionTimesMs"] as JsonArray).map { it as JsonPrimitive }
        assertTrue(times.none { it.content.contains(".") }, "Whole milliseconds")
        assertEquals(listOf(312, 288, 10_000), times.map { it.int })
    }

    @Test
    fun aFullCheckSendsTheMemoryPart() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(checksPath, netJson(201, fullAnswer))
        val run = makeRun(memory = SpanResult(4, 6))
        assertEquals("full", run.kind)
        rig.api.submitCheck(run)

        val call = rig.server.calls(checksPath).first()
        assertEquals(bodyKeys, call.body.keys.sorted())
        assertEquals("full", call.string("kind"))
        assertEquals(run.id, call.string("id"))
        val memory = assertNotNull(call.obj("memory"))
        assertEquals(listOf("spanAttempts", "spanCorrect"), memory.keys.sorted())
        assertEquals(4, (memory["spanCorrect"] as JsonPrimitive).int)
        assertEquals(6, (memory["spanAttempts"] as JsonPrimitive).int)
        assertEquals(JsonNull, call.body["minutesSinceWake"])
        assertEquals(JsonNull, call.body["switching"])
    }

    @Test
    fun resendingSendsTheSameId() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(checksPath, netJson(201, fullAnswer), netJson(200, fullAnswer))
        val run = makeRun()
        val first = rig.api.submitCheck(run)
        val second = rig.api.submitCheck(run)
        assertEquals(first, second)
        assertEquals(listOf(run.id, run.id), rig.server.calls(checksPath).map { it.string("id") })
    }

    @Test
    fun aFullAnswerDecodes() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(checksPath, netJson(201, fullAnswer))
        val result = rig.api.submitCheck(makeRun())
        val scores = ScoresToday(
            attention = Score("Attention", 96.0, 14, 14),
            memory = Score("Memory", null, 3, 14),
            thinking = Score("Thinking", null, 0, 14),
            focus = Score("Focus", null, 0, 14),
        )
        val expected = QuickCheckResult(
            id = "check-1", valid = true, invalidReason = null, meanRtMs = 291.4, medianRtMs = 284.5, lapses = 2,
            falseStarts = 1, performance = 0.9524, qualifiesForNormal = true, scores = scores,
        )
        assertEquals(expected, result)
    }

    @Test
    fun anInvalidAnswerCarriesItsReason() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(checksPath, netJson(200, invalidAnswer))
        val result = rig.api.submitCheck(makeRun(seconds = 60))
        assertFalse(result.valid)
        assertEquals("not_3_minutes", result.invalidReason)
        assertFalse(result.qualifiesForNormal)
        assertEquals("check-2", result.id)
        assertEquals(301.0, result.meanRtMs)
        assertEquals(1.0, result.performance)
        assertEquals(Score("Attention", null, 2, 14), result.scores.attention)
    }

    @Test
    fun missingMeasuresReadAsZero() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(checksPath, netJson(201, "{\"check\":{\"id\":\"check-3\",\"valid\":true},\"created\":true}"))
        val result = rig.api.submitCheck(makeRun())
        assertTrue(result.valid)
        assertNull(result.invalidReason)
        assertEquals(0, result.lapses)
        assertEquals(0.0, result.meanRtMs)
        assertFalse(result.qualifiesForNormal)
        assertEquals(Score("Attention", null), result.scores.attention)
    }

    @Test
    fun anAnswerWithoutTheCheckIsABadResponse() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(checksPath, netJson(201, "{\"scores\":{},\"created\":true}"))
        val error = netCaught { rig.api.submitCheck(makeRun()) }
        assertEquals("bad_response", (error as? AuraError)?.serverCode)
    }

    @Test
    fun anAnswerWithoutValidIsABadResponse() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(checksPath, netJson(201, "{\"check\":{\"id\":\"check-4\"},\"created\":true}"))
        val error = netCaught { rig.api.submitCheck(makeRun()) }
        assertEquals("bad_response", (error as? AuraError)?.serverCode)
    }

    @Test
    fun consentRequiredComesThroughAsTheOtherCallsThrowIt() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on(checksPath, netJson(403, consentRequiredAnswer))
        assertEquals(AuraError.ConsentRequired, netCaught { rig.api.submitCheck(makeRun()) })
        assertEquals(1, rig.server.calls(checksPath).size, "No retry without a grant just made")
    }

    @Test
    fun validationErrorsComeThrough() = runBlocking<Unit> {
        val rig = NetRig()
        val answer = "{\"error\":{\"code\":\"validation_failed\",\"message\":\"pvt.durationSeconds is too small.\",\"details\":null}}"
        rig.server.on(checksPath, netJson(400, answer))
        val error = netCaught { rig.api.submitCheck(makeRun(seconds = 5)) }
        assertEquals(AuraError.Server("validation_failed", "pvt.durationSeconds is too small.", 400), error)
    }
}
