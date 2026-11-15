package info.ibtaura.aura.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okio.Buffer
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// "Continue with Google" through Cognito's hosted sign-in. Every value here is an obvious fake; nothing leaves the
// machine (FakeCognito answers every request).

private const val DOMAIN = "aura-test.auth.us-west-1.amazoncognito.com"
private const val OAUTH_CLIENT = "oauth-client"

private val hostedConfig: AppConfig
    get() = CognitoRig.config.copy(cognitoDomain = DOMAIN, oauthClientId = OAUTH_CLIENT)

private val hosted = HostedSignIn(domain = DOMAIN, clientId = OAUTH_CLIENT)

private val tokenAnswer: CognitoAnswer
    get() = CognitoAnswer.Json(
        200,
        """{"id_token":"${CognitoRig.idToken}","access_token":"access-g","refresh_token":"refresh-g","expires_in":3600,"token_type":"Bearer"}""",
    )

private fun pending(retried: Boolean = false, loggingOut: Boolean = false) =
    HostedPending(verifier = "fake-verifier", state = "fake-state", retried = retried, loggingOut = loggingOut)

/** The form fields a request sent. */
private fun CognitoCall.form(): Map<String, String> {
    val text = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
    return text.split('&').associate { part ->
        java.net.URLDecoder.decode(part.substringBefore('='), "UTF-8") to
            java.net.URLDecoder.decode(part.substringAfter('='), "UTF-8")
    }
}

class PkceTest {
    @Test
    fun challengeMatchesRfc7636() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun verifierIsLongEnoughAndUrlSafe() {
        val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '_')
        val seen = mutableSetOf<String>()
        repeat(50) {
            val verifier = Pkce.verifier()
            assertTrue(verifier.length in 43..128, "length ${verifier.length}")
            assertTrue(verifier.all { it in allowed }, "unexpected character")
            assertFalse('=' in verifier)
            seen.add(verifier)
        }
        assertEquals(50, seen.size)
        val state = Pkce.state()
        assertEquals(43, state.length)
        assertTrue(state.all { it in allowed })
        assertEquals(43, Pkce.challenge(Pkce.verifier()).length)
    }
}

class HostedSignInUrlTest {
    @Test
    fun authorizeUrlGoesStraightToGoogleWithPkce() {
        val url = hosted.authorizeUrl(state = "fake-state", challenge = "fake-challenge")
        assertEquals(
            "https://$DOMAIN/oauth2/authorize?identity_provider=Google&response_type=code&client_id=$OAUTH_CLIENT" +
                "&redirect_uri=auraclarus%3A%2F%2Fauth&scope=openid+email+profile&state=fake-state" +
                "&code_challenge=fake-challenge&code_challenge_method=S256",
            url,
        )
    }

    @Test
    fun authorizeUrlEncodesItsValues() {
        val url = hosted.authorizeUrl(state = "a b&c=d", challenge = "x/y+z")
        assertTrue("state=a+b%26c%3Dd&" in url)
        assertTrue("code_challenge=x%2Fy%2Bz&" in url)
    }

    @Test
    fun logoutUrlReturnsToTheApp() {
        assertEquals(
            "https://$DOMAIN/logout?client_id=$OAUTH_CLIENT&logout_uri=auraclarus%3A%2F%2Fauth%2Fsignout",
            hosted.logoutUrl(),
        )
    }

    @Test
    fun startKeepsTheVerifierWhoseChallengeItSends() {
        val launch = hosted.start(random = SecureRandom())
        assertFalse(launch.pending.loggingOut)
        assertTrue("code_challenge=${Pkce.challenge(launch.pending.verifier)}&" in launch.url)
        assertTrue("state=${launch.pending.state}&" in launch.url)
        val logout = hosted.start(logOutFirst = true)
        assertTrue(logout.pending.loggingOut)
        assertEquals(hosted.logoutUrl(), logout.url)
    }

