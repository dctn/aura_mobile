package info.ibtaura.aura.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Ports of LiveAPITests.swift's request, retry and token classes, plus ServiceClient's own details.

private const val emptyTasks = "{\"tasks\":[],\"deleted\":[],\"cursor\":null}"
private const val bare503 = "{\"message\":\"Service Unavailable\"}"
private const val consentRequired = "{\"error\":{\"code\":\"consent_required\",\"message\":\"Health data consent is needed.\"}}"
private const val checkinOK = "{\"checkin\":{\"id\":\"x\"},\"created\":true}"
private const val consentView =
    "{\"policyVersion\":null,\"healthData\":false,\"aiAssistant\":true,\"productAnalytics\":false,\"decidedAt\":null," +
        "\"required\":true,\"currentPolicyVersion\":\"2026-10\"}"
private const val unauthorized = "{\"message\":\"Unauthorized\"}"

/** Tokens a test can swap from the fake's handler, so "another call refreshed meanwhile" needs no timing. */
private class SwappableTokens(first: String) : AccessTokenSource {
    private val lock = Any()
    private var held = first

    val current: String get() = synchronized(lock) { held }

    fun replace(token: String) = synchronized(lock) { held = token }

    override suspend fun accessToken(): String = current

    override suspend fun refreshedToken(rejected: String): String {
        replace("b")
        return "b"
    }

    override suspend fun tokenRejected(token: String): Boolean = current == token
}

class ServiceClientRequestTest {
    @Test
    fun sendsBearerTimeZoneAndAppHeaders() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks", netJson(200, emptyTasks))
        rig.api.tasks()
        val call = rig.server.calls("/v1/tasks").first()
        assertEquals("Bearer access-1", call.header("Authorization"))
        assertEquals(ZoneId.systemDefault().id, call.header("X-Time-Zone"))
        assertEquals("android/0.1.0", call.header("X-Aura-App"))
        assertEquals("application/json", call.header("Accept"))
        assertEquals("task.stub.test", call.host)
        assertNull(call.request.body, "A GET has no body")
    }

    @Test
    fun aBodyIsSentAsJson() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/checkins", netJson(201, checkinOK))
        rig.api.logMood(3, NetRig.now)
        val call = rig.server.calls("/v1/checkins").first()
        assertEquals("application/json", call.request.body?.contentType()?.toString())
        assertEquals("mood", call.string("kind"))
    }

    @Test
    fun baseURLWithStageAndTrailingSlash() = runBlocking<Unit> {
        val rig = NetRig(config = NetRig.config { it["health-data"] = "https://health.stub.test/dev/" })
        rig.server.on("/dev/v1/alerts", netJson(200, "{\"alerts\":[]}"))
        val alerts = rig.api.alerts("2025-01-01", "2025-01-02")
        assertEquals(emptyList(), alerts)
        assertEquals(1, rig.server.calls("/dev/v1/alerts").size)
    }

    @Test
    fun neverMoreThanSixOpen() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks", listOf(netJson(200, emptyTasks)), delayMs = 20)
        netConcurrently(20) { rig.api.tasks() }
        assertEquals(20, rig.server.calls("/v1/tasks").size)
        assertTrue(rig.server.peakOpen <= 6, "peak ${rig.server.peakOpen}")
        assertTrue(rig.server.peakOpen > 1, "calls do run side by side")
    }

    @Test
    fun noAnswerIsOffline() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks", NetAnswer.Offline)
        assertEquals(AuraError.Offline, netCaught { rig.api.tasks() })
    }

    @Test
    fun cancelledCallIsNotOffline() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks", NetAnswer.Cancelled)
        assertIs<CancellationException>(netCaught { rig.api.tasks() })
    }

    @Test
    fun cancellingTheCoroutineCancelsTheCall() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks", listOf(netJson(200, emptyTasks)), delayMs = 3000)
        val started = System.nanoTime()
        val job = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) { rig.api.tasks() }
        while (rig.server.calls("/v1/tasks").isEmpty()) delay(5)
        job.cancel()
        val error = netCaught { job.await() }
        assertIs<CancellationException>(error)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2500, "Cancelling doesn't wait for the answer")
        assertTrue(rig.http.dispatcher.runningCalls().all { it.isCanceled() }, "The OkHttp call is cancelled too")
    }

    @Test
    fun serverErrorKeepsCodeAndMessage() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks", netJson(400, "{\"error\":{\"code\":\"bad_request\",\"message\":\"Not that.\"}}"))
        assertEquals(AuraError.Server("bad_request", "Not that.", 400), netCaught { rig.api.tasks() })
    }

    @Test
    fun gatewayErrorHasOnlyAMessage() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks", netJson(429, "{\"message\":\"Too Many Requests\"}"))
        assertEquals(AuraError.Server("http_429", "Too Many Requests", 429), netCaught { rig.api.tasks() })
    }

    @Test
    fun anErrorWithoutABodyHasTheStatus() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks", netJson(500, ""))
        assertEquals(AuraError.Server("http_500", "HTTP 500", 500), netCaught { rig.api.tasks() })
    }

    @Test
    fun answerItCantReadIsBadResponse() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks", netJson(200, "not json"))
        val error = netCaught { rig.api.tasks() }
        assertEquals("bad_response", (error as? AuraError)?.serverCode)
    }

    @Test
    fun queryIsEscapedStrictlyAndSorted() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/x", netJson(200, "{}"))
        val client = ServiceClient(NetRig.config, NetTokens(), rig.http, sleep = {}, now = { NetRig.now })
        client.send(ServiceName.task, "GET", "/v1/x", query = mapOf("to" to "a b", "cursor" to "a+b/c=é"))
        val call = rig.server.calls("/v1/x").first()
        assertTrue(call.address.endsWith("/v1/x?cursor=a%2Bb%2Fc%3D%C3%A9&to=a%20b"), call.address)
        assertEquals("a+b/c=é", call.query["cursor"])
    }

    @Test
    fun aMissingServiceAddressIsBadConfig() = runBlocking<Unit> {
        val rig = NetRig(config = NetRig.config { it.remove("task") })
        val error = netCaught { rig.api.tasks() }
        assertEquals("bad_config", (error as? AuraError)?.serverCode)
        assertTrue(rig.server.allCalls.isEmpty())
    }

    @Test
    fun aDeleteSendsNoBodyAndAnEmptyAnswerIsFine() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/calendar/events/e1", netJson(204, ""))
        rig.api.deleteEvent("e1")
        val call = rig.server.calls("/v1/calendar/events/e1").first()
        assertEquals("DELETE", call.method)
        assertNull(call.request.body)
        assertNull(call.request.body?.contentType())
    }
}

