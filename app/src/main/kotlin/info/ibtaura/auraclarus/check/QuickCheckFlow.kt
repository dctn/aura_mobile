package info.ibtaura.auraclarus.check

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import info.ibtaura.aura.core.AuraApi
import info.ibtaura.aura.core.QuickCheckResult
import info.ibtaura.aura.core.QuickCheckRun
import info.ibtaura.aura.core.ReactionTest
import info.ibtaura.aura.core.SpanResult
import info.ibtaura.aura.core.SpanTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.ceil

/**
 * Runs one Quick check (iOS QuickCheckFlow.swift): the intro, the reaction test, the memory part, then sending it.
 * The screen only draws it.
 *
 * Times are `SystemClock.uptimeMillis()` in seconds, a clock that never jumps when the phone's time is changed. The
 * dot's time is read in the frame callback that puts it on screen (`withFrameNanos`), and a tap's time is the touch
 * event's own `uptimeMillis`, the same clock, so neither carries coroutine scheduling delays.
 *
 * `scope` must carry a frame clock (the screen's `rememberCoroutineScope()` does).
 */
@Stable
internal class QuickCheckFlow(
    private val api: AuraApi,
    short: Boolean,
    private val uiTesting: Boolean,
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit,
) {
    enum class Step { intro, reaction, memory, sending, result }

    var step by mutableStateOf(Step.intro)
        private set

    // What the reaction card shows. Copied out of the test only when it changes, so the screen redraws a few times
    // a second and not on every frame.
    var reactionPhase by mutableStateOf<ReactionTest.Phase>(ReactionTest.Phase.NotStarted)
        private set
    /** The newest reactions first, at most 3. */
    var recentTimes by mutableStateOf<List<Double>>(emptyList())
        private set
    var secondsLeft by mutableIntStateOf(0)
        private set

    /** The memory test. Its fields aren't Compose state, so `spanVersion` is bumped whenever it changes. */
    var span: SpanTest
        private set
    var spanVersion by mutableIntStateOf(0)
        private set
    /** False for the second before the first row shows. */
    var memoryBegun by mutableStateOf(false)
        private set
    /** Right or wrong for the row just typed, shown briefly; null the rest of the time. */
    var feedback by mutableStateOf<Boolean?>(null)
        private set
    /** The row as it was typed, shown with the feedback (the test has already moved on to the next row). */
    var feedbackRow by mutableStateOf<List<Int>>(emptyList())
        private set

    /** True after the app was left during the reaction test; the intro then says why it starts again. */
    var interrupted by mutableStateOf(false)
        private set
    var sendFailed by mutableStateOf(false)
        private set
    var result by mutableStateOf<QuickCheckResult?>(null)
        private set
    /** True when the memory part was done rather than skipped. */
    var memoryDone by mutableStateOf(false)
        private set

    val reactionConfig: ReactionTest.Config =
        if (short) ReactionTest.Config.short(8.0) else ReactionTest.Config.standard
    private val spanConfig: SpanTest.Config = if (short) SpanTest.Config.short else SpanTest.Config.standard

    private var reaction = ReactionTest(reactionConfig)
    private var startedAt = Instant.now()
    /** Built once, so "Try again" resends the same id. */
    private var run: QuickCheckRun? = null
    private var ticker: Job? = null
    private var readyJob: Job? = null
    private var hideJob: Job? = null
    private var feedbackJob: Job? = null
    private var sendJob: Job? = null
    /** Counts the rows shown, so a late hide never hides the next row early. */
    private var round = 0
    private var stopped = false

    init {
        span = SpanTest(spanConfig)
        secondsLeft = ceil(reactionConfig.duration).toInt()
    }

    /** True while a part of the check is running, so leaving asks first. */
    val isRunning: Boolean get() = step == Step.reaction || step == Step.memory

    // MARK: Reaction

    fun startReaction() {
        if (step != Step.intro || stopped) return
        reaction = ReactionTest(reactionConfig)
        startedAt = Instant.now()
        val now = clockNow()
        reaction.start(now)
        step = Step.reaction
        publishReaction(now)
        startTicker()
    }

    /** A touch-down on the card, timed by the touch event (`PointerInputChange.uptimeMillis`, in seconds). */
    fun tap(at: Double) {
        if (step != Step.reaction) return
        reaction.tap(at)
        publishReaction(at)
        if (reaction.isFinished) finishReaction()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && step == Step.reaction) {
                // Once a frame: a dot due now is shown on this very frame, and counts from it.
                withFrameNanos { tick() }
                // UI tests move on more slowly, so the test runner sees an idle app between ticks.
                if (uiTesting) delay(100)
            }
        }
    }

    private fun tick() {
        if (step != Step.reaction) return
        val now = clockNow()
        reaction.tick(now)
        publishReaction(now)
        if (reaction.isFinished) finishReaction()
    }

    private fun publishReaction(now: Double) {
        val phase = reaction.phase
        if (phase != reactionPhase) reactionPhase = phase
        val recent = reaction.recent
        if (recent != recentTimes) recentTimes = recent
        val left = ceil(reaction.remaining(now)).toInt()
        if (left != secondsLeft) secondsLeft = left
    }

    private fun finishReaction() {
        if (step != Step.reaction) return
        ticker?.cancel()
        ticker = null
        step = Step.memory
        readyJob = scope.launch {
            // A moment to look away from the card before the first row.
            delay(1_000)
            if (step != Step.memory) return@launch
            span.begin()
            spanChanged()
            memoryBegun = true
            scheduleHide()
        }
    }

    // MARK: Memory

    /** Hides the row after `length * showPerDigit` seconds. */
    private fun scheduleHide() {
        round += 1
        val thisRound = round
        val milliseconds = Math.round(span.showDuration * 1000)
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(milliseconds)
            if (step != Step.memory || round != thisRound) return@launch
            span.hideDigits()
            spanChanged()
        }
    }

    fun type(digit: Int) {
        if (step != Step.memory || feedback != null || span.phase != SpanTest.Phase.entering) return
        val row = span.entered + digit
        val right = span.type(digit)
        spanChanged()
        if (right == null) return
        feedbackRow = row
        feedback = right
        feedbackJob = scope.launch {
            delay(800)
            if (step != Step.memory) return@launch
            feedback = null
            afterRow()
        }
    }

    fun deleteLast() {
        if (step != Step.memory || feedback != null) return
        span.deleteLast()
        spanChanged()
    }

    private fun afterRow() {
        when (span.phase) {
            SpanTest.Phase.finished -> send(span.result)
            SpanTest.Phase.showing -> scheduleHide()
            SpanTest.Phase.entering -> Unit
        }
    }

    /** Sends the check as reaction only. */
    fun skipMemory() {
        if (step != Step.memory) return
        send(null)
    }

    private fun spanChanged() {
        spanVersion += 1
    }

    // MARK: Sending

    private fun send(answered: SpanResult?) {
        cancelMemoryJobs()
        // The backend needs at least one answered row; with none the check goes as reaction only.
        val memory = if ((answered?.spanAttempts ?: 0) > 0) answered else null
        memoryDone = memory != null
        run = QuickCheckRun(
            takenAt = startedAt,
            durationSeconds = reaction.durationSeconds,
            reactionTimesMs = reaction.reactionTimesMs,
            falseStarts = reaction.falseStarts,
            memory = memory,
        )
        submit()
    }

    /** Sends the run built once; resending the same id is safe. */
    fun submit() {
        val run = run ?: return
        if (stopped) return
        sendFailed = false
        step = Step.sending
        sendJob?.cancel()
        sendJob = scope.launch {
            try {
                val answer = api.submitCheck(run)
                if (stopped) return@launch
                result = answer
                step = Step.result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (stopped) return@launch
                onError(e)
                sendFailed = true
            }
        }
    }

    /**
     * The app was left: the reaction test's timing no longer holds, so it starts again from the intro. The memory
     * part isn't timed and carries on.
     */
    fun interrupt() {
        if (step != Step.reaction || stopped) return
        ticker?.cancel()
        ticker = null
        reaction = ReactionTest(reactionConfig)
        reactionPhase = ReactionTest.Phase.NotStarted
        recentTimes = emptyList()
        secondsLeft = ceil(reactionConfig.duration).toInt()
        interrupted = true
        step = Step.intro
    }

    // MARK: Stopping

    /** Stops every timer; nothing more is sent. Safe to call more than once. */
    fun stop() {
        stopped = true
        ticker?.cancel()
        ticker = null
        cancelMemoryJobs()
    }

    private fun cancelMemoryJobs() {
        readyJob?.cancel()
        readyJob = null
        hideJob?.cancel()
        hideJob = null
        feedbackJob?.cancel()
        feedbackJob = null
    }

    private companion object {
        fun clockNow(): Double = SystemClock.uptimeMillis() / 1000.0
    }
}
