package dev.creditwatch.app

import dev.creditwatch.domain.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardTrendsTest {
    private val usd = CurrencyCode("USD")
    private val start = Instant.parse("2026-09-20T12:00:00Z")

    @Test fun chartsUseRealReadingsAndLabelChangeOverObservedPeriod() {
        val trends = buildCardTrends(listOf(
            sample(start, "10", "1"),
            sample(start.plusSeconds(60), "9", "2"),
        ))
        assertEquals(BigDecimal("-10.0"), trends.balance.changePercent)
        assertEquals(BigDecimal("100.0"), trends.burn.changePercent)
        assertEquals(1, trends.balance.minutes)
        assertEquals(2, trends.runway.points.size)
        assertTrue(trends.runway.changePercent!! < BigDecimal.ZERO)
    }

    @Test fun unavailableBillingAndStaleBalanceDoNotCreateFalseTrends() {
        val old = sample(start, "10", "1")
        val stale = sample(start.plusSeconds(300), "9", "2")
            .copy(balanceObservedAt = start, unknownCosts = setOf(CostType.COMPUTE))
        val trends = buildCardTrends(listOf(old, stale))
        assertEquals(1, trends.balance.points.size)
        assertEquals(1, trends.burn.points.size)
        assertEquals(1, trends.runway.points.size)
        assertEquals(null, trends.balance.changePercent)
    }

    private fun sample(at: Instant, balance: String, burn: String) = MonitoringSample(
        AccountId("account"), at, at, Money(BigDecimal(balance), usd),
        MoneyRate(BigDecimal(burn), usd), emptySet(),
    )
}
