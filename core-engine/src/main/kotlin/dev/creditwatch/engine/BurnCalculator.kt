package dev.creditwatch.engine

import dev.creditwatch.domain.*
import java.time.Instant

class BurnCalculator {
    fun calculate(
        accountId: AccountId,
        currency: CurrencyCode,
        instances: List<CloudInstance>,
        observedAt: Instant,
        bandwidthRate: MoneyRate? = null,
    ): BurnSnapshot {
        val components = mutableListOf<RateComponent>()
        val unknown = mutableSetOf<CostType>()

        for (instance in instances) {
            when (instance.state) {
                InstanceState.RUNNING -> instance.computeRate?.let {
                    components += RateComponent(CostType.COMPUTE, it, DataSource.PROVIDER_REPORTED)
                } ?: run { unknown += CostType.COMPUTE }
                InstanceState.STOPPED -> Unit
                InstanceState.UNKNOWN -> unknown += CostType.COMPUTE
            }
            instance.storageRate?.let {
                components += RateComponent(CostType.STORAGE, it, DataSource.PROVIDER_REPORTED)
            } ?: run { unknown += CostType.STORAGE }
        }

        bandwidthRate?.let {
            components += RateComponent(CostType.BANDWIDTH, it, DataSource.ESTIMATED)
        } ?: run { unknown += CostType.BANDWIDTH }

        val total = components.fold(MoneyRate.zero(currency)) { sum, component ->
            sum + component.rate
        }
        return BurnSnapshot(accountId, observedAt, total, components, unknown)
    }
}
