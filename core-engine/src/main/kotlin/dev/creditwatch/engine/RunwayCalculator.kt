package dev.creditwatch.engine

import dev.creditwatch.domain.Money
import dev.creditwatch.domain.MoneyPrecision
import dev.creditwatch.domain.MoneyRate
import java.math.BigDecimal
import java.time.Duration

sealed interface RunwayResult {
    data class Available(val duration: Duration) : RunwayResult
    data object NoBurn : RunwayResult
    data object BalanceDepleted : RunwayResult
    data object Unavailable : RunwayResult
}

class RunwayCalculator {
    fun raw(balance: Money?, burn: MoneyRate?): RunwayResult = calculate(balance, burn)

    fun safe(
        balance: Money?,
        currentBurn: MoneyRate?,
        movingAverage: MoneyRate?,
        safetyMultiplier: BigDecimal = BigDecimal("1.10"),
    ): RunwayResult {
        require(safetyMultiplier >= BigDecimal.ONE) { "Safety multiplier must be at least 1" }
        if (currentBurn == null) return calculate(balance, null)
        if (movingAverage != null) {
            require(currentBurn.currency == movingAverage.currency) { "Cannot compare different currencies" }
        }
        val reference = listOfNotNull(currentBurn, movingAverage).maxBy { it.amountPerHour }
        val safeRate = reference.copy(amountPerHour = reference.amountPerHour.multiply(safetyMultiplier))
        return calculate(balance, safeRate)
    }

    private fun calculate(balance: Money?, burn: MoneyRate?): RunwayResult {
        if (balance == null || burn == null) return RunwayResult.Unavailable
        require(balance.currency == burn.currency) { "Cannot calculate runway across currencies" }
        if (balance.amount.signum() <= 0) return RunwayResult.BalanceDepleted
        if (burn.amountPerHour.signum() == 0) return RunwayResult.NoBurn

        val seconds = balance.amount.multiply(BigDecimal(3600))
            .divide(burn.amountPerHour, 0, MoneyPrecision.ROUNDING)
        return RunwayResult.Available(Duration.ofSeconds(seconds.longValueExact()))
    }
}
