package info.ibtaura.aura.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

// "Continue with Google" through Cognito's hosted sign-in: OAuth 2.0 authorization code + PKCE (RFC 7636), no client
// secret. The app opens the authorize URL in a browser tab; Cognito sends the person to Google and back, then redirects
// to auraclarus://auth with a code, which the app swaps for the usual Cognito tokens. The app never talks to Google.
// Those tokens come from the OAuth app client, so they record it and refresh with it (AuthTokens.clientId).
// Never log a code, verifier, state or token.

/** PKCE's verifier and S256 challenge, and the `state` that ties a redirect to the attempt that started it. */
object Pkce {
    private val random = SecureRandom()
    private val base64Url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** 64 random bytes as base64url with no padding: 86 characters from [A-Za-z0-9-_], inside RFC 7636's 43 to 128. */
    fun verifier(source: SecureRandom = random): String = randomText(source, 64)

    /** BASE64URL(SHA-256(ASCII(verifier))) with no padding (RFC 7636 section 4.2, method S256). */
    fun challenge(verifier: String): String =
        base64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** 32 random bytes as base64url: 43 characters nobody can guess. */
    fun state(source: SecureRandom = random): String = randomText(source, 32)

    private fun randomText(source: SecureRandom, bytes: Int): String {
        val data = ByteArray(bytes)
        source.nextBytes(data)
        return base64Url.encodeToString(data)
    }
}

/** What has to survive the trip through the browser (the app keeps it in memory and in private preferences). */
data class HostedPending(
    val verifier: String,
    val state: String,
    /** The one automatic retry after "Already found an entry for username" has been used. */
    val retried: Boolean = false,
    /** The browser was sent to /logout first; its redirect continues to the authorize URL. */
    val loggingOut: Boolean = false,
)

/** A URL to open in the browser tab, and the attempt to keep until its redirect comes back. */
data class HostedLaunch(val url: String, val pending: HostedPending)

/** What to do with a redirect. */
sealed class HostedNext {
    /** Swap the code for tokens (`AuthService.finishHostedSignIn`). */
    data class Exchange(val code: String, val verifier: String) : HostedNext()

    /** Open this in the browser tab next: the authorize URL after a logout, or the one automatic retry. */
    data class Open(val launch: HostedLaunch) : HostedNext()

    /** The person backed out: no message. */
    data object Cancelled : HostedNext()

    /** Show this sentence on the sign-in screen. */
    data class Fail(val message: String) : HostedNext()

    /** Not a redirect for this sign-in. */
    data object Ignore : HostedNext()
}

/** A redirect to auraclarus://auth, read: its path ("" or "/signout") and its query (and fragment) parameters. */
data class HostedRedirect(val path: String, val params: Map<String, String>) {
    val code: String? get() = params["code"]?.takeIf { it.isNotEmpty() }
    val state: String? get() = params["state"]
    val error: String? get() = params["error"]?.takeIf { it.isNotEmpty() }
    val errorDescription: String? get() = params["error_description"]
    val isSignOut: Boolean get() = path.trimEnd('/') == "/signout"

    companion object {
        private const val PREFIX = "auraclarus://auth"

        /** Null when `uri` isn't auraclarus://auth or below it. */
        fun parse(uri: String): HostedRedirect? {
            if (!uri.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)) return null
            val rest = uri.substring(PREFIX.length)
            if (rest.isNotEmpty() && rest[0] !in "/?#") return null
            val path = rest.substringBefore('?').substringBefore('#')
            val query = if ('?' in rest) rest.substringAfter('?').substringBefore('#') else ""
            val fragment = if ('#' in rest) rest.substringAfter('#') else ""
            val params = LinkedHashMap<String, String>()
            for (part in (query.split('&') + fragment.split('&'))) {
                if (part.isEmpty()) continue
                val name = decode(part.substringBefore('=')) ?: continue
                val value = decode(if ('=' in part) part.substringAfter('=') else "") ?: continue
                // The first one wins: a fragment can't override the query.
                if (name.isNotEmpty() && name !in params) params[name] = value
            }
            return HostedRedirect(path, params)
        }

        private fun decode(text: String): String? = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrNull()
    }
}

/**
 * The hosted sign-in's URLs on one Cognito domain, for one public app client, and what each redirect means.
 * The domain and client come from aura-dev.json (`cognitoDomain`, `oauthClientId`).
 */
