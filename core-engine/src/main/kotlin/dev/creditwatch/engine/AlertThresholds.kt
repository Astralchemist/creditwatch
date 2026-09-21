package dev.creditwatch.engine

import java.time.Duration

/** The runway thresholds a user can arm, longest first for display. */
val RUNWAY_ALERT_THRESHOLDS_HOURS: List<Int> = listOf(12, 6, 1)

/** Why a threshold cannot be armed right now, or [ARMABLE] if it can. */
sealed interface ThresholdArming {
    data object Armable : ThresholdArming
    /** The runway is already under the threshold, so arming it would report the past. */
    data class AlreadyBelow(val thresholdHours: Int) : ThresholdArming
    /** There is nothing left to warn about. */
    data object Depleted : ThresholdArming
}

/**
 * Whether a runway threshold is still worth switching on.
 *
 * Arming a threshold the runway has already fallen under is not a warning, it is a
 * notification about the past: it would fire on the very next cycle and tell the user
 * something the headline already says in red. Refuse the switch while the reading says so
 * rather than firing an alert that was never early.
 *
 * A threshold that was armed before the runway fell stays armed — that alert is the whole
 * point. This governs arming only, never an existing subscription.
 */
fun thresholdArming(thresholdHours: Int, runway: RunwayResult): ThresholdArming {
    require(thresholdHours > 0) { "A threshold must be a positive number of hours" }
    return when (runway) {
        RunwayResult.BalanceDepleted -> ThresholdArming.Depleted
        is RunwayResult.Available ->
            if (runway.duration < Duration.ofHours(thresholdHours.toLong()))
                ThresholdArming.AlreadyBelow(thresholdHours) else ThresholdArming.Armable
        // Without a burn or without complete prices there is no runway to compare against, so
        // the user is allowed to arm ahead of the data arriving.
        RunwayResult.NoBurn, RunwayResult.Unavailable -> ThresholdArming.Armable
    }
}

fun ThresholdArming.isArmable(): Boolean = this == ThresholdArming.Armable
