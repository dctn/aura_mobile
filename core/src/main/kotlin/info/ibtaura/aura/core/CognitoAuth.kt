package info.ibtaura.aura.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// A port of AuraKit/CognitoAuth.swift: signing in with an emailed code, with no AWS SDK.
// Someone with an account gets an 8-digit sign-in code (InitiateAuth USER_AUTH, EMAIL_OTP, RespondToAuthChallenge).
// Someone new is signed up on the spot with no password and gets a 6-digit code (SignUp, ConfirmSignUp), whose session
// signs them in. The pool hides account status, so see aura_mobile/cognito-sign-in.md before changing a branch here.

/** Cognito's own refusal, before it's put into words for a person. */
internal class CognitoRefusal(kind: String, val detail: String, val status: Int = 400) : Exception(kind.substringAfterLast('#')) {
    val kind: String = kind.substringAfterLast('#')

    val publicError: AuraError
        get() = AuraError.Server(code = kind, message = CognitoWords.describe(kind, detail), status = status)
}

internal object CognitoWords {
    const val SIGN_IN_CODE_LENGTH = 8
    const val CONFIRM_CODE_LENGTH = 6

    /** The refusals that mean a refresh token can never work again. Anything else keeps the session. */
    val sessionEnding: Set<String> = setOf("NotAuthorizedException", "UserNotFoundException")

    /** The sentence a person reads for a refusal. */
    fun describe(kind: String, message: String): String {
        val lower = message.lowercase()
        when (kind) {
            "CodeMismatchException" -> return "That code isn't right. Check the email and try again."
            "ExpiredCodeException" -> return "That code has expired. Send a new one."
            "CodeDeliveryFailureException" -> return "The code email couldn't be sent. Check the address and try again."
            "LimitExceededException", "TooManyRequestsException", "TooManyFailedAttemptsException" ->
                return "Too many attempts. Wait a minute, then try again."
            "NotAuthorizedException" -> {
                if (lower.contains("session")) return "The sign-in took too long. Send a new code."
                if (lower.contains("disabled")) return "This account has been turned off."
                if (lower.contains("refresh token") || lower.contains("revoked")) return "Your session has ended. Sign in again."
                if (lower.contains("attempts")) return "Too many attempts. Wait a minute, then try again."
            }
            "InvalidParameterException" -> if (lower.contains("email")) return "Enter a valid email address."
        }
        return message.ifEmpty { "Sign-in didn't work. Try again." }
    }

    fun unsupported(step: String): AuraError = AuraError.Server(
        code = "unsupported_step",
        message = "Sign-in needs a step this version of the app doesn't support ($step). Update the app.",
        status = 0,
    )

    fun looksLikeEmail(email: String): Boolean {
        val parts = email.split("@")
        return parts.size == 2 && parts[0].isNotEmpty() && parts[1].contains(".") && !email.contains(" ")
    }

    /** Cognito's two ways of saying so. */
    fun isAlreadyConfirmed(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("already confirmed") || lower.contains("current status is confirmed")
    }

    private val random = SecureRandom()

    /** For a pool that insists on a password at sign-up: one nobody knows or needs, since every sign-in uses an emailed code. */
    fun randomPassword(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return "Cl4!" + Base64.getEncoder().encodeToString(bytes)
    }
}

/** A string field, or null when it's missing or not a string. */
private fun JsonObject.str(name: String): String? {
    val value = this[name] as? JsonPrimitive ?: return null
    return if (value.isString) value.content else null
}

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.int(name: String): Int? {
    val value = this[name] as? JsonPrimitive ?: return null
    return if (value.isString) null else value.intOrNull
}

private val amzJson = "application/x-amz-json-1.1".toMediaType()

