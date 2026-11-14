package info.ibtaura.aura.core

import info.ibtaura.aura.core.CognitoRig.Companion.refused
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A port of AuraKitTests/CognitoAuthTests.swift: the same 27 tests with the same expectations.

private const val otpChallenge =
    """{"ChallengeName":"EMAIL_OTP","Session":"otp-session","ChallengeParameters":{"CODE_DELIVERY_DESTINATION":"p***@example.com"}}"""
private const val selectWithOTP =
    """{"ChallengeName":"SELECT_CHALLENGE","Session":"select-session","AvailableChallenges":["EMAIL_OTP","PASSWORD"]}"""
private const val selectWithoutOTP =
    """{"ChallengeName":"SELECT_CHALLENGE","Session":"select-session","AvailableChallenges":["PASSWORD","PASSWORD_SRP"]}"""
private const val delivered =
    """{"UserConfirmed":false,"UserSub":"sub-1","Session":"signup-session","CodeDeliveryDetails":{"Destination":"p***@example.com","DeliveryMedium":"EMAIL","AttributeName":"email"}}"""
private const val deliveredNoSession =
    """{"CodeDeliveryDetails":{"Destination":"p***@example.com","DeliveryMedium":"EMAIL","AttributeName":"email"}}"""
private val alreadyConfirmed = refused("InvalidParameterException", "User is already confirmed.")

private fun json(text: String) = CognitoAnswer.Json(200, text)

/** The error a call threw, if any. */
private suspend fun cognitoCaught(body: suspend () -> Unit): Throwable? = try {
    body()
    null
} catch (e: Throwable) {
    e
}

class CognitoSignInTest {
    private val email = CognitoRig.email

