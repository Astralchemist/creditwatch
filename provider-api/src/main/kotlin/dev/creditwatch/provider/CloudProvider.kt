package dev.creditwatch.provider

import dev.creditwatch.domain.*

data class ProviderCapabilities(
    val providesBalance: Boolean,
    val providesInstancePricing: Boolean,
    val providesStoragePricing: Boolean,
    val providesBandwidthPricing: Boolean,
)

sealed interface CredentialValidation {
    data object Valid : CredentialValidation
    data object Invalid : CredentialValidation
}

sealed interface ProviderError {
    data object Unauthorized : ProviderError
    data object RateLimited : ProviderError
    data object NetworkUnavailable : ProviderError
    data object Timeout : ProviderError
    data class InvalidResponse(val reason: String) : ProviderError
    data class ServerError(val statusCode: Int) : ProviderError
}

interface CloudProvider {
    val id: ProviderId
    val capabilities: ProviderCapabilities

    suspend fun validateCredentials(): CredentialValidation
    suspend fun getAccountSnapshot(): BalanceSnapshot
    suspend fun getInstances(): List<CloudInstance>
}