    @Test
    fun configWithoutADomainHasNoHostedSignIn() {
        assertNull(HostedSignIn.from(CognitoRig.config))
        assertNull(HostedSignIn.from(hostedConfig.copy(cognitoDomain = "  ")))
        assertNull(HostedSignIn.from(hostedConfig.copy(oauthClientId = null)))
        assertNull(HostedSignIn.from(hostedConfig.copy(cognitoDomain = "evil.example/path")))
        assertEquals(hosted, HostedSignIn.from(hostedConfig))
        assertEquals(hosted, HostedSignIn.from(hostedConfig.copy(cognitoDomain = "https://$DOMAIN/")))
        assertNull(CognitoAuth(CognitoRig.config, InMemoryTokenStore()).hostedSignIn)
    }

    @Test
    fun devConfigNamesTheDevDomainAndAndroidClient() {
        val config = AppConfig.decode(File("../app/src/main/assets/aura-dev.json").readText())
        val signIn = assertNotNull(HostedSignIn.from(config))
        assertEquals("aura-clarus-dev.auth.us-west-1.amazoncognito.com", signIn.domain)
        assertEquals("2hap9k4fglk5qu8i87qolbt8oj", signIn.clientId)
        // Email sign-in stays on its own client.
        assertEquals("54fbmqoiqktq2c9k202s234h5v", config.clientId)
    }
}

class HostedRedirectTest {
    @Test
    fun parsesCodeAndState() {
        val redirect = assertNotNull(HostedRedirect.parse("auraclarus://auth?code=fake-code&state=fake-state"))
        assertEquals("fake-code", redirect.code)
        assertEquals("fake-state", redirect.state)
        assertNull(redirect.error)
        assertFalse(redirect.isSignOut)
    }

    @Test
    fun parsesAnErrorAndDecodesIt() {
        val redirect = assertNotNull(
            HostedRedirect.parse("auraclarus://auth?error_description=Already+found+an+entry+for+username+Google_1+&error=invalid_request&state=s%2B1"),
        )
        assertEquals("invalid_request", redirect.error)
        assertEquals("Already found an entry for username Google_1 ", redirect.errorDescription)
        assertEquals("s+1", redirect.state)
        assertTrue(HostedSignIn.isLinkingHiccup(redirect.errorDescription))
    }

    @Test
    fun othersAreNotOurs() {
        assertNull(HostedRedirect.parse("https://auth?code=x"))
        assertNull(HostedRedirect.parse("auraclarus://authx?code=x"))
        assertNull(HostedRedirect.parse("other://auth?code=x"))
        assertTrue(assertNotNull(HostedRedirect.parse("auraclarus://auth/signout")).isSignOut)
    }

    @Test
    fun matchingCodeIsExchangedWithTheKeptVerifier() {
        val next = hosted.handle("auraclarus://auth?code=fake-code&state=fake-state", pending())
        assertEquals(HostedNext.Exchange("fake-code", "fake-verifier"), next)
    }

    @Test
    fun mismatchedOrMissingStateIsRejected() {
        val wrong = hosted.handle("auraclarus://auth?code=fake-code&state=other-state", pending())
        assertEquals(HostedNext.Fail(HostedSignIn.MISMATCH), wrong)
        val missing = hosted.handle("auraclarus://auth?code=fake-code", pending())
        assertEquals(HostedNext.Fail(HostedSignIn.MISMATCH), missing)
        val wrongError = hosted.handle("auraclarus://auth?error=server_error&state=other-state", pending())
        assertEquals(HostedNext.Fail(HostedSignIn.MISMATCH), wrongError)
    }

    @Test
    fun noAttemptUnderWayMeansExpired() {
        assertEquals(HostedNext.Fail(HostedSignIn.EXPIRED), hosted.handle("auraclarus://auth?code=c&state=s", null))
        assertEquals(HostedNext.Ignore, hosted.handle("https://example.com/?code=c", pending()))
    }

    @Test
    fun errorsAndCancelling() {
        assertEquals(HostedNext.Fail(HostedSignIn.FAILED), hosted.handle("auraclarus://auth?error=server_error&state=fake-state", pending()))
        assertEquals(HostedNext.Cancelled, hosted.handle("auraclarus://auth?error=access_denied&state=fake-state", pending()))
        assertEquals(HostedNext.Fail(HostedSignIn.FAILED), hosted.handle("auraclarus://auth?state=fake-state", pending()))
    }

