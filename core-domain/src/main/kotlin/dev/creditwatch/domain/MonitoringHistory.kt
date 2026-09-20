package dev.creditwatch.domain

import java.time.Duration
import java.time.Instant

data class RunwayAlertState(
    val activeThresholdHours: Int? = null,
    val lastNotifiedAt: Instant? = null,
)

data class MonitoringSample(
    val accountId: AccountId,
    val observedAt: Instant,
    val balanceObservedAt: Instant,
    val balance: Money,
    val knownRate: MoneyRate,
    val unknownCosts: Set<CostType>,
) {
    init { require(balance.currency == knownRate.currency) }

    val hasRequiredRates: Boolean
        get() = CostType.COMPUTE !in unknownCosts && CostType.STORAGE !in unknownCosts &&
            !balanceObservedAt.isAfter(observedAt) &&
            Duration.between(balanceObservedAt, observedAt) <= Duration.ofMinutes(2)
}

/** Blocking local I/O. Call from an I/O dispatcher. Implementations must serialize access. */
interface MonitoringHistory : AutoCloseable {
    fun latest(): MonitoringSample?
    fun since(accountId: AccountId, since: Instant): List<MonitoringSample>
    fun save(sample: MonitoringSample)
    fun prune(before: Instant)
    fun runwayAlert(accountId: AccountId): RunwayAlertState?
    fun saveRunwayAlert(accountId: AccountId, state: RunwayAlertState)
    fun clear()
}
