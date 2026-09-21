package dev.creditwatch.engine

import dev.creditwatch.domain.RunwayAlertState
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.*

class RunwayAlertRuleTest {
    private val rule = RunwayAlertRule()
    private val now = Instant.parse("2026-09-20T12:00:00Z")
    private fun hours(value: Long) = RunwayResult.Available(Duration.ofHours(value))
    private fun minutes(value: Long) = RunwayResult.Available(Duration.ofMinutes(value))

    @Test fun crossingAndLowerThresholdNotifyOnce() {
        val first = rule.evaluate(RunwayAlertState(), hours(11), now, fresh = true)
        assertEquals(12, first.event?.thresholdHours)
        val repeated = rule.evaluate(first.state, hours(10), now.plusSeconds(60), fresh = true)
        assertNull(repeated.event)
        val lower = rule.evaluate(repeated.state, hours(5), now.plusSeconds(120), fresh = true)
        assertEquals(6, lower.event?.thresholdHours)
        val critical = rule.evaluate(lower.state, minutes(45), now.plusSeconds(180), fresh = true)
        assertEquals(1, critical.event?.thresholdHours)
    }

    @Test fun hysteresisPreventsFlappingAndResolutionAllowsNewAlert() {
        val active = rule.evaluate(RunwayAlertState(), hours(5), now, true).state
        val near = rule.evaluate(active, minutes(370), now.plusSeconds(60), true)
        assertEquals(6, near.state.activeThresholdHours)
        assertNull(near.event)
        val recovered = rule.evaluate(near.state, minutes(391), now.plusSeconds(120), true)
        assertEquals(12, recovered.state.activeThresholdHours)
        val crossedAgain = rule.evaluate(recovered.state, hours(5), now.plusSeconds(180), true)
        assertEquals(6, crossedAgain.event?.thresholdHours)
        val resolved = rule.evaluate(crossedAgain.state, hours(13), now.plusSeconds(240), true)
        assertNull(resolved.state.activeThresholdHours)
    }

    @Test fun staleAndUnavailableDataNeverTriggerOrResolve() {
        val active = rule.evaluate(RunwayAlertState(), hours(5), now, true).state
        assertEquals(active, rule.evaluate(active, hours(20), now.plusSeconds(60), fresh = false).state)
        assertEquals(active, rule.evaluate(active, RunwayResult.Unavailable, now.plusSeconds(60), true).state)
        assertNull(rule.evaluate(active, RunwayResult.Unavailable, now.plusSeconds(60), true).event)
    }

    @Test fun aThresholdThisRuleDoesNotHaveIsNeverRevived() {
        // The carried state names 12h, but this rule has only 1h and 6h: the user switched 12h
        // off, and the stored row outlived the change.
        val carried = RunwayAlertState(activeThresholdHours = 12, lastNotifiedAt = now)
        val narrowed = RunwayAlertRule(listOf(1, 6))
        val afterCooldown = narrowed.evaluate(carried, hours(9), now.plus(Duration.ofHours(6)), true)
        assertNull(afterCooldown.event)
        assertNull(afterCooldown.state.activeThresholdHours)
        // A mark that is still armed warns as usual, carrying no memory of the one that was not.
        assertEquals(6, narrowed.evaluate(carried, hours(5), now.plusSeconds(60), true).event?.thresholdHours)
    }

    @Test fun cooldownAndZeroBurnAreHandled() {
        val active = rule.evaluate(RunwayAlertState(), hours(5), now, true).state
        assertNull(rule.evaluate(active, hours(5), now.plus(Duration.ofHours(5)), true).event)
        assertEquals(6, rule.evaluate(active, hours(5), now.plus(Duration.ofHours(6)), true).event?.thresholdHours)
        assertNull(rule.evaluate(active, RunwayResult.NoBurn, now.plusSeconds(60), true).state.activeThresholdHours)
        assertEquals(1, rule.evaluate(RunwayAlertState(), RunwayResult.BalanceDepleted, now, true).event?.thresholdHours)
    }
}
