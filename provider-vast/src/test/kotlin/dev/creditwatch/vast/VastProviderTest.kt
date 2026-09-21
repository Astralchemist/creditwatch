package dev.creditwatch.vast

import dev.creditwatch.domain.InstanceState
import dev.creditwatch.domain.CostType
import dev.creditwatch.engine.BurnCalculator
import dev.creditwatch.engine.RunwayCalculator
import dev.creditwatch.engine.RunwayResult
import dev.creditwatch.provider.ProviderFailure
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VastProviderTest {
    private val clock = Clock.fixed(Instant.parse("2026-09-19T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun readsAccountAndAllInstancePages(): Unit = runBlocking {
        val paths = mutableListOf<String>()
        val client = mockClient { request ->
            assertEquals("Bearer sample-key", request.headers[HttpHeaders.Authorization])
            paths += request.url.encodedPath + (request.url.encodedQuery.takeIf(String::isNotEmpty)?.let { "?$it" } ?: "")
            when (request.url.encodedPath) {
                "/api/v0/users/current/" -> respond(fixture("user.json"))
                "/api/v1/instances/" -> respond(
                    fixture(if (request.url.parameters["after_token"] == null) "instances-page-1.json" else "instances-page-2.json")
                )
                else -> error("Unexpected path")
            }
        }
        try {
            val provider = VastProvider(client, "sample-key".toCharArray(), clock)
            val account = provider.getAccountSnapshot()
            val instances = provider.getInstances()

            assertEquals(BigDecimal("24.18"), account.balance.amount)
            assertEquals(clock.instant(), account.observedAt)
            assertEquals(3, instances.size)
            assertEquals(InstanceState.STOPPED, instances[1].state)
            assertEquals(BigDecimal("0.02"), instances[1].storageRate?.amountPerHour)
            assertEquals(null, instances[2].storageRate)
            assertTrue(paths.last().contains("after_token=page"))
            // Vast answers 301 to the slashless forms and the client does not follow redirects.
            assertTrue(paths.all { it.substringBefore("?").endsWith("/") }, "every path needs a trailing slash: $paths")
            val burn = BurnCalculator().calculate(account.accountId, account.balance.currency, instances, clock.instant())
            assertEquals(BigDecimal("1.05"), burn.knownRate.amountPerHour)
            assertEquals(setOf(CostType.STORAGE, CostType.BANDWIDTH), burn.unknownCosts)
            assertTrue(RunwayCalculator().safe(account.balance, burn.knownRate, null) is RunwayResult.Available)
        } finally { client.close() }
    }

    @Test
    fun unauthorizedIsReportedWithoutResponseBody(): Unit = runBlocking {
        val client = mockClient { _ -> respond("sensitive body", HttpStatusCode.Unauthorized) }
        try {
            val provider = VastProvider(client, "sample-key".toCharArray(), clock)
            val failure = assertFailsWith<ProviderFailure.Unauthorized> { provider.getAccountSnapshot() }
            assertEquals("The provider rejected the API key", failure.message)
        } finally { client.close() }
    }

    @Test
    fun malformedAccountDoesNotBecomeZeroBalance(): Unit = runBlocking {
        val client = mockClient { _ -> respond("{\"id\":42}") }
        try {
            assertFailsWith<ProviderFailure.InvalidResponse> {
                VastProvider(client, "sample-key".toCharArray(), clock).getAccountSnapshot()
            }
        } finally { client.close() }
    }

    @Test
    fun rateLimitIsASeparateError(): Unit = runBlocking {
        val client = mockClient { _ -> respond("too many", HttpStatusCode.TooManyRequests,
            headersOf(HttpHeaders.RetryAfter, "120")) }
        try {
            val failure = assertFailsWith<ProviderFailure.RateLimited> {
                VastProvider(client, "sample-key".toCharArray(), clock).getAccountSnapshot()
            }
            assertEquals(java.time.Duration.ofSeconds(120), failure.retryAfter)
        } finally { client.close() }
    }

    @Test
    fun accountAcceptsEitherDocumentedBalanceField(): Unit = runBlocking {
        val credit = mockClient { _ -> respond("{\"id\":42,\"credit\":24.18}") }
        try {
            assertEquals(BigDecimal("24.18"),
                VastProvider(credit, "sample-key".toCharArray(), clock).getAccountSnapshot().balance.amount)
        } finally { credit.close() }
        // The live shape: Vast sends both, and only "credit" holds the spendable amount.
        val both = mockClient { _ -> respond("{\"id\":42,\"balance\":0,\"credit\":4.5057355248999995}") }
        try {
            assertEquals(BigDecimal("4.5057355248999995"),
                VastProvider(both, "sample-key".toCharArray(), clock).getAccountSnapshot().balance.amount)
        } finally { both.close() }
        // Only "balance": still read, so a shape without "credit" is not a zero balance.
        val onlyBalance = mockClient { _ -> respond("{\"id\":42,\"balance\":12.50}") }
        try {
            assertEquals(BigDecimal("12.50"),
                VastProvider(onlyBalance, "sample-key".toCharArray(), clock).getAccountSnapshot().balance.amount)
        } finally { onlyBalance.close() }
    }

    @Test
    fun repeatedPaginationTokenIsRejected(): Unit = runBlocking {
        val looping = "{\"success\":true,\"instances\":[],\"next_token\":\"same\"}"
        val client = mockClient { _ -> respond(looping) }
        try {
            assertFailsWith<ProviderFailure.InvalidResponse> {
                VastProvider(client, "sample-key".toCharArray(), clock).getInstances()
            }
        } finally { client.close() }
    }

    @Test
    fun oversizedBodyIsRejectedBeforeItIsBuffered(): Unit = runBlocking {
        val client = mockClient { _ -> respond("{\"id\":42,\"balance\":\"" + "9".repeat(1_000_001) + "\"}") }
        try {
            assertFailsWith<ProviderFailure.InvalidResponse> {
                VastProvider(client, "sample-key".toCharArray(), clock).getAccountSnapshot()
            }
        } finally { client.close() }
    }

    @Test
    fun theKeyIsCopiedOnConstructionAndCanBeErased(): Unit = runBlocking {
        val key = "sample-key".toCharArray()
        val client = mockClient { request ->
            assertEquals("Bearer sample-key", request.headers[HttpHeaders.Authorization])
            respond(fixture("user.json"))
        }
        try {
            val provider = VastProvider(client, key, clock)
            key.fill('\u0000')
            assertEquals(BigDecimal("24.18"), provider.getAccountSnapshot().balance.amount)
            provider.eraseCredential()
            assertFailsWith<IllegalStateException> { provider.getAccountSnapshot() }
        } finally { client.close() }
    }

    @Test
    fun readsPublishedPortsAndAddressesWhenTheProviderReportsThem(): Unit = runBlocking {
        val client = mockClient { respond(fixture("instances-with-endpoint.json")) }
        try {
            val instances = VastProvider(client, "sample-key".toCharArray(), clock).getInstances()
            val served = instances.first { it.id.value == "18421" }
            val endpoint = assertNotNull(served.endpoint)
            assertEquals("203.0.113.41", endpoint.publicIp)
            assertEquals("ssh5.vast.ai", endpoint.sshHost)
            assertEquals(41022, endpoint.sshPort)
            assertEquals(mapOf(8080 to 41234, 22 to 41022), endpoint.publishedPorts)
            assertEquals(41234, endpoint.hostPortFor(8080))
            assertNull(endpoint.hostPortFor(9999))
            assertTrue(endpoint.isReachable)

            // An instance created without spare ports has an address and nothing to poll on it.
            val unreachable = assertNotNull(instances.first { it.id.value == "18422" }.endpoint)
            assertEquals("203.0.113.42", unreachable.publicIp)
            assertTrue(unreachable.publishedPorts.isEmpty())
            assertFalse(unreachable.isReachable)
        } finally { client.close() }
    }

    @Test
    fun anUnexpectedAddressShapeCostsTheEndpointAndNothingElse(): Unit = runBlocking {
        val client = mockClient { respond(fixture("instances-odd-ports.json")) }
        try {
            val instances = VastProvider(client, "sample-key".toCharArray(), clock).getInstances()
            assertEquals(2, instances.size)

            // Ports arriving as a list instead of a map: the rest of the instance still reads.
            val listShaped = instances.first { it.id.value == "18423" }
            assertEquals(BigDecimal("0.5"), listShaped.computeRate?.amountPerHour)
            assertEquals("203.0.113.43", listShaped.endpoint?.publicIp)
            assertTrue(listShaped.endpoint?.publishedPorts.orEmpty().isEmpty())
            assertNull(listShaped.endpoint?.sshPort)

            // A binding with no HostPort is not a port anyone can reach.
            val noHostPort = instances.first { it.id.value == "18424" }
            assertNull(noHostPort.endpoint)
        } finally { client.close() }
    }

    private fun mockClient(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData): HttpClient =
        HttpClient(MockEngine) { engine { addHandler(handler) } }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/vast/$name")).readText()
}
