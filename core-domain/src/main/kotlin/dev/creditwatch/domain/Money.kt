package dev.creditwatch.domain

import java.math.BigDecimal
import java.math.RoundingMode

@JvmInline
value class CurrencyCode(val value: String) {
    init {
        require(value.matches(Regex("[A-Z]{3}"))) { "Currency must be a three-letter ISO code" }
    }
}

data class Money(val amount: BigDecimal, val currency: CurrencyCode) {
    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Cannot add different currencies" }
        return copy(amount = amount.add(other.amount))
    }
}

data class MoneyRate(val amountPerHour: BigDecimal, val currency: CurrencyCode) {
    init {
        require(amountPerHour.signum() >= 0) { "Hourly rate cannot be negative" }
    }

    operator fun plus(other: MoneyRate): MoneyRate {
        require(currency == other.currency) { "Cannot add different currencies" }
        return copy(amountPerHour = amountPerHour.add(other.amountPerHour))
    }

    companion object {
        fun zero(currency: CurrencyCode) = MoneyRate(BigDecimal.ZERO, currency)
    }
}

object MoneyPrecision {
    const val INTERNAL_SCALE = 6
    val ROUNDING: RoundingMode = RoundingMode.HALF_UP
}