/** One JSON POST per call. The app client is public, so there are no AWS credentials and no client secret. */
internal class CognitoClient(
    val endpoint: HttpUrl,
    val clientId: String,
    private val http: OkHttpClient,
    val now: AuraNow,
) {
    /** Throws `CognitoRefusal` for a refusal and `AuraError.Offline` for no answer. */
    suspend fun call(target: String, body: JsonObject, timeoutSeconds: Long = 20): JsonObject {
        val request = Request.Builder()
            .url(endpoint)
            // A ByteArray body keeps the Content-Type exactly as Cognito wants it, with no "; charset=utf-8".
            .post(body.toString().toByteArray(Charsets.UTF_8).toRequestBody(amzJson))
            .header("Content-Type", "application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService.$target")
            .build()
        val call = http.newCall(request)
        call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
        val (status, text) = try {
            call.await()
        } catch (e: IOException) {
            // A cancelled caller isn't the same as being offline.
            currentCoroutineContext().ensureActive()
            throw AuraError.Offline
        }
        val json = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        if (status !in 200 until 300) {
            val kind = json.str("__type") ?: "HTTP$status"
            val message = json.str("message") ?: json.str("Message") ?: ""
            throw CognitoRefusal(kind, message, status)
        }
        return json
    }

    /** REFRESH_TOKEN_AUTH. Throws `AuraError.SignedOut` only when Cognito refused this token or its user. */
    suspend fun refresh(refreshToken: String): AuthTokens {
        val body = buildJsonObject {
            put("AuthFlow", "REFRESH_TOKEN_AUTH")
            put("ClientId", clientId)
            putJsonObject("AuthParameters") { put("REFRESH_TOKEN", refreshToken) }
        }
        val result = try {
            call("InitiateAuth", body)
        } catch (refused: CognitoRefusal) {
            if (refused.kind in CognitoWords.sessionEnding) throw AuraError.SignedOut
            throw refused.publicError
        }
        // A 200 without tokens isn't Cognito refusing the token, so it must not end the session.
        val auth = result.obj("AuthenticationResult")
            ?: throw CognitoWords.unsupported(result.str("ChallengeName") ?: "REFRESH_WITHOUT_TOKENS")
        val tokens = AuthTokens.issued(
            accessToken = auth.str("AccessToken") ?: "",
            idToken = auth.str("IdToken") ?: "",
            refreshToken = auth.str("RefreshToken") ?: refreshToken,
            expiresIn = auth.int("ExpiresIn"),
            now = now(),
        )
        if (tokens.sub.isEmpty()) throw CognitoWords.unsupported("TOKENS_WITHOUT_IDENTITY")
        return tokens
    }

    /** Tokens from a sign-in answer. */
    fun tokens(result: JsonObject, step: String): AuthTokens {
        val auth = result.obj("AuthenticationResult")
        val refreshToken = auth?.str("RefreshToken")
        if (auth == null || refreshToken.isNullOrEmpty()) {
            throw CognitoWords.unsupported(result.str("ChallengeName") ?: step)
        }
        val tokens = AuthTokens.issued(
            accessToken = auth.str("AccessToken") ?: "",
            idToken = auth.str("IdToken") ?: "",
            refreshToken = refreshToken,
            expiresIn = auth.int("ExpiresIn"),
            now = now(),
        )
        if (tokens.sub.isEmpty()) throw CognitoWords.unsupported("TOKENS_WITHOUT_IDENTITY")
        return tokens
    }
}

/** Runs the call; cancelling the coroutine cancels it. Answers with the status and the body text. */
private suspend fun Call.await(): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val answer = try {
                response.use { it.code to it.body.string() }
            } catch (e: IOException) {
                continuation.resumeWithException(e)
                return
            }
            continuation.resume(answer)
        }
    })
}

/** Holds the tokens and keeps them fresh. However many ask at once, one refresh runs. */
internal class AuthSession(private val client: CognitoClient, private val store: TokenStore) {
    companion object {
        /** Refresh when less than this many seconds are left. */
        const val REFRESH_MARGIN_SECONDS = 120L
    }

    private val mutex = Mutex()
    /** Refreshes run here, so a caller's cancellation doesn't stop one that others wait on. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tokens: AuthTokens? = null
    private var loaded = false
    /** Bumped when a session begins or ends, so a refresh in flight across a sign-out can't bring the old account back. */
    private var epoch = 0
    private var refreshing: Deferred<AuthTokens>? = null
    /** Addresses confirmed here with no session to sign in with: a sign-in code is what completes them. */
    private val confirmedWithoutSession = mutableSetOf<String>()

    suspend fun restore(): AuthTokens? = mutex.withLock { restoreLocked() }

    suspend fun begin(fresh: AuthTokens) = mutex.withLock {
        tokens = fresh
        loaded = true
        startEpoch()
        // If saving fails the session still works until the app quits.
        runCatching { store.save(fresh) }
        Unit
    }

    /** Ends the session here and returns the tokens that were held, for a revoke. */
    suspend fun clear(): AuthTokens? = mutex.withLock { clearLocked() }

    suspend fun accessToken(): String {
        val running = mutex.withLock {
            loadIfNeeded()
            val current = tokens ?: throw AuraError.SignedOut
            if (Duration.between(client.now(), current.expiresAt).toMillis() >= REFRESH_MARGIN_SECONDS * 1000) {
                return current.accessToken
            }
            refreshLocked()
        }
        return running.await().accessToken
    }

