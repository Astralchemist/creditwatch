package dev.creditwatch.engine

import dev.creditwatch.domain.*
import java.time.Duration
import java.time.Instant
import kotlin.test.*

class MonitoringCalculatorTest {
    private val now = Instant.parse("2026-09-19T12:00:00Z")
    private val usd = CurrencyCode("USD")
    private fun sample(secondsAgo: Long, rate: String) = MonitoringSample(
        AccountId("a"), now.minusSeconds(secondsAgo), now.minusSeconds(secondsAgo),
        Money("30".toBigDecimal(), usd), MoneyRate(rate.toBigDecimal(), usd), setOf(CostType.BANDWIDTH),
    )

    @Test fun higherHistoricalBurnShortensSafeRunway() {
        val current = sample(0, "1")
        val result = MonitoringCalculator().calculate(current, (1L..60L).map { sample(it * 60, "2") })
        assertEquals(0, result.average!!.rate.amountPerHour.compareTo("2".toBigDecimal()))
        assertEquals(Duration.ofHours(1), result.average.coverage)
        assertEquals(RunwayResult.Available(Duration.ofHours(30)), result.rawRunway)
        assertEquals(RunwayResult.Available(Duration.ofSeconds(49091)), result.safeRunway)
    }

    @Test fun irregularSamplesAreWeightedByTime() {
        val result = MonitoringCalculator().calculate(sample(0, "3"), listOf(sample(120, "1"), sample(30, "3")))
        assertEquals(0, result.average!!.rate.amountPerHour.compareTo("1.5".toBigDecimal()))
        assertEquals(Duration.ofMinutes(2), result.average.coverage)
    }

    @Test fun outageDoesNotExtendOldSampleAcrossAnHour() {
        val result = MonitoringCalculator().calculate(sample(0, "1"), listOf(sample(3600, "2"), sample(60, "1")))
        assertEquals(Duration.ofMinutes(3), result.average!!.coverage)
    }

    @Test fun partialAndOtherAccountSamplesDoNotBecomeZeroBurn() {
        val incomplete = sample(60, "0").copy(unknownCosts = setOf(CostType.COMPUTE))
        val foreign = sample(90, "100").copy(accountId = AccountId("b"))
        val result = MonitoringCalculator().calculate(sample(0, "1"), listOf(sample(120, "2"), incomplete, foreign))
        assertEquals(Duration.ofMinutes(1), result.average!!.coverage)
        assertEquals(0, result.average.rate.amountPerHour.compareTo("2".toBigDecimal()))
    }

    @Test fun missingPricesOrOldBalanceMakeRunwayUnavailable() {
        val current = sample(0, "1")
        val calculator = MonitoringCalculator()
        assertEquals(RunwayResult.Unavailable, calculator.calculate(current.copy(unknownCosts = setOf(CostType.STORAGE)), emptyList()).safeRunway)
        assertEquals(RunwayResult.Unavailable, calculator.calculate(current.copy(balanceObservedAt = now.minusSeconds(121)), emptyList()).safeRunway)
        assertNull(calculator.calculate(current, emptyList()).average)
    }
}
