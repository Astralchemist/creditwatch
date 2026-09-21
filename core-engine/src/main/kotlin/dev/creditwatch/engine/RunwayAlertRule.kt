package dev.creditwatch.engine

import dev.creditwatch.domain.RunwayAlertState
import java.time.Duration
import java.time.Instant

data class RunwayAlertEvent(
    val thresholdHours: Int,
    val runway: RunwayResult,
    val observedAt: Instant,
)

data class RunwayAlertEvaluation(
    val state: RunwayAlertState,
    val event: RunwayAlertEvent?,
)

/** Evaluates only fresh provider cycles. State is persisted by the caller. */
class RunwayAlertRule(
    private val thresholdsHours: List<Int> = listOf(1, 6, 12),
    private val hysteresis: Duration = Duration.ofMinutes(30),
    private val repeatCooldown: Duration = Duration.ofHours(6),
) {
    init {
        require(thresholdsHours.isNotEmpty() && thresholdsHours.all { it > 0 })
        require(hysteresis >= Duration.ZERO && repeatCooldown > Duration.ZERO)
    }

    fun evaluate(
        previous: RunwayAlertState,
        runway: RunwayResult,
        observedAt: Instant,
        fresh: Boolean,
    ): RunwayAlertEvaluation {
        if (!fresh || runway == RunwayResult.Unavailable) return RunwayAlertEvaluation(previous, null)
        val remaining = when (runway) {
            is RunwayResult.Available -> runway.duration
            RunwayResult.BalanceDepleted -> Duration.ZERO
            RunwayResult.NoBurn -> null
            RunwayResult.Unavailable -> error("Handled above")
        }
        val crossed = remaining?.let { value ->
            thresholdsHours.sorted().firstOrNull { value < Duration.ofHours(it.toLong()) }
        }
        // The carried state may name a threshold this rule does not have: the user switched it
        // off, or the app restarted before the change reached the stored row. Honouring it would
        // warn about a mark that is no longer armed, so it counts as no previous threshold.
        val previousThreshold = previous.activeThresholdHours?.takeIf { it in thresholdsHours }
        val lastNotified = previous.lastNotifiedAt
        val active = when {
            remaining == null -> null
            previousThreshold != null &&
                remaining < Duration.ofHours(previousThreshold.toLong()).plus(hysteresis) ->
                minOf(previousThreshold, crossed ?: previousThreshold)
            else -> crossed
        }
        val notify = active != null && (
            previousThreshold == null ||
                active < previousThreshold ||
                (active == previousThreshold &&
                    (lastNotified == null ||
                        !observedAt.isBefore(lastNotified.plus(repeatCooldown))))
            )
        val next = RunwayAlertState(active, if (notify) observedAt else lastNotified)
        return RunwayAlertEvaluation(next,
            if (notify) RunwayAlertEvent(requireNotNull(active), runway, observedAt) else null)
    }
}
