package dev.creditwatch.app

import dev.creditwatch.engine.RUNWAY_ALERT_THRESHOLDS_HOURS
import dev.creditwatch.notifications.PhoneAlertTarget
import java.util.prefs.Preferences

/**
 * Which alerts the user wants, and where they go.
 *
 * The phone topic lives here rather than in the OS secure store: it is an address, not a
 * credential. It grants reading the alert text — hours of runway — and nothing else, and it
 * has to be readable to be shown as a QR code. The API key stays in the Keychain where it was.
 */
data class AlertPreferences(
    val enabledThresholdHours: Set<Int> = RUNWAY_ALERT_THRESHOLDS_HOURS.toSet(),
    val phoneEnabled: Boolean = false,
    val phoneServer: String = PhoneAlertTarget.DEFAULT_SERVER,
    val phoneTopic: String = "",
) {
    /** The target to publish to, or null when the phone is off or not paired yet. */
    val phoneTarget: PhoneAlertTarget?
        get() = if (!phoneEnabled) null
        else runCatching { PhoneAlertTarget(phoneServer, phoneTopic) }.getOrNull()

    /** The pairing target, whether or not sending is switched on, so the code can be shown first. */
    val pairingTarget: PhoneAlertTarget?
        get() = runCatching { PhoneAlertTarget(phoneServer, phoneTopic) }.getOrNull()
}

object AlertSettings {
    private const val THRESHOLDS = "alertThresholdHours"
    private const val PHONE_ENABLED = "phoneAlertsEnabled"
    private const val PHONE_SERVER = "phoneAlertServer"
    private const val PHONE_TOPIC = "phoneAlertTopic"

    private val preferences by lazy { Preferences.userNodeForPackage(AlertSettings::class.java) }

    fun load(): AlertPreferences = runCatching {
        val stored = preferences.get(THRESHOLDS, null)
        AlertPreferences(
            // An empty string is a user who switched every alert off; absent is a first run.
            enabledThresholdHours = when (stored) {
                null -> RUNWAY_ALERT_THRESHOLDS_HOURS.toSet()
                "" -> emptySet()
                else -> stored.split(',').mapNotNull(String::toIntOrNull)
                    .filter { it in RUNWAY_ALERT_THRESHOLDS_HOURS }.toSet()
            },
            phoneEnabled = preferences.getBoolean(PHONE_ENABLED, false),
            phoneServer = preferences.get(PHONE_SERVER, PhoneAlertTarget.DEFAULT_SERVER),
            phoneTopic = preferences.get(PHONE_TOPIC, ""),
        )
    }.getOrDefault(AlertPreferences())

    fun save(preference: AlertPreferences) {
        runCatching {
            preferences.put(THRESHOLDS, preference.enabledThresholdHours.sorted().joinToString(","))
            preferences.putBoolean(PHONE_ENABLED, preference.phoneEnabled)
            preferences.put(PHONE_SERVER, preference.phoneServer)
            preferences.put(PHONE_TOPIC, preference.phoneTopic)
        }
    }
}