    suspend fun refreshedToken(rejected: String): String {
        val running = mutex.withLock {
            loadIfNeeded()
            val current = tokens ?: throw AuraError.SignedOut
            // Someone else already replaced the token the API refused.
            if (current.accessToken != rejected) return current.accessToken
            refreshLocked()
        }
        return running.await().accessToken
    }

    /** True when the session is over, false when a newer token has already replaced the refused one. */
    suspend fun tokenRejected(token: String): Boolean = mutex.withLock {
        val held = tokens ?: return@withLock true
        if (held.accessToken != token) return@withLock false
        clearLocked()
        true
    }

    suspend fun noteConfirmedWithoutSession(email: String) = mutex.withLock {
        confirmedWithoutSession.add(email)
        Unit
    }

    suspend fun takeConfirmedWithoutSession(email: String): Boolean = mutex.withLock {
        confirmedWithoutSession.remove(email)
    }

    private fun restoreLocked(): AuthTokens? {
        // A call can beat the first screen here on a cold launch. A new epoch now would fail the refresh that call started.
        tokens?.let { return it }
        val saved = runCatching { store.load() }.getOrNull()?.takeIf { it.sub.isNotEmpty() }
        tokens = saved
        loaded = true
        startEpoch()
        return saved
    }

    private fun clearLocked(): AuthTokens? {
        loadIfNeeded()
        val held = tokens
        tokens = null
        startEpoch()
        runCatching { store.clear() }
        return held
    }

    private fun loadIfNeeded() {
        if (!loaded) restoreLocked()
    }

    private fun startEpoch() {
        epoch += 1
        refreshing = null
    }

    /** Every caller waits on the same refresh. Call with the lock held; await the answer after letting it go. */
    private fun refreshLocked(): Deferred<AuthTokens> {
        refreshing?.let { return it }
        val current = tokens ?: throw AuraError.SignedOut
        val started = epoch
        val task = scope.async { runRefresh(current, started) }
        refreshing = task
        return task
    }

    private suspend fun runRefresh(current: AuthTokens, started: Int): AuthTokens {
        try {
            val fresh = client.refresh(current.refreshToken)
            return mutex.withLock {
                // Signed out, or in again, while this ran: saving these would bring back the account that left.
                if (epoch != started) throw AuraError.SignedOut
                tokens = fresh
                runCatching { store.save(fresh) }
                refreshing = null
                fresh
            }
        } catch (signedOut: AuraError.SignedOut) {
            mutex.withLock {
                if (epoch == started) clearLocked()
            }
            throw AuraError.SignedOut
        } catch (other: Throwable) {
            mutex.withLock {
                if (epoch == started) refreshing = null
            }
            throw other
        }
    }
}

/**
 * Sign-in for the app: Cognito's emailed codes over raw JSON POSTs, and the session the API client draws tokens from.
 * Signing out clears [store]; a refresh Cognito refuses ends the session with `AuraError.SignedOut`.
 */
