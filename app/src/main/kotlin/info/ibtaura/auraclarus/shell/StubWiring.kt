package info.ibtaura.auraclarus.shell

import info.ibtaura.aura.core.AuraApi
import info.ibtaura.aura.core.AuthService
import info.ibtaura.aura.core.MorningOpening
import info.ibtaura.aura.core.SampleApi
import info.ibtaura.aura.core.StubApi
import info.ibtaura.aura.core.StubAuth

/** StubApi + StubAuth from :core, configured by the UI-test flags as iOS `AppModel.make()` does. */
fun stubServices(flags: LaunchFlags): Pair<AuraApi, AuthService> {
    // `--uitesting-collecting`: no nights yet and none last night, so Home shows the collecting banner.
    val opening = if (flags.collecting) {
        MorningOpening(estimated = true, baselineDays = 0, lastNight = false, score = 64.0, current = 64.0)
    } else {
        MorningOpening(estimated = false, baselineDays = 14, lastNight = true, score = 82.0, current = 76.0)
    }
    val api = if (flags.noSchedule) {
        StubApi(workSchedule = null, joinsRunningSession = flags.joinedSession,
            needsQuickCheck = flags.needsCheck, shortChecksCount = flags.shortCheck, opening = opening)
    } else {
        StubApi(joinsRunningSession = flags.joinedSession,
            needsQuickCheck = flags.needsCheck, shortChecksCount = flags.shortCheck, opening = opening)
    }
    return api to StubAuth()
}

/** Sample mode: weeks of made-up data, edits kept in memory until the app restarts. No network. */
fun sampleServices(): Pair<AuraApi, AuthService> = SampleApi() to StubAuth()
