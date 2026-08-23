package dev.farid.stabber.combat

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Frame-driven click scheduler that reads like a person mousing at roughly [Companion.CPS_MIN] to
 * [Companion.CPS_MAX] CPS.
 *
 * No constant rate anywhere: the CPS target itself wanders (resampled every few hundred
 * milliseconds), individual intervals are Gaussian-jittered around that target and trend instead of
 * firing white noise (AR(1)-style persistence), and the rhythm occasionally breaks with short fast
 * bursts and reaction-shaped pauses.
 */
class HumanClicker {
    companion object {
        /** Lower bound of the wandering CPS target. */
        const val CPS_MIN: Double = 8.0

        /** Upper bound of the wandering CPS target. */
        const val CPS_MAX: Double = 12.0

        /** Standard deviation of the Gaussian jitter applied to every interval, ms. */
        const val JITTER_SIGMA_MS: Double = 8.0

        /** Fraction of the previous interval's deviation carried into the next one, so intervals trend. */
        const val INTERVAL_PERSISTENCE: Double = 0.45

        /** Hard floor and ceiling on any single interval, ms. */
        const val INTERVAL_MIN_MS: Double = 45.0
        const val INTERVAL_MAX_MS: Double = 400.0

        /** How often the CPS target is resampled, seconds. */
        const val RESAMPLE_MIN_S: Double = 0.4
        const val RESAMPLE_MAX_S: Double = 0.9

        /** Chance per resample that a short fast burst starts. */
        const val BURST_CHANCE_PER_WINDOW: Double = 0.15

        /** Burst speed multiplier range applied to the CPS target while a burst lasts. */
        const val BURST_SPEEDUP_MIN: Double = 1.15
        const val BURST_SPEEDUP_MAX: Double = 1.28

        /** Burst length bounds, clicks. */
        const val BURST_CLICKS_MIN: Int = 2
        const val BURST_CLICKS_MAX: Int = 4

        /** Chance per click that a reaction-shaped pause follows it. */
        const val PAUSE_CHANCE_PER_CLICK: Double = 0.02

        /** Pause length bounds, seconds. */
        const val PAUSE_MIN_S: Double = 0.12
        const val PAUSE_MAX_S: Double = 0.35
    }

    private var nextClickNanos = 0L
    private var resampleDeadlineNanos = 0L
    private var pauseUntilNanos = 0L

    private var cpsTarget = (CPS_MIN + CPS_MAX) / 2.0
    private var intervalDeviation = 0.0

    private var burstRemainingClicks = 0
    private var burstSpeedup = 1.0

    /**
     * Advances the schedule. Returns true exactly once per scheduled click; [gateOpen] false
     * suppresses firing and pushes the schedule out so the first click after reopening does not
     * machine-gun.
     */
    fun tick(nowNanos: Long, gateOpen: Boolean): Boolean {
        if (nowNanos >= resampleDeadlineNanos) {
            resample(nowNanos)
        }
        if (nowNanos < pauseUntilNanos) return false
        if (nowNanos < nextClickNanos) return false
        if (!gateOpen) {
            defer(nowNanos)
            return false
        }

        scheduleNext(nowNanos)
        if (Math.random() < PAUSE_CHANCE_PER_CLICK) {
            pauseUntilNanos = nowNanos + (uniform(PAUSE_MIN_S, PAUSE_MAX_S) * 1.0e9).toLong()
        }
        return true
    }

    fun reset(nowNanos: Long) {
        nextClickNanos = nowNanos
        resampleDeadlineNanos = 0L
        pauseUntilNanos = 0L
        cpsTarget = (CPS_MIN + CPS_MAX) / 2.0
        intervalDeviation = 0.0
        burstRemainingClicks = 0
        burstSpeedup = 1.0
    }

    private fun resample(nowNanos: Long) {
        cpsTarget = uniform(CPS_MIN, CPS_MAX)
        if (burstRemainingClicks <= 0 && Math.random() < BURST_CHANCE_PER_WINDOW) {
            burstSpeedup = uniform(BURST_SPEEDUP_MIN, BURST_SPEEDUP_MAX)
            burstRemainingClicks =
                BURST_CLICKS_MIN + (Math.random() * (BURST_CLICKS_MAX - BURST_CLICKS_MIN + 1)).toInt()
        }
        resampleDeadlineNanos = nowNanos + (uniform(RESAMPLE_MIN_S, RESAMPLE_MAX_S) * 1.0e9).toLong()
    }

    private fun scheduleNext(nowNanos: Long) {
        val meanIntervalNanos = 1.0e9 / (cpsTarget * burstSpeedup)
        intervalDeviation =
            INTERVAL_PERSISTENCE * intervalDeviation + gaussian() * JITTER_SIGMA_MS * 1.0e6
        val intervalNanos = (meanIntervalNanos + intervalDeviation)
            .coerceIn(INTERVAL_MIN_MS * 1.0e6, INTERVAL_MAX_MS * 1.0e6)
        nextClickNanos = nowNanos + intervalNanos.toLong()

        if (burstRemainingClicks > 0 && --burstRemainingClicks == 0) {
            burstSpeedup = 1.0
        }
    }

    /** Pushes the next click out by one mean interval without disturbing the jitter state. */
    private fun defer(nowNanos: Long) {
        val meanIntervalNanos = 1.0e9 / cpsTarget
        nextClickNanos = nowNanos + meanIntervalNanos.toLong()
    }

    /** Standard normal via Box-Muller. */
    private fun gaussian(): Double {
        var u = Math.random()
        if (u < 1.0e-9) u = 1.0e-9
        val v = Math.random()
        return sqrt(-2.0 * ln(u)) * cos(2.0 * Math.PI * v)
    }

    private fun uniform(min: Double, max: Double): Double = min + Math.random() * (max - min)
}
