package dev.creditwatch.engine

import dev.creditwatch.domain.*
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CalculationsTest {
    private val usd = CurrencyCode("USD")
    private val account = AccountId("account-1")
    private val vast = ProviderId("vast")
    private val now = Instant.parse("2026-09-19T00:00:00Z")

    @Test
    fun stoppedInstanceKeepsStorageBurn() {
        val instance = CloudInstance(
            InstanceId("1"), vast, InstanceState.STOPPED,
            rate("0.60"), rate("0.03"),
        )
        val burn = BurnCalculator().calculate(account, usd, listOf(instance), now)

        assertEquals(BigDecimal("0.03"), burn.knownRate.amountPerHour)
        assertEquals(listOf(CostType.STORAGE), burn.components.map { it.type })
        assertEquals(setOf(CostType.BANDWIDTH), burn.unknownCosts)
        assertFalse(burn.isComplete)
    }

    @Test
    fun unknownComputeIsNotPresentedAsZero() {
        val instance = CloudInstance(InstanceId("1"), vast, InstanceState.RUNNING, null, rate("0.03"))
        val burn = BurnCalculator().calculate(account, usd, listOf(instance), now)

        assertEquals(BigDecimal("0.03"), burn.knownRate.amountPerHour)
        assertEquals(setOf(CostType.COMPUTE, CostType.BANDWIDTH), burn.unknownCosts)
    }

    @Test
    fun safeRunwayUsesHigherAverageAndMultiplier() {
        val calculator = RunwayCalculator()
        val balance = Money(BigDecimal("30"), usd)

        assertEquals(
            RunwayResult.Available(Duration.ofHours(50)),
            calculator.raw(balance, rate("0.60")),
        )
        assertEquals(
            RunwayResult.Available(Duration.ofSeconds(146540)),
            calculator.safe(balance, rate("0.60"), rate("0.67")),
        )
    }

    @Test
    fun runwayHandlesZeroDepletedAndMissingValues() {
        val calculator = RunwayCalculator()
        assertEquals(RunwayResult.NoBurn, calculator.raw(Money(BigDecimal.ONE, usd), rate("0")))
        assertEquals(RunwayResult.BalanceDepleted, calculator.raw(Money(BigDecimal.ZERO, usd), rate("1")))
        assertEquals(RunwayResult.Unavailable, calculator.raw(null, rate("1")))
        assertEquals(RunwayResult.Unavailable, calculator.raw(Money(BigDecimal.ONE, usd), null))
    }

    @Test
    fun currenciesCannotBeMixed() {
        assertFailsWith<IllegalArgumentException> {
            Money(BigDecimal.ONE, usd) + Money(BigDecimal.ONE, CurrencyCode("EUR"))
        }
        assertFailsWith<IllegalArgumentException> {
            RunwayCalculator().raw(Money(BigDecimal.ONE, usd), MoneyRate(BigDecimal.ONE, CurrencyCode("EUR")))
        }
    }

    private fun rate(amount: String) = MoneyRate(BigDecimal(amount), usd)
}
