package dev.creditwatch.app

import dev.creditwatch.domain.*
import dev.creditwatch.provider.*
import dev.creditwatch.vast.VastFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import java.time.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MonitoringControllerTest {
    @Test fun unavailableSecureStorageBlocksConnection(): Unit = runTest {
        val secrets = object : SecretStore {
            override suspend fun get(id: String): CharArray? = throw SecureStorageUnavailableException("Keyring unavailable")
            override suspend fun put(id: String, value: CharArray) = error("Must not save")
            override suspend fun delete(id: String) = error("Must not delete")
        }
        val clock = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC)
        val controller = MonitoringController(secrets, MemoryHistory(), { FakeProvider(clock) },
            clock, StandardTestDispatcher(testScheduler))
        controller.start(); runCurrent()
        assertFalse(controller.secureStorageAvailable)
        assertEquals("Keyring unavailable", controller.state.value.message)
        val key = "test".toCharArray()
        assertNull(controller.connect(key))
        assertTrue(key.all { it == '\u0000' })
        controller.stop()
    }

    @Test fun pollingNeverOverlapsAndManualRequestsDuringSyncAreCoalesced(): Unit = runTest {
        val f = fixture()
        f.provider.instanceDelay = 75_000
        f.controller.start()
        runCurrent()
        repeat(10) { f.controller.refreshNow() }
        advanceTimeBy(75_000); runCurrent()
        assertEquals(1, f.provider.calls)
        assertEquals(1, f.history.samples.size)
        advanceTimeBy(59_999); runCurrent()
        assertEquals(1, f.provider.calls)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, f.provider.calls)
        f.controller.stop()
        val calls = f.provider.calls
        advanceTimeBy(300_000); runCurrent()
        assertEquals(calls, f.provider.calls)
        assertTrue(f.history.closed)
    }

    @Test fun failuresKeepLastSnapshotAndBackoffResetsAfterRecovery(): Unit = runTest {
        val f = fixture()
        f.controller.start(); runCurrent()
        val original = f.controller.state.value.summary
        assertEquals(listOf("1"), f.controller.state.value.instances.map { it.id.value })
        f.provider.failure = VastFailure.Unavailable
        advanceTimeBy(60_000); runCurrent()
        assertEquals(original, f.controller.state.value.summary)
        assertEquals(listOf("1"), f.controller.state.value.instances.map { it.id.value })
        assertTrue(f.controller.state.value.stale)
        assertEquals(SyncStatus.OFFLINE, f.controller.state.value.status)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(f.clock.instant().plusSeconds(120), f.controller.state.value.nextSyncAt)
        assertEquals(1, f.history.samples.size)
        f.provider.failure = null
        advanceTimeBy(120_000); runCurrent()
        assertFalse(f.controller.state.value.stale)
        assertEquals(f.clock.instant().plusSeconds(60), f.controller.state.value.nextSyncAt)
        assertEquals(2, f.history.samples.size)
        f.controller.stop()
    }

    @Test fun retryAfterCannotBeBypassedWithRefresh(): Unit = runTest {
        val f = fixture()
        f.provider.failure = VastFailure.RateLimited(Duration.ofMinutes(20))
        f.controller.start(); runCurrent()
        repeat(10) { f.controller.refreshNow() }
        advanceTimeBy(19 * 60_000); runCurrent()
        assertEquals(1, f.provider.calls)
        f.provider.failure = null
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, f.provider.calls)
        assertEquals(SyncStatus.HEALTHY, f.controller.state.value.status)
        f.controller.stop()
    }

    @Test fun delayedBalanceIsMarkedStaleAndCannotProduceRunway(): Unit = runTest {
        val f = fixture()
        f.provider.accountAgeSeconds = 121
        f.controller.start(); runCurrent()
        assertTrue(f.controller.state.value.stale)
        assertEquals(SyncStatus.DEGRADED, f.controller.state.value.status)
        assertEquals(dev.creditwatch.engine.RunwayResult.Unavailable, f.controller.state.value.summary?.safeRunway)
        f.controller.stop()
    }

    @Test fun startupRestoresStaleSnapshotAndUnauthorizedStopsPolling(): Unit = runTest {
        val f = fixture()
        val sample = sample(f.clock.instant().minusSeconds(3600))
        f.history.save(sample)
        f.provider.failure = VastFailure.Unauthorized
        f.controller.start(); runCurrent()
        assertEquals(sample, f.controller.state.value.summary?.sample)
        assertTrue(f.controller.state.value.stale)
        assertEquals(SyncStatus.AUTH_ERROR, f.controller.state.value.status)
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(1, f.provider.calls)
        f.controller.removeAccount()?.join()
        assertFalse(f.controller.state.value.connected)
        assertNull(f.secrets.key)
        assertTrue(f.history.samples.isEmpty())
        f.controller.stop()
    }

    @Test fun connectionPersistsKeyOnlyAfterValidationAndErasesInput(): Unit = runTest {
        val f = fixture(savedKey = false)
        f.controller.start(); runCurrent()
        f.provider.failure = VastFailure.Unauthorized
        val invalid = "invalid".toCharArray()
        f.controller.connect(invalid)?.join()
        assertNull(f.secrets.key)
        assertTrue(invalid.all { it == '\u0000' })
        f.provider.failure = null
        val valid = "test".toCharArray()
        f.controller.connect(valid)?.join(); runCurrent()
        assertContentEquals("test".toCharArray(), f.secrets.key)
        assertTrue(valid.all { it == '\u0000' })
        assertNotNull(f.controller.state.value.summary)
        f.controller.stop()
    }

    @Test fun exponentialDelayIsCapped() {
        assertEquals(listOf(60L, 60L, 120L, 240L, 480L, 900L, 900L), (0..6).map { MonitoringController.backoff(it).seconds })
    }

    @Test fun lowRunwayNotifiesOnFreshCrossingsOnly(): Unit = runTest {
        val f = fixture()
        val events = mutableListOf<dev.creditwatch.engine.RunwayAlertEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.controller.alertEvents.collect { events += it }
        }
        f.provider.balanceAmount = "5"
        f.controller.start(); runCurrent()
        assertEquals(listOf(6), events.map { it.thresholdHours })
        assertEquals(6, f.history.alerts[AccountId("a")]?.activeThresholdHours)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, events.size)
        f.provider.accountAgeSeconds = 121
        advanceTimeBy(60_000); runCurrent()
        assertTrue(f.controller.state.value.stale)
        assertEquals(1, events.size)
        f.provider.accountAgeSeconds = 0
        f.provider.balanceAmount = "0.50"
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(6, 1), events.map { it.thresholdHours })
        f.controller.stop()
    }

    private fun TestScope.fixture(savedKey: Boolean = true): Fixture {
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.parse("2026-09-19T12:00:00Z").plusMillis(testScheduler.currentTime)
        }
        val history = MemoryHistory()
        val secrets = MemorySecrets(if (savedKey) "test".toCharArray() else null)
        val provider = FakeProvider(clock)
        return Fixture(MonitoringController(secrets, history, { provider }, clock, StandardTestDispatcher(testScheduler)), history, secrets, provider, clock)
    }

    private data class Fixture(val controller: MonitoringController, val history: MemoryHistory, val secrets: MemorySecrets, val provider: FakeProvider, val clock: Clock)
    private class MemorySecrets(var key: CharArray?) : SecretStore {
        override suspend fun put(id: String, value: CharArray) { key = value.copyOf() }
        override suspend fun get(id: String) = key?.copyOf()
        override suspend fun delete(id: String) { key = null }
    }
    private class MemoryHistory : MonitoringHistory {
        val samples = mutableListOf<MonitoringSample>()
        val alerts = mutableMapOf<AccountId, RunwayAlertState>()
        var closed = false
        override fun latest() = samples.maxByOrNull { it.observedAt }
        override fun since(accountId: AccountId, since: Instant) = samples.filter { it.accountId == accountId && it.observedAt >= since }
        override fun save(sample: MonitoringSample) { samples += sample }
        override fun prune(before: Instant) { samples.removeAll { it.observedAt < before } }
        override fun runwayAlert(accountId: AccountId) = alerts[accountId]
        override fun saveRunwayAlert(accountId: AccountId, state: RunwayAlertState) { alerts[accountId] = state }
        override fun clear() { samples.clear(); alerts.clear() }
        override fun close() { closed = true }
    }
    private class FakeProvider(val clock: Clock) : CloudProvider {
        var calls = 0
        var failure: Exception? = null
        var instanceDelay = 0L
        var accountAgeSeconds = 0L
        var balanceAmount = "30"
        override val id = ProviderId("vast")
        override val capabilities = ProviderCapabilities(true, true, true, false)
        override suspend fun validateCredentials() = CredentialValidation.Valid
        override suspend fun getAccountSnapshot(): BalanceSnapshot {
            calls++
            failure?.let { throw it }
            return BalanceSnapshot(AccountId("a"), Money(balanceAmount.toBigDecimal(), CurrencyCode("USD")), clock.instant().minusSeconds(accountAgeSeconds))
        }
        override suspend fun getInstances(): List<CloudInstance> {
            delay(instanceDelay)
            return listOf(CloudInstance(InstanceId("1"), id, InstanceState.RUNNING,
                MoneyRate("1".toBigDecimal(), CurrencyCode("USD")), MoneyRate("0.03".toBigDecimal(), CurrencyCode("USD"))))
        }
    }

    private fun sample(at: Instant) = MonitoringSample(AccountId("a"), at, at,
        Money("30".toBigDecimal(), CurrencyCode("USD")), MoneyRate("1".toBigDecimal(), CurrencyCode("USD")), setOf(CostType.BANDWIDTH))
}