class ServiceClientRetryTest {
    @Test
    fun bare503BacksOffThenSucceeds() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks", netJson(503, bare503), netJson(503, bare503), netJson(200, emptyTasks))
        rig.api.tasks()
        assertEquals(listOf(1.0, 2.0), rig.sleeps.values)
        assertEquals(3, rig.server.calls("/v1/tasks").size)
    }

    @Test
    fun bare503GivesUpAfterFourWaits() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks", netJson(503, bare503))
        val error = netCaught { rig.api.tasks() }
        assertEquals(listOf(1.0, 2.0, 4.0, 8.0), rig.sleeps.values)
        assertEquals(5, rig.server.calls("/v1/tasks").size)
        assertEquals(AuraError.Server("http_503", "Service Unavailable", 503), error)
    }

    @Test
    fun a503WithAnErrorBodyIsNotRetried() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks", netJson(503, "{\"error\":{\"code\":\"google_down\",\"message\":\"Later.\"}}"))
        val error = netCaught { rig.api.tasks() }
        assertEquals(emptyList(), rig.sleeps.values)
        assertEquals(AuraError.Server("google_down", "Later.", 503), error)
    }

    @Test
    fun consentRequiredWithoutAGrantIsImmediate() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/checkins", netJson(403, consentRequired))
        val error = netCaught { rig.api.logMood(3, NetRig.now) }
        assertEquals(AuraError.ConsentRequired, error)
        assertEquals(emptyList(), rig.sleeps.values)
        assertEquals(1, rig.server.calls("/v1/checkins").size)
    }

    @Test
    fun afterAGrantConsentRequiredIsRetried() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me/consent", netJson(200, consentView))
        rig.server.on("/v1/checkins", netJson(403, consentRequired), netJson(403, consentRequired), netJson(201, checkinOK))
        rig.api.grantHealthConsent()
        rig.api.logMood(3, NetRig.now)
        assertEquals(listOf(1.0, 2.0), rig.sleeps.values)
        assertEquals(3, rig.server.calls("/v1/checkins").size)
    }

    @Test
    fun afterAGrantConsentRetriesGiveUpAfterFourWaits() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me/consent", netJson(200, consentView))
        rig.server.on("/v1/checkins", netJson(403, consentRequired))
        rig.api.grantHealthConsent()
        val error = netCaught { rig.api.logMood(3, NetRig.now) }
        assertEquals(AuraError.ConsentRequired, error)
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), rig.sleeps.values)
        assertEquals(5, rig.server.calls("/v1/checkins").size)
    }

    @Test
    fun aGrantMoreThan30SecondsAgoIsNotWaitedFor() = runBlocking<Unit> {
        val server = NetServer()
        var clock = NetRig.now
        val sleeps = NetSleepLog()
        val api = LiveApi(NetRig.config, NetTokens(), server.client(), sleep = { sleeps.add(it) }, now = { clock })
        server.on("/v1/me/consent", netJson(200, consentView))
        server.on("/v1/checkins", netJson(403, consentRequired))
        api.grantHealthConsent()
        clock = clock.plusSeconds(30)
        assertEquals(AuraError.ConsentRequired, netCaught { api.logMood(3, clock) })
        assertEquals(emptyList(), sleeps.values)
    }

    @Test
    fun grantKeepsTheOtherChoices() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/me/consent", netJson(200, consentView))
        rig.api.grantHealthConsent()
        val calls = rig.server.calls("/v1/me/consent")
        assertEquals(listOf("GET", "PUT"), calls.map { it.method })
        val put = calls.last()
        assertEquals("2026-10", put.string("policyVersion"))
        assertEquals(true, put.bool("healthData"))
        assertEquals(true, put.bool("aiAssistant"))
        assertEquals(false, put.bool("productAnalytics"))
    }
}

