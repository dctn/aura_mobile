package info.ibtaura.auraclarus.shell

import android.content.Context
import info.ibtaura.aura.core.AuraApi
import info.ibtaura.aura.core.AuthService
import info.ibtaura.aura.core.LiveServices
import info.ibtaura.auraclarus.store.KeystoreTokenStore

/** Reads assets/aura-dev.json and builds CognitoAuth + LiveApi on dev us-west-1. Refuses prod and ap-south-1. */
fun liveServices(context: Context): Pair<AuraApi, AuthService> {
    val text = context.assets.open("aura-dev.json").bufferedReader().use { it.readText() }
    return LiveServices.make(text, KeystoreTokenStore(context))
}
