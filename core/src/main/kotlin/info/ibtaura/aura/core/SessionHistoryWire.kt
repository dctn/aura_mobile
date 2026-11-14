package info.ibtaura.aura.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

// Session history's wire shapes (session.ts sessionView, rewardsView; health-data.ts sessionTrace). Everything is
// optional and lossy, so one odd field never fails the page.

/** The sessionView keys `WireSessionView` doesn't read. Read from the same JSON object. */
@Serializable
internal data class WireSessionExtras(
    val mode: String? = null,
    val state: String? = null,
    val date: String? = null,
    val plannedMinutes: Double? = null,
    val minutes: Double? = null,
    val activeMinutes: Double? = null,
    val protectedMinutes: Double? = null,
    val interruptions: Double? = null,
    /** Lossy list of `WireSessionNote`. */
    val notes: JsonElement? = null,
    /** Lossy `WireSessionCapacity`. */
    val capacity: JsonElement? = null,
    /** Lossy `WireSessionRewards`. */
    val rewards: JsonElement? = null,
)

@Serializable
internal data class WireSessionNote(val at: String? = null, val text: String? = null)

@Serializable
internal data class WireSessionCapacity(
    val before: Double? = null,
    val after: Double? = null,
    val cost: Double? = null,
    val charged: Boolean? = null,
)

@Serializable
internal data class WireSessionRewards(val xpEarned: Double? = null, val levelUp: Boolean? = null)

internal object WireSessionDetail {
    /** One sessionView, or null when even its id can't be read. */
    fun model(element: JsonElement): SessionDetail? {
        val view = element.lossy(WireSessionView.serializer()) ?: return null
        val extras = element.lossy(WireSessionExtras.serializer()) ?: WireSessionExtras()
        val notes = (extras.notes as? JsonArray)?.toList().lossyItems(WireSessionNote.serializer())
            .mapNotNull { note -> note.text?.takeIf { it.isNotBlank() }?.let { SessionNote(WireDate.parse(note.at), it) } }
        val charge = extras.capacity.lossy(WireSessionCapacity.serializer())
        val capacity = if (charge?.before != null && charge.after != null) {
            SessionCapacity(before = charge.before, after = charge.after, cost = charge.cost ?: 0.0,
                charged = charge.charged ?: false)
        } else {
            null
        }
        val rewards = extras.rewards.lossy(WireSessionRewards.serializer())
        val wall = WireWhole.from(extras.minutes) ?: 0
        val active = WireWhole.from(extras.activeMinutes) ?: wall
        return SessionDetail(
            record = view.model,
            mode = extras.mode,
            state = extras.state,
            date = extras.date,
            plannedMinutes = WireWhole.from(extras.plannedMinutes),
            wallMinutes = wall,
            activeMinutes = active,
            protectedMinutes = WireWhole.from(extras.protectedMinutes) ?: active,
            interruptions = WireWhole.from(extras.interruptions) ?: 0,
            capacity = capacity,
            notes = notes,
            xpEarned = rewards?.xpEarned?.let { WireWhole.from(it) },
            levelUp = rewards?.levelUp ?: false,
        )
    }
}

/** GET /v1/sessions: `{sessions: [sessionView], cursor}`. A session the app can't read is left out. */
@Serializable
internal data class WireSessionDetailPage(val sessions: List<JsonElement>? = null, val cursor: String? = null) {
    val model: SessionPage
        get() = SessionPage(sessions = sessions.orEmpty().mapNotNull { WireSessionDetail.model(it) }, cursor = cursor)
}

/** GET /v1/sessions/{id}: `{session: sessionView}`. */
@Serializable
internal data class WireSessionDetailAnswer(val session: JsonElement? = null)

// MARK: Rewards: GET /v1/rewards

@Serializable
internal data class WireRewards(
    val xp: Double? = null,
    val level: Double? = null,
    val xpIntoLevel: Double? = null,
    val xpPerLevel: Double? = null,
    /** Lossy `Plant`. */
    val plant: JsonElement? = null,
    /** Lossy `Badge` items. */
    val badges: List<JsonElement>? = null,
    val totalFocusMinutes: Double? = null,
    val sessionsCompleted: Double? = null,
) {
    @Serializable
    internal data class Plant(val stage: Double? = null, val name: String? = null)

    @Serializable
    internal data class Badge(
        val id: String,
        val title: String? = null,
        val requirementMinutes: Double? = null,
        val unlocked: Boolean? = null,
        val unlockedAt: String? = null,
    )

    val model: Rewards
        get() {
            val xpTotal = WireWhole.from(xp) ?: 0
            val perLevel = WireWhole.from(xpPerLevel) ?: 60
            val plantInfo = plant.lossy(Plant.serializer())
            return Rewards(
                xp = xpTotal,
                level = WireWhole.from(level) ?: (xpTotal / perLevel.coerceAtLeast(1) + 1),
                xpIntoLevel = WireWhole.from(xpIntoLevel) ?: (xpTotal % perLevel.coerceAtLeast(1)),
                xpPerLevel = perLevel,
                plantStage = WireWhole.from(plantInfo?.stage) ?: 0,
                plantName = plantInfo?.name ?: "Seed",
                badges = badges.lossyItems(Badge.serializer()).map { badge ->
                    RewardBadge(id = badge.id, title = badge.title ?: badge.id,
                        requirementMinutes = WireWhole.from(badge.requirementMinutes) ?: 0,
                        unlocked = badge.unlocked ?: false, unlockedAt = WireDate.parse(badge.unlockedAt))
                },
                totalFocusMinutes = WireWhole.from(totalFocusMinutes) ?: xpTotal,
                sessionsCompleted = WireWhole.from(sessionsCompleted) ?: 0,
            )
        }
}

// MARK: Trace: GET /v1/trends/sessions/{id}

@Serializable
internal data class WireHeartRatePoint(val at: String? = null, val bpm: Double? = null)

@Serializable
internal data class WireStressPoint(val hour: Int? = null, val value: Double? = null)

/** The trace with its points. A point without a time (or hour) is left out; one without a value keeps its gap. */
internal val WireTrace.model: SessionTrace
    get() = SessionTrace(
        status = status ?: "noData",
        stressDrop = stressDrop,
        heartRate = hr.lossyItems(WireHeartRatePoint.serializer())
            .mapNotNull { point -> WireDate.parse(point.at)?.let { HeartRatePoint(it, point.bpm) } }
            .sortedBy { it.at },
        stress = stress.lossyItems(WireStressPoint.serializer())
            .mapNotNull { point -> point.hour?.takeIf { it in 0..23 }?.let { StressPoint(it, point.value) } }
            .sortedBy { it.hour },
        avgHr = avgHr,
    )
