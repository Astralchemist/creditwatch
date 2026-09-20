package dev.creditwatch.vast

import dev.creditwatch.domain.*
import dev.creditwatch.provider.CloudProvider
import dev.creditwatch.provider.CredentialValidation
import dev.creditwatch.provider.ProviderCapabilities
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import io.ktor.utils.io.readAvailable
import java.math.BigDecimal
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

sealed class VastFailure(message: String) : RuntimeException(message) {
    data object Unauthorized : VastFailure("Vast.ai rejected the API key")
    data class RateLimited(val retryAfter: Duration? = null) : VastFailure("Vast.ai rate limit reached")
    data object Unavailable : VastFailure("Vast.ai is unavailable")
    data object InvalidResponse : VastFailure("Vast.ai returned an unexpected response")
}

class VastProvider(
    private val client: HttpClient,
    private val apiKey: String,
    private val clock: Clock = Clock.systemUTC(),
    private val baseUrl: String = "https://console.vast.ai",
) : CloudProvider {
    init {
        val uri = URI(baseUrl)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1")))
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(apiKey.isNotBlank())
    }

    override val id = ProviderId("vast")
    override val capabilities = ProviderCapabilities(true, true, true, false)
    private val json = Json { ignoreUnknownKeys = true }
    private val usd = CurrencyCode("USD")

    override suspend fun validateCredentials(): CredentialValidation = try {
        getAccountSnapshot()
        CredentialValidation.Valid
    } catch (_: VastFailure.Unauthorized) {
        CredentialValidation.Invalid
    }

    override suspend fun getAccountSnapshot(): BalanceSnapshot {
        val body = request("/api/v0/users/current")
        val user = decode<UserDto>(body)
        val balance = user.balance?.decimal() ?: throw VastFailure.InvalidResponse
        return BalanceSnapshot(AccountId("vast:${user.id}"), Money(balance, usd), clock.instant())
    }

    override suspend fun getInstances(): List<CloudInstance> {
        val instances = mutableListOf<CloudInstance>()
        val seenTokens = mutableSetOf<String>()
        var nextToken: String? = null
        repeat(100) {
            val path = if (nextToken == null) "/api/v1/instances?limit=25"
                else "/api/v1/instances?limit=25&after_token=${java.net.URLEncoder.encode(nextToken, Charsets.UTF_8)}"
            val page = decode<InstancesPageDto>(request(path))
            if (page.success != true) throw VastFailure.InvalidResponse
            instances += page.instances.map(::mapInstance)
            val token = page.nextToken?.takeIf(String::isNotBlank) ?: return instances
            if (!seenTokens.add(token)) throw VastFailure.InvalidResponse
            nextToken = token
        }
        throw VastFailure.InvalidResponse
    }

    private fun mapInstance(dto: InstanceDto): CloudInstance {
        val state = when (dto.actualStatus?.lowercase()) {
            "running" -> InstanceState.RUNNING
            "stopped", "exited" -> InstanceState.STOPPED
            else -> InstanceState.UNKNOWN
        }
        return CloudInstance(
            id = InstanceId(dto.id.toString()),
            providerId = id,
            state = state,
            computeRate = dto.pricing?.gpuCostPerHour?.decimal()?.let { MoneyRate(it, usd) },
            storageRate = dto.pricing?.diskHour?.decimal()?.let { MoneyRate(it, usd) },
            label = dto.label,
        )
    }

    private suspend fun request(path: String): String {
        try {
            return client.prepareGet(baseUrl + path) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }.execute { response ->
            when (response.status) {
                HttpStatusCode.OK -> Unit
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> throw VastFailure.Unauthorized
                HttpStatusCode.TooManyRequests -> throw VastFailure.RateLimited(retryAfter(response.headers[HttpHeaders.RetryAfter]))
                else -> throw VastFailure.Unavailable
            }
            val channel = response.bodyAsChannel()
            val bytes = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = channel.readAvailable(chunk, 0, chunk.size)
                if (count < 0) break
                if (bytes.size() + count > 1_000_000) throw VastFailure.InvalidResponse
                bytes.write(chunk, 0, count)
            }
            bytes.toString(StandardCharsets.UTF_8)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: VastFailure) {
            throw failure
        } catch (_: Exception) {
            throw VastFailure.Unavailable
        }
    }

    private fun retryAfter(value: String?): Duration? {
        if (value == null) return null
        value.trim().toLongOrNull()?.let { return Duration.ofSeconds(it.coerceAtLeast(0)) }
        return runCatching {
            Duration.between(clock.instant(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
                .coerceAtLeast(Duration.ZERO)
        }.getOrNull()
    }

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (_: Exception) {
        throw VastFailure.InvalidResponse
    }

    private fun JsonPrimitive.decimal(): BigDecimal = try {
        BigDecimal(content)
    } catch (_: NumberFormatException) {
        throw VastFailure.InvalidResponse
    }

    companion object {
        fun newHttpClient(): HttpClient = HttpClient(CIO) {
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 10_000
            }
        }
    }
}

@Serializable
private data class UserDto(val id: Long, val balance: JsonPrimitive? = null)

@Serializable
private data class InstancesPageDto(
    val success: Boolean? = null,
    val instances: List<InstanceDto>,
    @SerialName("next_token") val nextToken: String? = null,
)

@Serializable
private data class InstanceDto(
    val id: Long,
    @SerialName("actual_status") val actualStatus: String? = null,
    val label: String? = null,
    @SerialName("instance") val pricing: InstancePricingDto? = null,
)

@Serializable
private data class InstancePricingDto(
    val gpuCostPerHour: JsonPrimitive? = null,
    val diskHour: JsonPrimitive? = null,
)