class ServiceClientTokenTest {
    @Test
    fun a401RefreshesAndRetries() = runBlocking<Unit> {
        val rig = NetRig()
        rig.server.on("/v1/tasks", netJson(401, unauthorized), netJson(200, emptyTasks))
        rig.api.tasks()
        val calls = rig.server.calls("/v1/tasks")
        assertEquals(listOf("Bearer access-1", "Bearer access-2"), calls.map { it.header("Authorization") })
        assertEquals(1, (rig.tokens as NetTokens).refreshes)
    }

    @Test
    fun aSecond401EndsTheSession() = runBlocking<Unit> {
        val tokens = NetTokens()
        val rig = NetRig(tokens = tokens)
        rig.server.on("/v1/tasks", netJson(401, unauthorized))
        val error = netCaught { rig.api.tasks() }
        assertEquals(AuraError.SignedOut, error)
        assertEquals(1, tokens.refreshes)
        assertEquals(2, rig.server.calls("/v1/tasks").size)
        assertNull(tokens.current)
    }

    @Test
    fun concurrent401sEachRefreshOnceThenSucceed() = runBlocking<Unit> {
        // The single shared refresh is CognitoAuth's (its own tests); here each call asks once and goes on.
        val tokens = NetTokens()
        val rig = NetRig(tokens = tokens)
        rig.server.respond("/v1/tasks", delayMs = 10) { call ->
            if (call.header("Authorization") == "Bearer access-2") netJson(200, emptyTasks) else netJson(401, unauthorized)
        }
        netConcurrently(8) { rig.api.tasks() }
        assertTrue(tokens.refreshes in 1..8)
        assertEquals(8, rig.server.calls("/v1/tasks").count { it.header("Authorization") == "Bearer access-2" })
    }

    @Test
    fun aSecond401OnAnOlderTokenRetriesWithTheNewerOne() = runBlocking<Unit> {
        val tokens = SwappableTokens("a")
        val rig = NetRig(tokens = tokens)
        rig.server.respond("/v1/tasks") { call ->
            val sent = call.header("Authorization")
            if (sent == "Bearer c") return@respond netJson(200, emptyTasks)
            if (sent == "Bearer b") tokens.replace("c")
            netJson(401, unauthorized)
        }
        rig.api.tasks()
        val headers = rig.server.calls("/v1/tasks").map { it.header("Authorization") }
        assertEquals(listOf("Bearer a", "Bearer b", "Bearer c"), headers)
    }

    @Test
    fun stillOlderAfterTheRetryIsNotASignOut() = runBlocking<Unit> {
        val tokens = SwappableTokens("a")
        val rig = NetRig(tokens = tokens)
        rig.server.respond("/v1/tasks") {
            tokens.replace(UUID.randomUUID().toString())
            netJson(401, unauthorized)
        }
        val error = netCaught { rig.api.tasks() }
        assertEquals(AuraError.Server("http_401", "Unauthorized", 401), error)
        assertEquals(3, rig.server.calls("/v1/tasks").size)
    }

    @Test
    fun aRefusedRefreshEndsTheSession() = runBlocking<Unit> {
        val tokens = NetTokens().apply { refreshError = AuraError.SignedOut }
        val rig = NetRig(tokens = tokens)
        rig.server.on("/v1/tasks", netJson(401, unauthorized))
        assertEquals(AuraError.SignedOut, netCaught { rig.api.tasks() })
        assertEquals(1, rig.server.calls("/v1/tasks").size)
    }

    @Test
    fun anOfflineTokenSourceIsOffline() = runBlocking<Unit> {
        val tokens = NetTokens().apply { accessError = AuraError.Offline }
        val rig = NetRig(tokens = tokens)
        assertEquals(AuraError.Offline, netCaught { rig.api.tasks() })
        assertTrue(rig.server.allCalls.isEmpty())
    }

    @Test
    fun noSessionIsSignedOut() = runBlocking<Unit> {
        val rig = NetRig(tokens = NetTokens(first = null))
        assertEquals(AuraError.SignedOut, netCaught { rig.api.tasks() })
        assertEquals(0, rig.server.allCalls.size)
    }

    @Test
    fun urlEscapeKeepsOnlyUnreserved() {
        assertEquals("a-._~Z9", URLEscape.component("a-._~Z9"))
        assertEquals("s1%402030-03-17", URLEscape.component("s1@2030-03-17"))
        assertEquals("%2B%20%2F%3F%26%3D", URLEscape.component("+ /?&="))
        assertNotNull(URLEscape.component(""))
    }
}