    @Test
    fun linkingHiccupRetriesOnceWithAFreshAttempt() {
        val hiccup = "auraclarus://auth?error_description=Already+found+an+entry+for+username+Google_1+&error=invalid_request&state=fake-state"
        val retry = assertIs<HostedNext.Open>(hosted.handle(hiccup, pending()))
        assertTrue(retry.launch.pending.retried)
        assertFalse(retry.launch.pending.loggingOut)
        assertNotEquals("fake-state", retry.launch.pending.state)
        assertNotEquals("fake-verifier", retry.launch.pending.verifier)
        assertTrue(retry.launch.url.startsWith("https://$DOMAIN/oauth2/authorize?"))
        // The second time it's an error.
        assertEquals(HostedNext.Fail(HostedSignIn.FAILED), hosted.handle(hiccup, pending(retried = true)))
    }

    @Test
    fun backFromLogoutContinuesToGoogle() {
        val next = assertIs<HostedNext.Open>(hosted.handle("auraclarus://auth/signout", pending(loggingOut = true)))
        assertFalse(next.launch.pending.loggingOut)
        assertTrue(next.launch.url.startsWith("https://$DOMAIN/oauth2/authorize?"))
        assertEquals(HostedNext.Ignore, hosted.handle("auraclarus://auth/signout", pending()))
        assertEquals(HostedNext.Ignore, hosted.handle("auraclarus://auth/signout", null))
    }
}

class HostedTokenExchangeTest {
    @Test
    fun codeIsSwappedForTokensIssuedByTheOAuthClient() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null, config = hostedConfig)
        rig.server.on("/oauth2/token", listOf(tokenAnswer))
        val state = rig.auth.finishHostedSignIn("fake-code", "fake-verifier")
        assertEquals(AuthState.SignedIn(CognitoRig.email), state)

        val call = assertNotNull(rig.server.calls("/oauth2/token").singleOrNull())
        assertEquals(DOMAIN, call.request.url.host)
        assertEquals("https", call.request.url.scheme)
        assertEquals("POST", call.request.method)
        assertEquals("application/x-www-form-urlencoded", call.request.body?.contentType()?.toString())
        assertNull(call.header("Authorization"))
        assertEquals(
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to OAUTH_CLIENT,
                "code" to "fake-code",
                "redirect_uri" to "auraclarus://auth",
                "code_verifier" to "fake-verifier",
            ),
            call.form(),
        )
        val saved = assertNotNull(rig.store.tokens)
        assertEquals("refresh-g", saved.refreshToken)
        assertEquals("access-g", saved.accessToken)
        assertEquals(OAUTH_CLIENT, saved.clientId)
        assertEquals(CognitoRig.now.plusSeconds(3600), saved.expiresAt)
        assertEquals("access-g", rig.auth.accessToken())
    }

    @Test
    fun refusedCodeIsPutIntoWords() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null, config = hostedConfig)
        rig.server.on("/oauth2/token", listOf(CognitoAnswer.Json(400, """{"error":"invalid_grant"}""")))
        val error = assertIs<AuraError.Server>(runCatching { rig.auth.finishHostedSignIn("fake-code", "fake-verifier") }.exceptionOrNull())
        assertEquals("invalid_grant", error.code)
        assertEquals(HostedSignIn.EXPIRED, error.message)
        assertNull(rig.store.tokens)
    }

    @Test
    fun offlineExchange() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null, config = hostedConfig)
        rig.server.on("/oauth2/token", listOf(CognitoAnswer.Fail))
        assertEquals(AuraError.Offline, runCatching { rig.auth.finishHostedSignIn("fake-code", "fake-verifier") }.exceptionOrNull())
    }

    @Test
    fun answerWithoutARefreshTokenIsRefused() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null, config = hostedConfig)
        rig.server.on("/oauth2/token", listOf(CognitoAnswer.Json(200, """{"id_token":"${CognitoRig.idToken}","access_token":"a"}""")))
        assertIs<AuraError.Server>(runCatching { rig.auth.finishHostedSignIn("fake-code", "fake-verifier") }.exceptionOrNull())
        assertNull(rig.store.tokens)
    }

    @Test
    fun withoutAHostedConfigItIsNotSetUp() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        val error = assertIs<AuraError.Server>(runCatching { rig.auth.finishHostedSignIn("fake-code", "fake-verifier") }.exceptionOrNull())
        assertEquals("hosted_not_configured", error.code)
        assertTrue(rig.server.allCalls.isEmpty())
    }
}

