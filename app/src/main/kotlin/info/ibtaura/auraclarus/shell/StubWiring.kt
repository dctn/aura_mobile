package info.ibtaura.auraclarus.shell

import info.ibtaura.aura.core.AuraApi
import info.ibtaura.aura.core.AuthService
import info.ibtaura.aura.core.StubApi
import info.ibtaura.aura.core.StubAuth

/** StubApi + StubAuth from :core, configured by the UI-test flags as iOS `AppModel.make()` does. */
fun stubServices(flags: LaunchFlags): Pair<AuraApi, AuthService> {
    val api = if (flags.noSchedule) {
        StubApi(workSchedule = null, joinsRunningSession = flags.joinedSession,
            needsQuickCheck = flags.needsCheck, shortChecksCount = flags.shortCheck)
    } else {
        StubApi(joinsRunningSession = flags.joinedSession,
            needsQuickCheck = flags.needsCheck, shortChecksCount = flags.shortCheck)
    }
    return api to StubAuth()
}