data class HostedSignIn(
    val domain: String,
    val clientId: String,
    val redirectUri: String = REDIRECT_URI,
    val signOutUri: String = SIGN_OUT_URI,
) {
    companion object {
        const val REDIRECT_URI = "auraclarus://auth"
        const val SIGN_OUT_URI = "auraclarus://auth/signout"
        const val SCOPES = "openid email profile"

        /** The hosted sign-in the config describes, or null when it names no usable Cognito domain or OAuth client. */
        fun from(config: AppConfig): HostedSignIn? {
            val host = config.cognitoDomain?.trim()?.removePrefix("https://")?.trimEnd('/').orEmpty()
            val client = config.oauthClientId?.trim().orEmpty()
            if (host.isEmpty() || client.isEmpty() || host.any { it == '/' || it == '?' || it == '#' || it == '@' }) return null
            if ("https://$host/".toHttpUrlOrNull()?.host != host.lowercase()) return null
            return HostedSignIn(domain = host.lowercase(), clientId = client)
        }

        /** Cognito's words for a first Google sign-in that links to an existing account; trying once more works. */
        fun isLinkingHiccup(description: String?): Boolean =
            description?.contains("already found an entry for username", ignoreCase = true) == true

        internal fun notConfigured(): AuraError =
            AuraError.Server(code = "hosted_not_configured", message = "Google sign-in isn't set up in this build.", status = 0)

        internal const val MISMATCH = "That sign-in didn't come from this app. Try again."
        internal const val EXPIRED = "That sign-in has expired. Try again."
        internal const val FAILED = "Google sign-in didn't work. Try again."

        private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")
    }

    private fun query(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }

    /** Straight to Google (identity_provider=Google), with the S256 challenge of the verifier kept for the exchange. */
    fun authorizeUrl(state: String, challenge: String): String = "https://$domain/oauth2/authorize?" + query(
        "identity_provider" to "Google",
        "response_type" to "code",
        "client_id" to clientId,
        "redirect_uri" to redirectUri,
        "scope" to SCOPES,
        "state" to state,
        "code_challenge" to challenge,
        "code_challenge_method" to "S256",
    )

    /**
     * Ends Cognito's own browser session (its cookie would sign the same Google account straight back in), then
     * redirects to [signOutUri], which must be one of the app client's sign-out URLs.
     */
    fun logoutUrl(): String = "https://$domain/logout?" + query("client_id" to clientId, "logout_uri" to signOutUri)

    val tokenUrl: String get() = "https://$domain/oauth2/token"

    /** A new attempt with a fresh verifier and state. With [logOutFirst] the browser visits /logout before Google. */
    fun start(logOutFirst: Boolean = false, retried: Boolean = false, random: SecureRandom? = null): HostedLaunch {
        val verifier = if (random != null) Pkce.verifier(random) else Pkce.verifier()
        val state = if (random != null) Pkce.state(random) else Pkce.state()
        val pending = HostedPending(verifier = verifier, state = state, retried = retried, loggingOut = logOutFirst)
        val url = if (logOutFirst) logoutUrl() else authorizeUrl(state, Pkce.challenge(verifier))
        return HostedLaunch(url, pending)
    }

    /** What a redirect means for the attempt in [pending] (null when none is under way). */
    fun handle(uri: String, pending: HostedPending?, random: SecureRandom? = null): HostedNext {
        val redirect = HostedRedirect.parse(uri) ?: return HostedNext.Ignore
        if (redirect.isSignOut) {
            // Back from /logout: now to Google, keeping whether the retry was used.
            if (pending == null || !pending.loggingOut) return HostedNext.Ignore
            return HostedNext.Open(start(logOutFirst = false, retried = pending.retried, random = random))
        }
        if (pending == null) return HostedNext.Fail(EXPIRED)
        val state = redirect.state
        val error = redirect.error
        if (error != null) {
            if (state != null && state != pending.state) return HostedNext.Fail(MISMATCH)
            if (isLinkingHiccup(redirect.errorDescription) && !pending.retried) {
                return HostedNext.Open(start(logOutFirst = false, retried = true, random = random))
            }
            if (error == "access_denied") return HostedNext.Cancelled
            return HostedNext.Fail(FAILED)
        }
        val code = redirect.code ?: return HostedNext.Fail(FAILED)
        if (state != pending.state) return HostedNext.Fail(MISMATCH)
        return HostedNext.Exchange(code, pending.verifier)
    }
}

/** POST /oauth2/token: the code and its verifier for Cognito tokens, recorded as issued by the OAuth client. */
internal class HostedTokenClient(
    private val signIn: HostedSignIn,
    private val http: OkHttpClient,
    private val now: AuraNow,
) {
    /** Throws `AuraError.Offline` for no answer and `AuraError.Server` for a refusal. */
    suspend fun exchange(code: String, verifier: String): AuthTokens {
        // FormBody sends exactly "application/x-www-form-urlencoded", as the token endpoint wants.
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", signIn.clientId)
            .add("code", code)
            .add("redirect_uri", signIn.redirectUri)
            .add("code_verifier", verifier)
            .build()
        val request = Request.Builder().url(signIn.tokenUrl).post(body).build()
        val call = http.newCall(request)
        call.timeout().timeout(20, TimeUnit.SECONDS)
        val (status, text) = try {
            call.await()
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw AuraError.Offline
        }
        val json = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        if (status !in 200 until 300) {
            val error = json.text("error") ?: "HTTP$status"
            val message = if (error == "invalid_grant") HostedSignIn.EXPIRED else HostedSignIn.FAILED
            throw AuraError.Server(code = error, message = message, status = status)
        }
        val refreshToken = json.text("refresh_token")
        if (refreshToken.isNullOrEmpty()) throw CognitoWords.unsupported("HOSTED_WITHOUT_REFRESH_TOKEN")
        val tokens = AuthTokens.issued(
            accessToken = json.text("access_token") ?: "",
            idToken = json.text("id_token") ?: "",
            refreshToken = refreshToken,
            expiresIn = (json["expires_in"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull,
            now = now(),
            clientId = signIn.clientId,
        )
        if (tokens.sub.isEmpty()) throw CognitoWords.unsupported("TOKENS_WITHOUT_IDENTITY")
        return tokens
    }

    private fun JsonObject.text(name: String): String? {
        val value = this[name] as? JsonPrimitive ?: return null
        return if (value.isString) value.content else null
    }
}
