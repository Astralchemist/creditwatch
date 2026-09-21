package dev.creditwatch.provider

import dev.creditwatch.domain.*

data class ProviderCapabilities(
    val providesBalance: Boolean,
    val providesInstancePricing: Boolean,
    val providesStoragePricing: Boolean,
    val providesBandwidthPricing: Boolean,
)

/** Read-only. Every method fails with [ProviderFailure]; a rejected key surfaces as
 *  [ProviderFailure.Unauthorized] from the call that needed it. */
interface CloudProvider {
    val id: ProviderId
    val capabilities: ProviderCapabilities

    suspend fun getAccountSnapshot(): BalanceSnapshot
    suspend fun getInstances(): List<CloudInstance>

    /**
     * Wipes the adapter's own copy of the credential; the instance is unusable afterwards.
     * It cannot reach copies the HTTP stack made while sending requests, so this shortens the
     * key's lifetime in memory rather than removing every trace of it.
     */
    fun eraseCredential() {}
}
