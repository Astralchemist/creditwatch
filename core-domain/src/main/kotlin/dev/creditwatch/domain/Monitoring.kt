package dev.creditwatch.domain

import java.time.Instant

@JvmInline value class ProviderId(val value: String)
@JvmInline value class AccountId(val value: String)
@JvmInline value class InstanceId(val value: String)

enum class InstanceState { RUNNING, STOPPED, UNKNOWN }
enum class DataSource { PROVIDER_REPORTED, DERIVED, ESTIMATED }
enum class CostType { COMPUTE, STORAGE, BANDWIDTH }

data class RateComponent(
    val type: CostType,
    val rate: MoneyRate,
    val source: DataSource,
)

data class CloudInstance(
    val id: InstanceId,
    val providerId: ProviderId,
    val state: InstanceState,
    val computeRate: MoneyRate?,
    val storageRate: MoneyRate?,
    val label: String? = null,
)

data class BalanceSnapshot(
    val accountId: AccountId,
    val balance: Money,
    val observedAt: Instant,
)

data class BurnSnapshot(
    val accountId: AccountId,
    val observedAt: Instant,
    val knownRate: MoneyRate,
    val components: List<RateComponent>,
    val unknownCosts: Set<CostType>,
) {
    val isComplete: Boolean get() = unknownCosts.isEmpty()
}
