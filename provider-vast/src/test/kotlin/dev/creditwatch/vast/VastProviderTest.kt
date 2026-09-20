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
        // Both present: the field the account schema documents wins.
        val both = mockClient { _ -> respond("{\"id\":42,\"balance\":10.00,\"credit\":99.99}") }
        try {
            assertEquals(BigDecimal("10.00"),
                VastProvider(both, "sample-key".toCharArray(), clock).getAccountSnapshot().balance.amount)
        } finally { both.close() }
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

    private fun mockClient(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData): HttpClient =
        HttpClient(MockEngine) { engine { addHandler(handler) } }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/vast/$name")).readText()
}
