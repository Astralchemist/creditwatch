package dev.creditwatch.app

import dev.creditwatch.domain.*
import dev.creditwatch.engine.BurnCalculator
import dev.creditwatch.engine.MonitoringCalculator
import dev.creditwatch.engine.MonitoringSummary
import dev.creditwatch.engine.RUNWAY_ALERT_THRESHOLDS_HOURS
import dev.creditwatch.engine.RunwayAlertEvaluation
import dev.creditwatch.engine.RunwayAlertEvent
import dev.creditwatch.engine.RunwayAlertRule
import dev.creditwatch.provider.CloudProvider
import dev.creditwatch.provider.ProviderFailure
import dev.creditwatch.provider.SecretStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

enum class SyncStatus { DISCONNECTED, CONNECTING, SYNCING, HEALTHY, OFFLINE, AUTH_ERROR, RATE_LIMITED, DEGRADED }

data class MonitoringState(
    val connected: Boolean = false,
    val busy: Boolean = true,
    val summary: MonitoringSummary? = null,
    val instances: List<CloudInstance> = emptyList(),
    val stale: Boolean = true,
    val status: SyncStatus = SyncStatus.DISCONNECTED,
    val message: String = "Loading saved account…",
    val nextSyncAt: Instant? = null,
    /** True while [nextSyncAt] is a failure backoff that a manual refresh must not shorten. */
    val backingOff: Boolean = false,
    val activeRunwayThresholdHours: Int? = null,
    val trends: CardTrends = CardTrends(),
)

