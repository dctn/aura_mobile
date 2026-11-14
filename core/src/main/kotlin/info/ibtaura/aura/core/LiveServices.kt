package info.ibtaura.aura.core

import okhttp3.OkHttpClient

/**
 * Builds the real backend and sign-in. The app calls this at launch when it isn't running UI tests. The API keeps
 * GET /v1/me for `MeCache.KEEP_MILLIS`, shared by every screen, and forgets it whenever the session begins or ends.
 */
object LiveServices {
    fun make(config: AppConfig, store: TokenStore, http: OkHttpClient = auraHttpClient()): Pair<AuraApi, AuthService> {
        val auth = CognitoAuth(config, store, http)
        val api = LiveApi(config, auth, http, realSleep, realNow, meKeepMillis = MeCache.KEEP_MILLIS)
        auth.onSessionChanged = { api.clearCaches() }
        return api to auth
    }

    /** Reads aura-dev.json. Refuses prod, ap-south-1, and any service URL in ap-south-1. */
    fun make(configText: String, store: TokenStore, http: OkHttpClient = auraHttpClient()): Pair<AuraApi, AuthService> =
        make(checked(AppConfig.decode(configText)), store, http)

    internal fun checked(config: AppConfig): AppConfig {
        val badUrl = config.services.values.any { it.contains("ap-south-1") }
        if (config.stage == "prod" || config.region == "ap-south-1" || badUrl) {
            throw AuraError.Server(code = "bad_config", message = "This build only runs against a dev stage.", status = 0)
        }
        return config
    }
}