    @Test
    fun callsCognitoInTheRightRegionWithItsHeaders() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        rig.server.on("InitiateAuth", listOf(json(otpChallenge)))
        rig.server.on("ResendConfirmationCode", listOf(alreadyConfirmed))
        rig.auth.requestCode(email)
        val call = assertNotNull(rig.server.calls("InitiateAuth").firstOrNull())
        assertEquals("cognito-idp.us-west-1.amazonaws.com", call.request.url.host)
        assertEquals("POST", call.request.method)
        assertEquals("application/x-amz-json-1.1", call.header("Content-Type"))
        assertEquals("application/x-amz-json-1.1", call.request.body?.contentType()?.toString())
        assertEquals("AWSCognitoIdentityProviderService.InitiateAuth", call.header("X-Amz-Target"))
        assertNull(call.header("Authorization"))
    }

    @Test
    fun existingAccountGetsAnEightDigitSignIn() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        rig.server.on("InitiateAuth", listOf(json(otpChallenge)))
        rig.server.on("ResendConfirmationCode", listOf(alreadyConfirmed))
        val step = rig.auth.requestCode("  Person@Example.COM ")
        assertEquals(AuthStep.Kind.signIn, step.kind)
        assertEquals(email, step.email)
        assertEquals("otp-session", step.session)
        assertEquals(8, step.codeLength)
        assertFalse(step.alsoConfirm)
        assertEquals("p***@example.com", step.destination)
        val first = assertNotNull(rig.server.calls("InitiateAuth").firstOrNull())
        assertEquals("USER_AUTH", first.string("AuthFlow"))
        assertEquals("stub-client", first.string("ClientId"))
        assertEquals(email, first.nested("AuthParameters", "USERNAME"))
        assertEquals("EMAIL_OTP", first.nested("AuthParameters", "PREFERRED_CHALLENGE"))

        rig.server.on("RespondToAuthChallenge", listOf(CognitoRig.signedInAnswer))
        val state = rig.auth.finish(step, "1234 5678")
        assertEquals(AuthState.SignedIn(email), state)
        val answer = assertNotNull(rig.server.calls("RespondToAuthChallenge").firstOrNull())
        assertEquals("EMAIL_OTP", answer.string("ChallengeName"))
        assertEquals("otp-session", answer.string("Session"))
        assertEquals(email, answer.nested("ChallengeResponses", "USERNAME"))
        assertEquals("12345678", answer.nested("ChallengeResponses", "EMAIL_OTP_CODE"))
        assertEquals("refresh-1", rig.store.tokens?.refreshToken)
    }

    @Test
    fun selectChallengeOfferingEmailCodeIsAnswered() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        rig.server.on("InitiateAuth", listOf(json(selectWithOTP)))
        rig.server.on("RespondToAuthChallenge", listOf(json(otpChallenge)))
        rig.server.on("ResendConfirmationCode", listOf(alreadyConfirmed))
        val step = rig.auth.requestCode(email)
        assertEquals(AuthStep.Kind.signIn, step.kind)
        assertEquals("otp-session", step.session)
        val pick = assertNotNull(rig.server.calls("RespondToAuthChallenge").firstOrNull())
        assertEquals("SELECT_CHALLENGE", pick.string("ChallengeName"))
        assertEquals("select-session", pick.string("Session"))
        assertEquals("EMAIL_OTP", pick.nested("ChallengeResponses", "ANSWER"))
    }

    @Test
    fun noEmailCodeOfferedMeansSignUpWithSixDigits() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        rig.server.on("InitiateAuth", listOf(json(selectWithoutOTP)))
        rig.server.on("SignUp", listOf(json(delivered)))
        val step = rig.auth.requestCode(email)
        assertEquals(AuthStep.Kind.confirmSignUp, step.kind)
        assertEquals(6, step.codeLength)
        assertEquals("signup-session", step.session)
        assertEquals("p***@example.com", step.destination)
        assertEquals(0, rig.server.calls("RespondToAuthChallenge").size)
        val signUp = assertNotNull(rig.server.calls("SignUp").firstOrNull())
        assertNull(signUp.body["Password"])
        assertEquals(email, signUp.string("Username"))

        rig.server.on("ConfirmSignUp", listOf(json("""{"Session":"confirm-session"}""")))
        rig.server.on("InitiateAuth", listOf(CognitoRig.signedInAnswer))
        val state = rig.auth.finish(step, "123456")
        assertEquals(AuthState.SignedIn(email), state)
        val confirm = assertNotNull(rig.server.calls("ConfirmSignUp").firstOrNull())
        assertEquals("123456", confirm.string("ConfirmationCode"))
        val signIn = assertNotNull(rig.server.calls("InitiateAuth").lastOrNull())
        assertEquals("confirm-session", signIn.string("Session"))
        assertEquals("USER_AUTH", signIn.string("AuthFlow"))
        assertEquals(email, signIn.nested("AuthParameters", "USERNAME"))
    }

    @Test
    fun challengeNotAvailableMeansSignUp() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        rig.server.on("InitiateAuth", listOf(json(selectWithOTP)))
        rig.server.on(
            "RespondToAuthChallenge",
            listOf(refused("NotAuthorizedException", "The challenge is not available for this user.")),
        )
        rig.server.on("SignUp", listOf(json(delivered)))
        val step = rig.auth.requestCode(email)
        assertEquals(AuthStep.Kind.confirmSignUp, step.kind)
        assertEquals(6, step.codeLength)
    }

    @Test
    fun userNotFoundMeansSignUp() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        rig.server.on("InitiateAuth", listOf(refused("com.amazon#UserNotFoundException", "User does not exist.")))
        rig.server.on("SignUp", listOf(json(delivered)))
        val step = rig.auth.requestCode(email)
        assertEquals(AuthStep.Kind.confirmSignUp, step.kind)
    }

    @Test
    fun existingUnconfirmedAccountGetsANewConfirmationCode() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        rig.server.on("InitiateAuth", listOf(json(selectWithoutOTP)))
        rig.server.on("SignUp", listOf(refused("UsernameExistsException", "User already exists")))
        rig.server.on("ResendConfirmationCode", listOf(json(deliveredNoSession)))
        val step = rig.auth.requestCode(email)
        assertEquals(AuthStep.Kind.confirmSignUp, step.kind)
        assertNull(step.session)
        assertEquals(1, rig.server.calls("ResendConfirmationCode").size)
    }

    @Test
    fun poolInsistingOnAPasswordGetsARandomOne() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        rig.server.on("InitiateAuth", listOf(json(selectWithoutOTP)))
        rig.server.on("SignUp", listOf(refused("InvalidPasswordException", "Password did not conform"), json(delivered)))
        rig.auth.requestCode(email)
        val calls = rig.server.calls("SignUp")
        assertEquals(2, calls.size)
        assertNull(calls[0].body["Password"])
        val password = calls[1].string("Password") ?: ""
        assertTrue(password.startsWith("Cl4!"))
        assertTrue(password.length > 20)
    }

    @Test
    fun bothCodesStepTakesASixDigitConfirmation() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        rig.server.on("InitiateAuth", listOf(json(otpChallenge)))
        rig.server.on("ResendConfirmationCode", listOf(json(deliveredNoSession)))
        val step = rig.auth.requestCode(email)
        assertEquals(AuthStep.Kind.signIn, step.kind)
        assertTrue(step.alsoConfirm)

        rig.server.on("ConfirmSignUp", listOf(json("""{"Session":"confirm-session"}""")))
        rig.server.on("InitiateAuth", listOf(CognitoRig.signedInAnswer))
        val state = rig.auth.finish(step, "123456")
        assertEquals(AuthState.SignedIn(email), state)
        assertEquals(1, rig.server.calls("ConfirmSignUp").size)
        assertEquals(0, rig.server.calls("RespondToAuthChallenge").size)
    }

    @Test
    fun bothCodesStepTakesAnEightDigitSignIn() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        rig.server.on("InitiateAuth", listOf(json(otpChallenge)))
        rig.server.on("ResendConfirmationCode", listOf(json(deliveredNoSession)))
        val step = rig.auth.requestCode(email)
        rig.server.on("RespondToAuthChallenge", listOf(CognitoRig.signedInAnswer))
        val state = rig.auth.finish(step, "12345678")
        assertEquals(AuthState.SignedIn(email), state)
        assertEquals(0, rig.server.calls("ConfirmSignUp").size)
        assertEquals(1, rig.server.calls("RespondToAuthChallenge").size)
    }

    @Test
    fun wrongCodeIsPutIntoWords() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        rig.server.on("RespondToAuthChallenge", listOf(refused("CodeMismatchException", "Invalid code received")))
        val step = AuthStep(kind = AuthStep.Kind.signIn, email = email, session = "otp-session", codeLength = 8)
        val error = cognitoCaught { rig.auth.finish(step, "00000000") }
        assertEquals(
            AuraError.Server(
                code = "CodeMismatchException",
                message = "That code isn't right. Check the email and try again.",
                status = 400,
            ),
            error,
        )
        assertNull(rig.store.tokens)
    }

    @Test
    fun badEmailNeverReachesCognito() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        val error = cognitoCaught { rig.auth.requestCode("not an email") }
        assertEquals(AuraError.Server(code = "invalid_email", message = "Enter a valid email address.", status = 0), error)
        assertEquals(0, rig.server.allCalls.size)
    }

    @Test
    fun offlineDuringSignIn() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        rig.server.on("InitiateAuth", listOf(CognitoAnswer.Fail))
        val error = cognitoCaught { rig.auth.requestCode(email) }
        assertEquals(AuraError.Offline, error)
    }

    @Test
    fun confirmedWithoutASessionNeedsASignInCode() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        rig.server.on("ConfirmSignUp", listOf(json("{}")))
        val step = AuthStep(kind = AuthStep.Kind.confirmSignUp, email = email, session = null, codeLength = 6)
        val error = cognitoCaught { rig.auth.finish(step, "123456") }
        val server = assertIs<AuraError.Server>(error)
        assertEquals("confirmed_without_session", server.code)

        rig.server.on("InitiateAuth", listOf(json(otpChallenge)))
        val next = rig.auth.resend(step)
        assertEquals(AuthStep.Kind.signIn, next.kind)
        assertEquals(8, next.codeLength)
        assertEquals(0, rig.server.calls("ResendConfirmationCode").size)
    }

    @Test
    fun alreadyConfirmedOnResendSendsASignInCode() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        rig.server.on("ResendConfirmationCode", listOf(alreadyConfirmed))
        rig.server.on("InitiateAuth", listOf(json(otpChallenge)))
        val step = AuthStep(kind = AuthStep.Kind.confirmSignUp, email = email, session = null, codeLength = 6)
        val next = rig.auth.resend(step)
        assertEquals(AuthStep.Kind.signIn, next.kind)
    }

    @Test
    fun signInStepWithAnUnknownChallengeIsUnsupported() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        rig.server.on("InitiateAuth", listOf(json("""{"ChallengeName":"MFA_SETUP","Session":"s"}""")))
        val error = cognitoCaught { rig.auth.requestCode(email) }
        val server = assertIs<AuraError.Server>(error)
        assertEquals("unsupported_step", server.code)
        assertTrue(server.message.contains("MFA_SETUP"))
    }
}