class CognitoAuth internal constructor(
    config: AppConfig,
    store: TokenStore,
    http: OkHttpClient,
    now: AuraNow,
    endpoint: HttpUrl,
) : AuthService, AccessTokenSource {

    constructor(
        config: AppConfig,
        store: TokenStore,
        http: OkHttpClient = auraHttpClient(),
        now: AuraNow = realNow,
    ) : this(config, store, http, now, regionalEndpoint(config.region))

    private val client = CognitoClient(endpoint = endpoint, clientId = config.clientId, http = http, now = now)
    private val session = AuthSession(client, store)

    internal companion object {
        fun regionalEndpoint(region: String): HttpUrl =
            "https://cognito-idp.$region.amazonaws.com/".toHttpUrlOrNull() ?: "https://cognito-idp.invalid/".toHttpUrl()
    }

    // AuthService

    override suspend fun restore(): AuthState {
        val tokens = session.restore() ?: return AuthState.SignedOut
        return AuthState.SignedIn(tokens.email)
    }

    /** Sends a code, creating the account first if there is none. */
    override suspend fun requestCode(email: String): AuthStep {
        val address = email.trim().lowercase()
        if (!CognitoWords.looksLikeEmail(address)) {
            throw AuraError.Server(code = "invalid_email", message = "Enter a valid email address.", status = 0)
        }
        try {
            return start(address)
        } catch (refused: CognitoRefusal) {
            throw refused.publicError
        }
    }

    override suspend fun finish(step: AuthStep, code: String): AuthState {
        val digits = code.filter { it in '0'..'9' }
        // A 6-digit code on a both-codes step means this account was never confirmed.
        val isConfirmation = step.kind == AuthStep.Kind.confirmSignUp ||
            (step.alsoConfirm && digits.length == CognitoWords.CONFIRM_CODE_LENGTH)
        try {
            val tokens = if (isConfirmation) {
                confirmAndSignIn(step.email, digits)
            } else {
                answerChallenge(step, digits)
            }
            session.begin(tokens)
            return AuthState.SignedIn(tokens.email)
        } catch (refused: CognitoRefusal) {
            throw refused.publicError
        }
    }

    override suspend fun resend(step: AuthStep): AuthStep {
        try {
            return when (step.kind) {
                AuthStep.Kind.signIn -> withConfirmation(requestSignInCode(step.email))
                AuthStep.Kind.confirmSignUp -> {
                    // A confirmed account gets a fake success from ResendConfirmationCode and no email.
                    if (session.takeConfirmedWithoutSession(step.email)) {
                        requestSignInCode(step.email)
                    } else {
                        resendConfirmation(step.email)
                    }
                }
            }
        } catch (refused: CognitoRefusal) {
            throw refused.publicError
        }
    }

    /** Clears the session first, then revokes as a best effort: signing out has to work offline. */
    override suspend fun signOut() {
        val held = session.clear() ?: return
        val body = buildJsonObject {
            put("ClientId", client.clientId)
            put("Token", held.refreshToken)
        }
        try {
            client.call("RevokeToken", body, timeoutSeconds = 5)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Already signed out here; the refresh token simply lives out its time.
        }
    }

    // AccessTokenSource

    override suspend fun accessToken(): String = session.accessToken()

    override suspend fun refreshedToken(rejected: String): String = session.refreshedToken(rejected)

    override suspend fun tokenRejected(token: String): Boolean = session.tokenRejected(token)

    // The steps

    private suspend fun start(address: String): AuthStep {
        try {
            return withConfirmation(requestSignInCode(address))
        } catch (refused: CognitoRefusal) {
            when (refused.kind) {
                "UserNotFoundException" -> {
                    try {
                        return signUp(address)
                    } catch (exists: CognitoRefusal) {
                        // Hidden earlier: the account exists but was never confirmed.
                        if (exists.kind == "UsernameExistsException") return resendConfirmation(address)
                        throw exists
                    }
                }
                "UserNotConfirmedException" -> return resendConfirmation(address)
                else -> throw refused
            }
        }
    }

    private suspend fun requestSignInCode(email: String): AuthStep {
        val first = buildJsonObject {
            put("AuthFlow", "USER_AUTH")
            put("ClientId", client.clientId)
            putJsonObject("AuthParameters") {
                put("USERNAME", email)
                put("PREFERRED_CHALLENGE", "EMAIL_OTP")
            }
        }
        var result = client.call("InitiateAuth", first)
        val selectSession = result.str("Session")
        if (result.str("ChallengeName") == "SELECT_CHALLENGE" && !selectSession.isNullOrEmpty()) {
            // The pool hides unknown emails: it offers password challenges only, and picking EMAIL_OTP is then refused.
            val offered = (result["AvailableChallenges"] as? JsonArray)
                ?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            if (offered != null && offered.all { it != null } && "EMAIL_OTP" !in offered) {
                throw CognitoRefusal("UserNotFoundException", "No account can take an email code")
            }
            val pick = buildJsonObject {
                put("ChallengeName", "SELECT_CHALLENGE")
                put("ClientId", client.clientId)
                put("Session", selectSession)
                putJsonObject("ChallengeResponses") {
                    put("USERNAME", email)
                    put("ANSWER", "EMAIL_OTP")
                }
            }
            result = try {
                client.call("RespondToAuthChallenge", pick)
            } catch (refused: CognitoRefusal) {
                if (refused.detail.lowercase().contains("challenge is not available")) {
                    throw CognitoRefusal("UserNotFoundException", refused.detail)
                }
                throw refused
            }
        }
        val otpSession = result.str("Session")
        if (result.str("ChallengeName") != "EMAIL_OTP" || otpSession.isNullOrEmpty()) {
            throw CognitoWords.unsupported(result.str("ChallengeName") ?: "NO_CHALLENGE")
        }
        val destination = result.obj("ChallengeParameters")?.str("CODE_DELIVERY_DESTINATION") ?: ""
        return AuthStep(
            kind = AuthStep.Kind.signIn,
            email = email,
            session = otpSession,
            codeLength = CognitoWords.SIGN_IN_CODE_LENGTH,
            alsoConfirm = false,
            destination = destination.ifEmpty { email },
        )
    }

    /**
     * Also asks for a confirmation code. A confirmed account gets a fake success and no email; for an unconfirmed one
     * the sign-in challenge was the fake. Exactly one real email goes out.
     */
    private suspend fun withConfirmation(step: AuthStep): AuthStep {
        val body = buildJsonObject {
            put("ClientId", client.clientId)
            put("Username", step.email)
        }
        try {
            client.call("ResendConfirmationCode", body)
        } catch (_: CognitoRefusal) {
            return step
        }
        return step.copy(alsoConfirm = true)
    }

    private suspend fun signUp(email: String): AuthStep {
        fun body(password: String?) = buildJsonObject {
            put("ClientId", client.clientId)
            put("Username", email)
            putJsonArray("UserAttributes") {
                addJsonObject {
                    put("Name", "email")
                    put("Value", email)
                }
            }
            if (password != null) put("Password", password)
        }
        val result = try {
            client.call("SignUp", body(null))
        } catch (refused: CognitoRefusal) {
            if (refused.kind == "InvalidPasswordException" || refused.detail.lowercase().contains("password")) {
                client.call("SignUp", body(CognitoWords.randomPassword()))
            } else {
                throw refused
            }
        }
        val signUpSession = result.str("Session")?.takeIf { it.isNotEmpty() }
        return confirmStep(email, signUpSession, result)
    }

    private suspend fun resendConfirmation(email: String): AuthStep {
        val body = buildJsonObject {
            put("ClientId", client.clientId)
            put("Username", email)
        }
        val result = try {
            client.call("ResendConfirmationCode", body)
        } catch (refused: CognitoRefusal) {
            // Confirmed, but the sign-in after it never happened: only a sign-in code finishes that.
            if (CognitoWords.isAlreadyConfirmed(refused.detail)) return requestSignInCode(email)
            throw refused
        }
        return confirmStep(email, null, result)
    }

    private fun confirmStep(email: String, session: String?, result: JsonObject): AuthStep {
        val destination = result.obj("CodeDeliveryDetails")?.str("Destination") ?: ""
        return AuthStep(
            kind = AuthStep.Kind.confirmSignUp,
            email = email,
            session = session,
            codeLength = CognitoWords.CONFIRM_CODE_LENGTH,
            alsoConfirm = false,
            destination = destination.ifEmpty { email },
        )
    }

    private suspend fun answerChallenge(step: AuthStep, digits: String): AuthTokens {
        val body = buildJsonObject {
            put("ChallengeName", "EMAIL_OTP")
            put("ClientId", client.clientId)
            put("Session", step.session ?: "")
            putJsonObject("ChallengeResponses") {
                put("USERNAME", step.email)
                put("EMAIL_OTP_CODE", digits)
            }
        }
        val result = client.call("RespondToAuthChallenge", body)
        return client.tokens(result, "EMAIL_OTP")
    }

    private suspend fun confirmAndSignIn(email: String, digits: String): AuthTokens {
        val confirmBody = buildJsonObject {
            put("ClientId", client.clientId)
            put("Username", email)
            put("ConfirmationCode", digits)
        }
        val confirmed = try {
            client.call("ConfirmSignUp", confirmBody)
        } catch (refused: CognitoRefusal) {
            if (CognitoWords.isAlreadyConfirmed(refused.detail)) throw signInCodeNeeded(email)
            throw refused
        }
        val confirmedSession = confirmed.str("Session")
        if (confirmedSession.isNullOrEmpty()) throw signInCodeNeeded(email)
        val body = buildJsonObject {
            put("AuthFlow", "USER_AUTH")
            put("ClientId", client.clientId)
            put("Session", confirmedSession)
            putJsonObject("AuthParameters") { put("USERNAME", email) }
        }
        val result = client.call("InitiateAuth", body)
        return client.tokens(result, "SIGN_UP_SESSION")
    }

    /** The account is confirmed but no session came back, so `resend` sends a sign-in code next. */
    private suspend fun signInCodeNeeded(email: String): AuraError {
        session.noteConfirmedWithoutSession(email)
        return AuraError.Server(
            code = "confirmed_without_session",
            message = "Your account is confirmed. Send a new code to sign in.",
            status = 0,
        )
    }
}
