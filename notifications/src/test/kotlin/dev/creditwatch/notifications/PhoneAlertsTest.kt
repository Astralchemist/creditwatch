package dev.creditwatch.notifications

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.creditwatch.notifications.PhoneAlertPublisher

class PhoneAlertsTest {
    private val target = PhoneAlertTarget("https://ntfy.sh", "abcdefgh12345678")

    @Test
    fun `the subscribe url is the server and topic`() {
        assertEquals("https://ntfy.sh/abcdefgh12345678", target.subscribeUrl)
        assertEquals("https://example.test/abcdefgh12345678",
            PhoneAlertTarget("https://example.test/", "abcdefgh12345678").subscribeUrl)
    }

    @Test
    fun `a topic must be long enough not to be guessed`() {
        assertFalse(PhoneAlertTarget.isValidTopic("short"))
        assertFalse(PhoneAlertTarget.isValidTopic("has spaces in it"))
        assertTrue(PhoneAlertTarget.isValidTopic(PhoneAlertTarget.randomTopic()))
        assertFailsWith<IllegalArgumentException> { PhoneAlertTarget("https://ntfy.sh", "short") }
        assertFailsWith<IllegalArgumentException> { PhoneAlertTarget("ntfy.sh", "abcdefgh12345678") }
    }

    @Test
    fun `generated topics do not repeat`() {
        val topics = (1..50).map { PhoneAlertTarget.randomTopic() }.toSet()
        assertEquals(50, topics.size)
    }

    @Test
    fun `an urgent alert is published to the topic with its title and priority`() = runTest {
        var seen: HttpRequestData? = null
        val http = HttpClient(MockEngine { request ->
            seen = request
            respond(ByteReadChannel("1"), HttpStatusCode.OK)
        })
        val result = PhoneAlertPublisher(http).publish(target, "CreditWatch", "Out of credit.", urgent = true)

        assertEquals(PhoneAlertResult.Delivered, result)
        assertEquals("https://ntfy.sh/abcdefgh12345678", seen?.url.toString())
        assertEquals("CreditWatch", seen?.headers?.get("Title"))
        assertEquals("urgent", seen?.headers?.get("Priority"))
    }

    @Test
    fun `a non-urgent alert drops to high priority`() = runTest {
        var priority: String? = null
        val http = HttpClient(MockEngine { request ->
            priority = request.headers["Priority"]
            respond(ByteReadChannel("1"), HttpStatusCode.OK)
        })
        PhoneAlertPublisher(http).publish(target, "CreditWatch", "Below 6h.")
        assertEquals("high", priority)
    }

    @Test
    fun `a rejected publish reports the status rather than throwing`() = runTest {
        val http = HttpClient(MockEngine { respondError(HttpStatusCode.Forbidden) })
        assertEquals(PhoneAlertResult.Rejected(403),
            PhoneAlertPublisher(http).publish(target, "CreditWatch", "Below 6h."))
    }

    @Test
    fun `an unreachable server reports the reason rather than throwing`() = runTest {
        val http = HttpClient(MockEngine { throw java.io.IOException("no route to host") })
        val result = PhoneAlertPublisher(http).publish(target, "CreditWatch", "Below 6h.")
        assertTrue(result is PhoneAlertResult.Unreachable)
        assertEquals("no route to host", (result as PhoneAlertResult.Unreachable).reason)
    }
}
