package info.ibtaura.aura.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.util.Base64

// A port of the token half of AuraKit/TokenStore.swift. The Android store (Keystore-backed) lives in :app.

/** What Cognito issued. Only ever written to a `TokenStore`; never log any of it. */
@Serializable
data class AuthTokens(
    val idToken: String,
    val accessToken: String,
    val refreshToken: String,
    /** When the access token stops being accepted. */
    @Serializable(with = InstantSerializer::class)
    val expiresAt: Instant,
) {
    /** The Cognito `sub`, from the id token. Empty when the token doesn't carry one. */
    val sub: String get() = JWT.string(JWT.claims(idToken), "sub") ?: ""

    val email: String?
        get() {
            val email = JWT.string(JWT.claims(idToken), "email")
            return if (email.isNullOrEmpty()) null else email.lowercase()
        }

    /** Never prints the tokens. */
    override fun toString(): String = "AuthTokens(expiresAt=$expiresAt)"

    companion object {
        /** Builds tokens from Cognito's `AuthenticationResult`: the access token's own expiry, else `ExpiresIn`, else an hour. */
        fun issued(accessToken: String, idToken: String, refreshToken: String, expiresIn: Int?, now: Instant): AuthTokens {
            val fromToken = JWT.expiry(accessToken)
            val expiry = when {
                fromToken != null -> fromToken
                expiresIn != null && expiresIn > 0 -> now.plusSeconds(expiresIn.toLong())
                else -> now.plusSeconds(3600)
            }
            return AuthTokens(idToken = idToken, accessToken = accessToken, refreshToken = refreshToken, expiresAt = expiry)
        }
    }
}

/** Reads a JWT's payload without checking it: the API checks the signature, the app only reads its own identity and expiry. */
object JWT {
    fun claims(token: String): JsonObject {
        val parts = token.split(".")
        if (parts.size < 2) return JsonObject(emptyMap())
        val body = StringBuilder(parts[1].replace('-', '+').replace('_', '/'))
        while (body.length % 4 != 0) body.append('=')
        return runCatching {
            val data = Base64.getDecoder().decode(body.toString())
            Json.parseToJsonElement(data.toString(Charsets.UTF_8)) as JsonObject
        }.getOrDefault(JsonObject(emptyMap()))
    }

    fun expiry(token: String): Instant? {
        val exp = claims(token)["exp"] as? JsonPrimitive ?: return null
        if (exp.isString) return null
        val seconds = exp.doubleOrNull ?: return null
        return Instant.ofEpochMilli((seconds * 1000).toLong())
    }

    /** A string claim, or null when it's missing or not a string. */
    internal fun string(claims: JsonObject, name: String): String? {
        val value = claims[name] as? JsonPrimitive ?: return null
        return if (value.isString) value.content else null
    }
}

/** Where the session is kept between launches. */
interface TokenStore {
    fun load(): AuthTokens?
    fun save(tokens: AuthTokens)
    fun clear()
}

/** For tests and stubs (Swift's `MemoryTokenStore`). */
class InMemoryTokenStore(tokens: AuthTokens? = null) : TokenStore {
    private val lock = Any()
    private var held: AuthTokens? = tokens

    val tokens: AuthTokens? get() = synchronized(lock) { held }

    override fun load(): AuthTokens? = tokens

    override fun save(tokens: AuthTokens) {
        synchronized(lock) { held = tokens }
    }

    override fun clear() {
        synchronized(lock) { held = null }
    }
}
