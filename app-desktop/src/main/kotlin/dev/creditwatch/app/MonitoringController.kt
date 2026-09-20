package dev.creditwatch.app

import dev.creditwatch.domain.*
import dev.creditwatch.engine.BurnCalculator
import dev.creditwatch.engine.MonitoringCalculator
import dev.creditwatch.engine.MonitoringSummary
import dev.creditwatch.engine.RunwayAlertEvent
import dev.creditwatch.engine.RunwayAlertRule
import dev.creditwatch.provider.CloudProvider
import dev.creditwatch.provider.SecretStore
import dev.creditwatch.vast.VastFailure
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
    val stale: Boolean = true,
    val status: SyncStatus = SyncStatus.DISCONNECTED,
    val message: String = "Loading saved account…",
    val nextSyncAt: Instant? = null,
    val activeRunwayThresholdHours: Int? = null,
)

/** Owns the account session. A single job performs each complete polling cycle. */
class MonitoringController(
    private val secrets: SecretStore?,
    private val history: MonitoringHistory,
    private val providerFactory: (String) -> CloudProvider,
    private val clock: Clock = Clock.systemUTC(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(MonitoringState())
    val state = mutableState.asStateFlow()
    val secureStorageAvailable: Boolean get() = secrets != null
    private val actionPending = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val alertChannel = Channel<RunwayAlertEvent>(Channel.UNLIMITED)
    val alertEvents = alertChannel.receiveAsFlow()
    private val alertRule = RunwayAlertRule()
    private val alertStates = mutableMapOf<AccountId, RunwayAlertState>()
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
                try { provider = providerFactory(String(key)) } finally { key.fill('\u0000') }
                val restored = try {
                    val last = history.latest()
                    history.prune(clock.instant().minus(Duration.ofHours(72)))
                    last?.let { calculator.calculate(it, history.since(it.accountId, it.observedAt.minusSeconds(3720))) }
                } catch (_: Exception) { null }
                mutableState.value = MonitoringState(connected = true, busy = false, summary = restored,
                    stale = true, message = "Showing saved data. Refreshing…")
                beginPolling()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.value = MonitoringState(busy = false, message = "Could not load the saved key securely.")
            }
        }
    }

    fun connect(key: CharArray): Job? {
        if (state.value.busy || state.value.connected || secrets == null || !actionPending.compareAndSet(false, true)) {
            key.fill('\u0000')
            return null
        }
        return scope.launch {
            mutableState.update { it.copy(busy = true, status = SyncStatus.CONNECTING, message = "Connecting to Vast.ai…") }
            try {
                val candidate = providerFactory(String(key))
                val account = candidate.getAccountSnapshot()
                secrets.put(SECRET_ID, key)
                provider = candidate
                mutableState.value = MonitoringState(connected = true, busy = false, message = "Connected. Loading instances…")
                beginPolling(account)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.value = MonitoringState(busy = false, message = failureMessage(failure))
            } finally {
                key.fill('\u0000')
                actionPending.set(false)
            }
        }
    }

    fun refreshNow() {
        val current = state.value
        if (!current.connected || current.busy || current.status == SyncStatus.AUTH_ERROR) return
        if (current.status == SyncStatus.RATE_LIMITED && current.nextSyncAt?.isAfter(clock.instant()) == true) return
        refreshRequests.trySend(Unit)
    }

    fun removeAccount(): Job? {
        if (state.value.busy || !state.value.connected || !actionPending.compareAndSet(false, true)) return null
        return scope.launch {
            mutableState.update { it.copy(busy = true) }
            pollJob?.cancelAndJoin()
            try {
                requireNotNull(secrets).delete(SECRET_ID)
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
        withContext(Dispatchers.IO) { history.close() }
    }

    private fun beginPolling(initialAccount: BalanceSnapshot? = null) {
        check(pollJob?.isActive != true)
        pollJob = scope.launch {
            var failures = 0
            var prefetched = initialAccount
            while (isActive) {
                while (refreshRequests.tryReceive().isSuccess) { /* coalesce requests already waiting */ }
                mutableState.update { it.copy(busy = true, status = SyncStatus.SYNCING, message = "Refreshing Vast.ai…", nextSyncAt = null) }
                var retryAfter: Duration? = null
                try {
                    val source = requireNotNull(provider)
                    val account = prefetched ?: source.getAccountSnapshot()
                    prefetched = null
                    val instances = source.getInstances()
                    val now = clock.instant()
                    val burn = BurnCalculator().calculate(account.accountId, account.balance.currency, instances, now)
                    val sample = MonitoringSample(account.accountId, now, account.observedAt, account.balance, burn.knownRate, burn.unknownCosts)
                    var storageFailed = false
                    val prior = try { history.since(sample.accountId, now.minusSeconds(3720)) }
                        catch (_: Exception) { storageFailed = true; emptyList() }
                    val summary = calculator.calculate(sample, prior)
                    try {
                        history.save(sample)
                        history.prune(now.minus(Duration.ofHours(72)))
                    } catch (_: Exception) { storageFailed = true }
                    failures = 0
                    val incomplete = !sample.hasRequiredRates
                    val oldBalance = account.observedAt.isBefore(now.minusSeconds(120)) || account.observedAt.isAfter(now)
                    val previousAlert = alertStates[sample.accountId] ?: try {
                        history.runwayAlert(sample.accountId)
                    } catch (_: Exception) {
                        storageFailed = true
                        null
                    } ?: RunwayAlertState()
                    val alert = alertRule.evaluate(previousAlert, summary.safeRunway, now,
                        fresh = !oldBalance && !incomplete)
                    alertStates[sample.accountId] = alert.state
                    try { history.saveRunwayAlert(sample.accountId, alert.state) }
                    catch (_: Exception) { storageFailed = true }
                    mutableState.value = MonitoringState(
                        connected = true, busy = false, summary = summary, stale = oldBalance,
                        status = if (storageFailed || incomplete) SyncStatus.DEGRADED else SyncStatus.HEALTHY,
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
                    retryAfter = (failure as? VastFailure.RateLimited)?.retryAfter
                    mutableState.update { it.copy(
                        busy = false, stale = true,
                        status = when (failure) {
                            VastFailure.Unauthorized -> SyncStatus.AUTH_ERROR
                            is VastFailure.RateLimited -> SyncStatus.RATE_LIMITED
                            VastFailure.Unavailable -> SyncStatus.OFFLINE
                            else -> SyncStatus.DEGRADED
                        },
                        message = failureMessage(failure),
                    ) }
                    if (failure is VastFailure.Unauthorized) return@launch
                }
                val wait = maxOf(backoff(failures), retryAfter ?: Duration.ZERO)
                val next = clock.instant().plus(wait)
                mutableState.update { it.copy(nextSyncAt = next) }
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

    private fun failureMessage(failure: Exception): String = when (failure) {
        VastFailure.Unauthorized -> "Vast.ai rejected the key. Remove the account and connect with a valid key."
        is VastFailure.RateLimited -> "Vast.ai rate limit reached. Waiting before retrying."
        VastFailure.InvalidResponse -> "Vast.ai returned incomplete or unreadable data. Retrying automatically."
        VastFailure.Unavailable -> "Vast.ai is unavailable. Retrying automatically."
        else -> "Could not complete the operation. Check secure storage and connectivity."
    }

    companion object {
        private const val SECRET_ID = "vast-default"
        internal fun backoff(failures: Int): Duration =
            Duration.ofSeconds(if (failures <= 1) 60 else minOf(900L, 60L * (1L shl (failures - 1).coerceAtMost(4))))
    }
}
