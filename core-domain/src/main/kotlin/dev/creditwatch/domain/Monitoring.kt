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

/**
 * Where an instance can be reached from outside the provider's network.
 *
 * A telemetry agent running inside a rented instance cannot call home: the desktop is behind
 * NAT, asleep, or on a different network by the time it has something to say. The reachable
 * direction is the other one, so what matters is whether the provider published a port the
 * desktop can poll. It often has not — a port has to be asked for when the instance is
 * created — which is why every field here is optional and absence is an ordinary answer.
 */
data class InstanceEndpoint(
    val publicIp: String? = null,
    val sshHost: String? = null,
    val sshPort: Int? = null,
    /** Container port to the host port the provider published it on. */
    val publishedPorts: Map<Int, Int> = emptyMap(),
) {
    /** True when something outside the provider could open a connection to this instance. */
    val isReachable: Boolean get() = publicIp != null && publishedPorts.isNotEmpty()

    fun hostPortFor(containerPort: Int): Int? = publishedPorts[containerPort]
}

data class CloudInstance(
    val id: InstanceId,
    val providerId: ProviderId,
    val state: InstanceState,
    val computeRate: MoneyRate?,
    val storageRate: MoneyRate?,
    val label: String? = null,
    /** Null when the provider reports no address for it at all. */
    val endpoint: InstanceEndpoint? = null,
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