// The Swift tests drove the session through LiveAPI.tasks(), which another port owns; these ask CognitoAuth for the
// token directly, which is the call LiveAPI makes first.
class CognitoSessionTest {
    private val email = CognitoRig.email

    @Test
    fun restoreWithASavedSession() = runBlocking<Unit> {
        val rig = CognitoRig()
        val state = rig.auth.restore()
        assertEquals(AuthState.SignedIn(email), state)
        assertEquals(0, rig.server.allCalls.size)
    }

    @Test
    fun restoreWithNothingSaved() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = null)
        assertEquals(AuthState.SignedOut, rig.auth.restore())
    }

    @Test
    fun restoreIgnoresTokensWithoutAnIdentity() = runBlocking<Unit> {
        val unusable = AuthTokens(idToken = "junk", accessToken = "a", refreshToken = "r", expiresAt = CognitoRig.now.plusSeconds(3600))
        val rig = CognitoRig(tokens = unusable)
        assertEquals(AuthState.SignedOut, rig.auth.restore())
    }

    @Test
    fun signOutRevokesAndClears() = runBlocking<Unit> {
        val rig = CognitoRig()
        rig.server.on("RevokeToken", listOf(json("{}")))
        rig.auth.signOut()
        assertNull(rig.store.tokens)
        val revoke = assertNotNull(rig.server.calls("RevokeToken").firstOrNull())
        assertEquals("refresh-1", revoke.string("Token"))
        assertEquals("stub-client", revoke.string("ClientId"))
    }

    @Test
    fun signOutWorksOffline() = runBlocking<Unit> {
        val rig = CognitoRig()
        rig.server.on("RevokeToken", listOf(CognitoAnswer.Fail))
        rig.auth.signOut()
        assertNull(rig.store.tokens)
        val error = cognitoCaught { rig.auth.accessToken() }
        assertEquals(AuraError.SignedOut, error)
    }

    @Test
    fun signOutDuringARefreshDoesNotBringTheSessionBack() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = CognitoRig.tokens(expiresIn = 10))
        rig.server.on("InitiateAuth", listOf(CognitoRig.refreshAnswer), delayMillis = 500)
        rig.server.on("RevokeToken", listOf(json("{}")))
        val call = async(Dispatchers.Default) { cognitoCaught { rig.auth.accessToken() } }
        // Long enough for the refresh to be in flight, shorter than its answer.
        delay(30)
        rig.auth.signOut()
        val error = call.await()
        assertNotNull(error, "The call should have failed")
        assertNull(rig.store.tokens)
    }

    @Test
    fun secondRestoreLeavesARunningRefreshAlone() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = CognitoRig.tokens(expiresIn = 10))
        rig.server.on("InitiateAuth", listOf(CognitoRig.refreshAnswer), delayMillis = 200)
        val call = async(Dispatchers.Default) { rig.auth.accessToken() }
        // Long enough for the refresh to be in flight, shorter than its answer. If it hasn't started, this still passes.
        delay(30)
        val state = rig.auth.restore()
        assertEquals(AuthState.SignedIn(email), state)
        assertEquals("access-2", call.await())
        assertEquals(1, rig.server.calls("InitiateAuth").size)
        assertEquals("access-2", rig.store.tokens?.accessToken)
    }

    @Test
    fun restoreAfterSignOutStaysSignedOut() = runBlocking<Unit> {
        val rig = CognitoRig()
        rig.server.on("RevokeToken", listOf(json("{}")))
        rig.auth.signOut()
        assertEquals(AuthState.SignedOut, rig.auth.restore())
    }

    @Test
    fun tokenRejectedOnlyEndsTheSessionForTheHeldToken() = runBlocking<Unit> {
        val rig = CognitoRig()
        rig.auth.accessToken()
        assertFalse(rig.auth.tokenRejected("access-0"))
        assertNotNull(rig.store.tokens)
        assertTrue(rig.auth.tokenRejected("access-1"))
        assertNull(rig.store.tokens)
    }

    @Test
    fun otherRefusalKeepsTheSession() = runBlocking<Unit> {
        val rig = CognitoRig(tokens = CognitoRig.tokens(expiresIn = 10))
        rig.server.on("InitiateAuth", listOf(refused("TooManyRequestsException", "Rate exceeded")))
        val error = cognitoCaught { rig.auth.accessToken() }
        assertEquals(
            AuraError.Server(
                code = "TooManyRequestsException",
                message = "Too many attempts. Wait a minute, then try again.",
                status = 400,
            ),
            error,
        )
        assertNotNull(rig.store.tokens)
    }

    @Test
    fun tokenStateFromClaims() {
        val access = cognitoTestJWT(mapOf("exp" to CognitoRig.now.epochSecond + 600))
        val fromToken = AuthTokens.issued(accessToken = access, idToken = CognitoRig.idToken, refreshToken = "r", expiresIn = 3600, now = CognitoRig.now)
        assertEquals(CognitoRig.now.plusSeconds(600), fromToken.expiresAt)
        assertEquals("sub-1", fromToken.sub)
        assertEquals(email, fromToken.email)
        val fromAnswer = AuthTokens.issued(accessToken = "opaque", idToken = "junk", refreshToken = "r", expiresIn = 60, now = CognitoRig.now)
        assertEquals(CognitoRig.now.plusSeconds(60), fromAnswer.expiresAt)
        assertEquals("", fromAnswer.sub)
        assertNull(fromAnswer.email)
    }
}
