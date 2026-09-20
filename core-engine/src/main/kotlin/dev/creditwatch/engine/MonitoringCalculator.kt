package dev.creditwatch.engine

import dev.creditwatch.domain.MonitoringSample
import dev.creditwatch.domain.MoneyRate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

data class BurnAverage(val rate: MoneyRate, val coverage: Duration)

data class MonitoringSummary(
    val sample: MonitoringSample,
    val rawRunway: RunwayResult,
    val safeRunway: RunwayResult,
    val average: BurnAverage?,
)

class MonitoringCalculator {
    private val runway = RunwayCalculator()

    fun calculate(current: MonitoringSample, history: List<MonitoringSample>): MonitoringSummary {
        val average = average(current, history)
        val rate = current.knownRate.takeIf { current.hasRequiredRates }
        return MonitoringSummary(
            current,
            runway.raw(current.balance, rate),
            runway.safe(current.balance, rate, average?.rate),
            average,
        )
    }

    private fun average(current: MonitoringSample, history: List<MonitoringSample>): BurnAverage? {
        if (!current.hasRequiredRates) return null
        val end = current.observedAt
        val start = end.minus(Duration.ofHours(1))
        val samples = (history + current).filter {
            it.accountId == current.accountId && it.balance.currency == current.balance.currency && it.observedAt <= end
        }.distinctBy { it.observedAt }.sortedBy { it.observedAt }
        var weighted = BigDecimal.ZERO
        var coveredMillis = 0L
        for ((sample, next) in samples.zipWithNext()) {
            // A sample is held for at most two minutes; outages never extend its coverage.
            if (!sample.hasRequiredRates || sample.unknownCosts != current.unknownCosts) continue
            val from = maxOf(start, sample.observedAt)
            val until = minOf(end, next.observedAt, sample.observedAt.plusSeconds(120))
            if (until <= from) continue
            val millis = Duration.between(from, until).toMillis()
            weighted += sample.knownRate.amountPerHour.multiply(BigDecimal.valueOf(millis))
            coveredMillis += millis
        }
        if (coveredMillis < 60_000) return null
        return BurnAverage(
            MoneyRate(weighted.divide(BigDecimal.valueOf(coveredMillis), 12, RoundingMode.HALF_UP), current.balance.currency),
            Duration.ofMillis(coveredMillis),
        )
    }
}
