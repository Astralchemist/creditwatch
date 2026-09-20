package dev.creditwatch.vast

import dev.creditwatch.domain.*
import dev.creditwatch.provider.CloudProvider
import dev.creditwatch.provider.ProviderCapabilities
import dev.creditwatch.provider.ProviderFailure
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

class VastProvider(
    private val client: HttpClient,
    apiKey: CharArray,
    private val clock: Clock = Clock.systemUTC(),
    private val baseUrl: String = "https://console.vast.ai",
) : CloudProvider {
    /** Owned copy, so the caller can wipe its buffer as soon as the provider is built. */
    private val apiKey = apiKey.copyOf()

    init {
        val uri = URI(baseUrl)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1")))
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(this.apiKey.any { !it.isWhitespace() && it != '\u0000' })
    }

    override val id = ProviderId("vast")
    override val capabilities = ProviderCapabilities(true, true, true, false)
    private val json = Json { ignoreUnknownKeys = true }
    private val usd = CurrencyCode("USD")

    override suspend fun getAccountSnapshot(): BalanceSnapshot {
        val body = request("/api/v0/users/current")
        val user = decode<UserDto>(body)
        val balance = user.balance?.decimal() ?: throw ProviderFailure.InvalidResponse
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
            if (page.success != true) throw ProviderFailure.InvalidResponse
            instances += page.instances.map(::mapInstance)
            val token = page.nextToken?.takeIf(String::isNotBlank) ?: return instances
            if (!seenTokens.add(token)) throw ProviderFailure.InvalidResponse
            nextToken = token
        }
        throw ProviderFailure.InvalidResponse
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

    override fun eraseCredential() = apiKey.fill('\u0000')

    /** Built per request so no long-lived [String] copy of the key is retained by this adapter. */
    private fun authorization(): String {
        check(apiKey.any { it != '\u0000' }) { "The Vast.ai credential has been erased" }
        return "Bearer " + String(apiKey)
    }

    private suspend fun request(path: String): String {
        val credential = authorization()
        try {
            return client.prepareGet(baseUrl + path) {
                header(HttpHeaders.Authorization, credential)
            }.execute { response ->
            when (response.status) {
                HttpStatusCode.OK -> Unit
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> throw ProviderFailure.Unauthorized
                HttpStatusCode.TooManyRequests -> throw ProviderFailure.RateLimited(retryAfter(response.headers[HttpHeaders.RetryAfter]))
                else -> throw ProviderFailure.Unavailable
            }
            val channel = response.bodyAsChannel()
            val bytes = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = channel.readAvailable(chunk, 0, chunk.size)
                if (count < 0) break
                if (bytes.size() + count > 1_000_000) throw ProviderFailure.InvalidResponse
                bytes.write(chunk, 0, count)
            }
            bytes.toString(StandardCharsets.UTF_8)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ProviderFailure) {
            throw failure
        } catch (_: Exception) {
            throw ProviderFailure.Unavailable
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
        throw ProviderFailure.InvalidResponse
    }

    private fun JsonPrimitive.decimal(): BigDecimal = try {
        BigDecimal(content)
    } catch (_: NumberFormatException) {
        throw ProviderFailure.InvalidResponse
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
