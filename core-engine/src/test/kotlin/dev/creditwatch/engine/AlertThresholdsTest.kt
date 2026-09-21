package dev.creditwatch.engine

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlertThresholdsTest {
    @Test
    fun `a threshold above the current runway can be armed`() {
        val runway = RunwayResult.Available(Duration.ofHours(9))
        assertTrue(thresholdArming(6, runway).isArmable())
    }

    @Test
    fun `a threshold the runway has already fallen under cannot be armed`() {
        val runway = RunwayResult.Available(Duration.ofHours(4))
        assertEquals(ThresholdArming.AlreadyBelow(6), thresholdArming(6, runway))
        assertFalse(thresholdArming(6, runway).isArmable())
        assertFalse(thresholdArming(12, runway).isArmable())
        assertTrue(thresholdArming(1, runway).isArmable())
    }

    @Test
    fun `a runway exactly at the threshold can still be armed`() {
        val runway = RunwayResult.Available(Duration.ofHours(6))
        assertTrue(thresholdArming(6, runway).isArmable())
    }

    @Test
    fun `a depleted balance leaves nothing to warn about`() {
        RUNWAY_ALERT_THRESHOLDS_HOURS.forEach {
            assertEquals(ThresholdArming.Depleted, thresholdArming(it, RunwayResult.BalanceDepleted))
        }
    }

    @Test
    fun `without a runway reading the user may arm ahead of the data`() {
        assertTrue(thresholdArming(12, RunwayResult.Unavailable).isArmable())
        assertTrue(thresholdArming(12, RunwayResult.NoBurn).isArmable())
    }

    @Test
    fun `a threshold must be a positive number of hours`() {
        assertFailsWith<IllegalArgumentException> { thresholdArming(0, RunwayResult.NoBurn) }
    }
}