/** Owns the account session. A single job performs each complete polling cycle. */
class MonitoringController(
    private val secrets: SecretStore?,
    private val history: MonitoringHistory,
    private val providerFactory: (CharArray) -> CloudProvider,
    private val clock: Clock = Clock.systemUTC(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    alertThresholdHours: Set<Int> = RUNWAY_ALERT_THRESHOLDS_HOURS.toSet(),
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(MonitoringState())
    val state = mutableState.asStateFlow()
    private val storageReady = AtomicBoolean(secrets != null)
    val secureStorageAvailable: Boolean get() = storageReady.get()
    private val actionPending = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val alertChannel = Channel<RunwayAlertEvent>(Channel.UNLIMITED)
    val alertEvents = alertChannel.receiveAsFlow()
    /** Null once every threshold is switched off. Read by the poll loop, written from the UI. */
    @Volatile private var alertRule: RunwayAlertRule? = ruleFor(alertThresholdHours)
    private val alertStates = mutableMapOf<AccountId, RunwayAlertState>()
    /** Set when the armed set changes, so the next cycle ignores the state the old set left. */
    private val alertStateReset = AtomicBoolean(false)
    private var pollJob: Job? = null
    private var provider: CloudProvider? = null
    private val calculator = MonitoringCalculator()

    fun start(): Job? {
        if (!started.compareAndSet(false, true)) return null
        return scope.launch {
            try {
                val key = secrets?.get(SECRET_ID)
                if (key == null) {
                    mutableState.value = MonitoringState(busy = false, message = if (secrets == null)
                        "Secure key storage is not available on this system yet." else "Connect Vast.ai to begin.")
                    return@launch
                }
                try { provider = providerFactory(key) } finally { key.fill('\u0000') }
                val restored = try {
                    val last = history.latest()
                    history.prune(clock.instant().minus(Duration.ofHours(72)))
                    last?.let {
                        val samples = history.since(it.accountId, it.observedAt.minus(HISTORY_WINDOW))
                        calculator.calculate(it, samples) to buildCardTrends(samples + it)
                    }
                } catch (_: Exception) { null }
                mutableState.value = MonitoringState(connected = true, busy = false,
                    summary = restored?.first, trends = restored?.second ?: CardTrends(),
                    stale = true, status = SyncStatus.SYNCING,
                    message = "Showing saved data. Refreshing…")
                beginPolling()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is SecureStorageUnavailableException) storageReady.set(false)
                mutableState.value = MonitoringState(busy = false, message =
                    if (failure is SecureStorageUnavailableException) failure.message!!
                    else "Could not load the saved key securely.")
            }
        }
    }

    fun connect(key: CharArray): Job? {
        if (state.value.busy || state.value.connected || !secureStorageAvailable || !actionPending.compareAndSet(false, true)) {
            key.fill('\u0000')
            return null
        }
        return scope.launch {
            mutableState.update { it.copy(busy = true, status = SyncStatus.CONNECTING, message = "Connecting to Vast.ai…") }
            var candidate: CloudProvider? = null
            try {
                candidate = providerFactory(key)
                val account = candidate.getAccountSnapshot()
                requireNotNull(secrets).put(SECRET_ID, key)
                provider?.eraseCredential()
                provider = candidate
                candidate = null // adopted; the finally below must not wipe it
                mutableState.value = MonitoringState(connected = true, busy = false,
                    status = SyncStatus.SYNCING, message = "Connected. Loading instances…")
                beginPolling(account)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure is SecureStorageUnavailableException) storageReady.set(false)
                mutableState.value = MonitoringState(busy = false,
                    status = statusFor(failure, SyncStatus.DISCONNECTED), message = failureMessage(failure))
            } finally {
                candidate?.eraseCredential()
                key.fill('\u0000')
                actionPending.set(false)
            }
        }
    }

    /**
     * Replaces the armed thresholds. Switching one off clears the state behind it, so arming
     * it again later warns afresh rather than staying silent on a threshold already marked
     * as notified.
     *
     * The stored row outlives this call, so clearing the cache alone is not enough: the next
     * cycle would read the old threshold straight back out of it and warn about a mark that is
     * no longer armed. The flag makes that cycle start from nothing and overwrite the row.
     */
    fun setAlertThresholds(hours: Set<Int>) {
        alertRule = ruleFor(hours)
        alertStates.clear()
        alertStateReset.set(true)
        if (hours.isEmpty()) {
            mutableState.update { it.copy(activeRunwayThresholdHours = null) }
        }
    }

    fun refreshNow() {
        val current = state.value
        if (!current.connected || current.busy || current.status == SyncStatus.AUTH_ERROR) return
        // Every failure backs off, not just rate limiting, so gate on the flag rather than the status.
        if (current.backingOff && current.nextSyncAt?.isAfter(clock.instant()) == true) return
        refreshRequests.trySend(Unit)
    }

    fun removeAccount(): Job? {
        if (state.value.busy || !state.value.connected || !actionPending.compareAndSet(false, true)) return null
        return scope.launch {
            mutableState.update { it.copy(busy = true) }
            pollJob?.cancelAndJoin()
            try {
                requireNotNull(secrets).delete(SECRET_ID)
                provider?.eraseCredential() // after the delete: the catch below resumes polling
                provider = null
                val cleared = runCatching { history.clear() }.isSuccess
                alertStates.clear()
                while (alertChannel.tryReceive().isSuccess) { /* discard account notifications */ }
                mutableState.value = MonitoringState(busy = false, message = if (cleared)
                    "Account and local history removed." else "Key removed. Local history could not be cleared.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(busy = false, message = "Could not remove the key from secure storage.") }
                beginPolling()
            } finally { actionPending.set(false) }
        }
    }

    suspend fun stop() {
        scope.coroutineContext.job.cancelAndJoin()
        provider?.eraseCredential()
        withContext(Dispatchers.IO) { history.close() }
    }

    private fun beginPolling(initialAccount: BalanceSnapshot? = null) {
        check(pollJob?.isActive != true)
        pollJob = scope.launch {
            var failures = 0
            var prefetched = initialAccount
            while (isActive) {
                while (refreshRequests.tryReceive().isSuccess) { /* coalesce requests already waiting */ }
                mutableState.update { it.copy(busy = true, status = SyncStatus.SYNCING,
                    message = "Refreshing Vast.ai…", nextSyncAt = null, backingOff = false) }
                // status, nextSyncAt and backingOff are published together: a gap between them
                // lets refreshNow() see a failure with no deadline yet and skip the backoff.
                var next = clock.instant()
                try {
                    val source = requireNotNull(provider)
                    val account = prefetched ?: source.getAccountSnapshot()
                    prefetched = null
                    val instances = source.getInstances()
                    val now = clock.instant()
                    val burn = BurnCalculator().calculate(account.accountId, account.balance.currency, instances, now)
                    val sample = MonitoringSample(account.accountId, now, account.observedAt, account.balance, burn.knownRate, burn.unknownCosts)
                    var storageFailed = false
                    val prior = try { history.since(sample.accountId, now.minus(HISTORY_WINDOW)) }
                        catch (_: Exception) { storageFailed = true; emptyList() }
                    val summary = calculator.calculate(sample, prior)
                    try {
                        history.save(sample)
                        history.prune(now.minus(Duration.ofHours(72)))
                    } catch (_: Exception) { storageFailed = true }
                    failures = 0
                    val incomplete = !sample.hasRequiredRates
                    val oldBalance = account.observedAt.isBefore(now.minusSeconds(120)) || account.observedAt.isAfter(now)
                    val previousAlert = if (alertStateReset.getAndSet(false)) RunwayAlertState()
                        else alertStates[sample.accountId] ?: try {
                            history.runwayAlert(sample.accountId)
                        } catch (_: Exception) {
                            storageFailed = true
                            null
                        } ?: RunwayAlertState()
                    val alert = alertRule?.evaluate(previousAlert, summary.safeRunway, now,
                        fresh = !oldBalance && !incomplete)
                        ?: RunwayAlertEvaluation(RunwayAlertState(), null)
                    alertStates[sample.accountId] = alert.state
                    try { history.saveRunwayAlert(sample.accountId, alert.state) }
                    catch (_: Exception) { storageFailed = true }
                    next = clock.instant().plus(backoff(failures))
                    mutableState.value = MonitoringState(
                        connected = true, busy = false, summary = summary, stale = oldBalance,
                        instances = instances,
                        trends = buildCardTrends(prior + sample),
                        status = if (storageFailed || incomplete) SyncStatus.DEGRADED else SyncStatus.HEALTHY,
                        nextSyncAt = next,
                        activeRunwayThresholdHours = alert.state.activeThresholdHours,
                        message = when {
                            storageFailed -> "Live data loaded. Local history is unavailable."
                            incomplete -> "Partial or delayed billing data. Runway is unavailable."
                            else -> "Monitoring Vast.ai • refresh every 60 seconds"
                        },
                    )
                    alert.event?.let { alertChannel.trySend(it) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    prefetched = null
                    failures = (failures + 1).coerceAtMost(6)
                    val retryAfter = (failure as? ProviderFailure.RateLimited)?.retryAfter
                    next = clock.instant().plus(maxOf(backoff(failures), retryAfter ?: Duration.ZERO))
                    mutableState.update { it.copy(
                        busy = false, stale = true,
                        status = statusFor(failure, SyncStatus.DEGRADED),
                        message = failureMessage(failure),
                        nextSyncAt = next, backingOff = true,
                    ) }
                    if (failure is ProviderFailure.Unauthorized) return@launch
                }
                // Wake periodically only to mark aging data. Never decrement a saved runway.
                while (isActive && clock.instant() < next) {
                    val remaining = Duration.between(clock.instant(), next).toMillis().coerceAtLeast(1)
                    if (withTimeoutOrNull(minOf(15_000L, remaining)) { refreshRequests.receive(); true } == true) break
                    mutableState.update { state ->
                        val old = state.summary?.sample?.balanceObservedAt?.isBefore(clock.instant().minusSeconds(120)) == true
                        if (old) state.copy(stale = true) else state
                    }
                }
            }
        }
    }

    /** [fallback] covers non-provider failures: secure storage and anything unexpected. */
    private fun statusFor(failure: Exception, fallback: SyncStatus): SyncStatus = when (failure) {
        ProviderFailure.Unauthorized -> SyncStatus.AUTH_ERROR
        is ProviderFailure.RateLimited -> SyncStatus.RATE_LIMITED
        ProviderFailure.Unavailable -> SyncStatus.OFFLINE
        is ProviderFailure -> SyncStatus.DEGRADED
        else -> fallback
    }

    private fun failureMessage(failure: Exception): String = when (failure) {
        ProviderFailure.Unauthorized -> "Vast.ai rejected the key. Remove the account and connect with a valid key."
        is ProviderFailure.RateLimited -> "Vast.ai rate limit reached. Waiting before retrying."
        ProviderFailure.InvalidResponse -> "Vast.ai returned incomplete or unreadable data. Retrying automatically."
        ProviderFailure.Unavailable -> "Vast.ai is unavailable. Retrying automatically."
        is SecureStorageUnavailableException -> failure.message!!
        else -> "Could not complete the operation. Check secure storage and connectivity."
    }

    companion object {
        private const val SECRET_ID = "vast-default"

        private fun ruleFor(hours: Set<Int>): RunwayAlertRule? =
            hours.takeIf { it.isNotEmpty() }?.let { RunwayAlertRule(it.sorted()) }

        /** Covers the chart window plus the slack the one-hour burn average needs at its edge. */
        private val HISTORY_WINDOW: Duration = TREND_WINDOW.plusMinutes(2)
        internal fun backoff(failures: Int): Duration =
            Duration.ofSeconds(if (failures <= 1) 60 else minOf(900L, 60L * (1L shl (failures - 1).coerceAtMost(4))))
    }
}