class TokenClientTest {
    @Test
    fun refreshUsesTheIssuingClient() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = CognitoRig.tokens(expiresIn = 60).copy(clientId = OAUTH_CLIENT), config = hostedConfig)
        rig.server.on("InitiateAuth", listOf(CognitoRig.refreshAnswer))
        assertEquals("access-2", rig.auth.accessToken())
        val call = assertNotNull(rig.server.calls("InitiateAuth").singleOrNull())
        assertEquals("REFRESH_TOKEN_AUTH", call.string("AuthFlow"))
        assertEquals(OAUTH_CLIENT, call.string("ClientId"))
        assertEquals("refresh-1", call.nested("AuthParameters", "REFRESH_TOKEN"))
        assertEquals(OAUTH_CLIENT, rig.store.tokens?.clientId)
    }

    @Test
    fun oldSessionWithoutAClientRefreshesWithTheDefault() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = CognitoRig.tokens(expiresIn = 60), config = hostedConfig)
        rig.server.on("InitiateAuth", listOf(CognitoRig.refreshAnswer))
        assertEquals("access-2", rig.auth.accessToken())
        assertEquals("stub-client", rig.server.calls("InitiateAuth").single().string("ClientId"))
        // Still "no client recorded", so it keeps meaning the default.
        assertNull(rig.store.tokens?.clientId)
    }

    @Test
    fun emailSignInRecordsItsClient() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null, config = hostedConfig)
        rig.server.on("RespondToAuthChallenge", listOf(CognitoRig.signedInAnswer))
        val step = AuthStep(kind = AuthStep.Kind.signIn, email = CognitoRig.email, session = "otp-session", codeLength = 8)
        rig.auth.finish(step, "12345678")
        assertEquals("stub-client", rig.server.calls("RespondToAuthChallenge").single().string("ClientId"))
        assertEquals("stub-client", rig.store.tokens?.clientId)
    }

    @Test
    fun signOutRevokesWithTheIssuingClient() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = CognitoRig.tokens().copy(clientId = OAUTH_CLIENT), config = hostedConfig)
        rig.server.on("RevokeToken", listOf(CognitoAnswer.Json(200, "{}")))
        rig.auth.restore()
        rig.auth.signOut()
        val revoke = rig.server.calls("RevokeToken").single()
        assertEquals(OAUTH_CLIENT, revoke.string("ClientId"))
        assertNull(rig.store.tokens)
    }

    @Test
    fun savedSessionsWithoutAClientStillRead() {
        val json = Json { ignoreUnknownKeys = true }
        val old = CognitoRig.tokens()
        val text = json.encodeToString(AuthTokens.serializer(), old)
        // Null isn't written, so this is exactly what builds before clientId saved.
        assertFalse("clientId" in text)
        assertNull(json.decodeFromString(AuthTokens.serializer(), text).clientId)
        val google = old.copy(clientId = OAUTH_CLIENT)
        assertEquals(google, json.decodeFromString(AuthTokens.serializer(), json.encodeToString(AuthTokens.serializer(), google)))
        // And the tokens still never print.
        assertFalse("refresh" in google.toString())
    }
}

class StubAuthHostedTest {
    @Test
    fun signedOutStubSignsInWithGoogleAtOnce() = runBlocking<Unit> {
        val auth = StubAuth(startsSignedIn = false)
        assertEquals(AuthState.SignedOut, auth.restore())
        assertNotNull(auth.hostedSignIn)
        assertIs<AuthState.SignedIn>(auth.finishHostedSignIn("stub", "stub"))
        assertIs<AuthState.SignedIn>(auth.restore())
        auth.signOut()
        assertEquals(AuthState.SignedOut, auth.restore())
        assertIs<AuthState.SignedIn>(StubAuth().restore())
    }
}
