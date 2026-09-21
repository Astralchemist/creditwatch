package dev.creditwatch.notifications

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.isSuccess
import java.security.SecureRandom

/**
 * A phone subscription, addressed by an ntfy topic.
 *
 * CreditWatch holds no account and runs no server of its own, so the phone is reached by
 * publishing to a topic on a public or self-hosted ntfy instance. The topic *is* the address
 * and the permission: anyone who knows it can read the alerts. Nothing else leaks through it —
 * the messages carry hours of runway, never a key, a balance figure or an instance — so it is
 * treated as a private address rather than as a credential, and [randomTopic] makes one long
 * enough that it cannot be guessed.
 *
 * This reaches the phone only while CreditWatch is awake to publish. A sleeping laptop polls
 * nothing and therefore sends nothing; covering that needs an always-on monitor, which is a
 * separate decision.
 */
data class PhoneAlertTarget(val server: String, val topic: String) {
    init {
        require(topic.matches(TOPIC_PATTERN)) { "A topic is 8-64 characters of letters, digits, - or _" }
        require(server.startsWith("http://") || server.startsWith("https://")) {
            "The server must be an http or https URL"
        }
    }

    private val base: String get() = server.trimEnd('/')

    /** Where the phone subscribes; also what the pairing QR code encodes. */
    val subscribeUrl: String get() = "$base/$topic"

    internal val publishUrl: String get() = "$base/$topic"

    companion object {
        const val DEFAULT_SERVER = "https://ntfy.sh"
        private val TOPIC_PATTERN = Regex("[A-Za-z0-9_-]{8,64}")
        private const val ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun isValidTopic(topic: String) = topic.matches(TOPIC_PATTERN)

        /** 24 characters from an unambiguous alphabet: unguessable, still readable aloud. */
        fun randomTopic(random: SecureRandom = SecureRandom()): String =
            (1..24).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    }
}

sealed interface PhoneAlertResult {
    data object Delivered : PhoneAlertResult
    /** The server answered, and said no. */
    data class Rejected(val status: Int) : PhoneAlertResult
    /** The server could not be reached at all. */
    data class Unreachable(val reason: String) : PhoneAlertResult
}

/** Publishes an alert to a phone. Failures are returned, never thrown: a push that does not
 *  arrive must not take the desktop notification down with it. */
class PhoneAlertPublisher(private val http: HttpClient) {

    suspend fun publish(
        target: PhoneAlertTarget,
        title: String,
        body: String,
        urgent: Boolean = false,
    ): PhoneAlertResult = try {
        val response = http.post(target.publishUrl) {
            header("Title", title)
            header("Priority", if (urgent) "urgent" else "high")
            header("Tags", if (urgent) "rotating_light" else "warning")
            setBody(body)
        }
        if (response.status.isSuccess()) PhoneAlertResult.Delivered
        else PhoneAlertResult.Rejected(response.status.value)
    } catch (failure: Exception) {
        PhoneAlertResult.Unreachable(failure.message ?: failure::class.simpleName.orEmpty())
    }
}
