package dev.creditwatch.persistence

import dev.creditwatch.domain.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.*

class SqliteMonitoringHistoryTest {
    @TempDir lateinit var directory: Path
    private val now = Instant.parse("2026-09-19T12:00:00Z")
    private val usd = CurrencyCode("USD")
    private fun sample(time: Instant = now) = MonitoringSample(
        AccountId("a"), time, time.minusSeconds(2), Money("24.123456789".toBigDecimal(), usd),
        MoneyRate("0.000000321".toBigDecimal(), usd), setOf(CostType.BANDWIDTH, CostType.STORAGE),
    )

    @Test fun decimalValuesAndQualitySurviveRestartAndMigration() {
        val file = directory.resolve("history.db")
        SqliteMonitoringHistory(file).use { it.save(sample()) }
        SqliteMonitoringHistory(file).use {
            assertEquals(sample(), it.latest())
            assertEquals(listOf(sample()), it.since(AccountId("a"), now.minusSeconds(60)))
            assertTrue(it.since(AccountId("b"), now.minusSeconds(60)).isEmpty())
        }
    }

    @Test fun manualRefreshCannotCreateMoreThanOneSamplePerMinute() {
        SqliteMonitoringHistory(directory.resolve("history.db")).use {
            it.save(sample())
            it.save(sample(now.plusSeconds(10)))
            assertEquals(listOf(sample(now.plusSeconds(10))), it.since(AccountId("a"), now.minusSeconds(60)))
        }
    }

    @Test fun retentionAndAccountRemovalDeleteSamples() {
        SqliteMonitoringHistory(directory.resolve("history.db")).use {
            it.save(sample(now.minusSeconds(73 * 3600)))
            it.save(sample())
            it.saveRunwayAlert(AccountId("a"), RunwayAlertState(6, now))
            it.prune(now.minusSeconds(72 * 3600))
            assertEquals(listOf(sample()), it.since(AccountId("a"), Instant.EPOCH))
            it.clear()
            assertNull(it.latest())
            assertNull(it.runwayAlert(AccountId("a")))
        }
    }

    @Test fun alertStateSurvivesRestart() {
        val file = directory.resolve("history.db")
        val state = RunwayAlertState(6, now)
        SqliteMonitoringHistory(file).use { it.saveRunwayAlert(AccountId("a"), state) }
        SqliteMonitoringHistory(file).use {
            assertEquals(state, it.runwayAlert(AccountId("a")))
            assertNull(it.runwayAlert(AccountId("other")))
        }
    }

    @Test fun versionOneDatabaseUpgradesWithoutLosingSamples() {
        val file = directory.resolve("history.db")
        SqliteMonitoringHistory(file).use { it.save(sample()) }
        DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use {
                it.execute("DROP TABLE runway_alert_state")
                it.execute("PRAGMA user_version = 1")
            }
        }
        SqliteMonitoringHistory(file).use {
            assertEquals(sample(), it.latest())
            it.saveRunwayAlert(AccountId("a"), RunwayAlertState(12, now))
            assertEquals(12, it.runwayAlert(AccountId("a"))?.activeThresholdHours)
        }
    }

    @Test fun futureSchemaIsRejectedWithoutRecreatingDatabase() {
        val file = directory.resolve("history.db")
        SqliteMonitoringHistory(file).use { it.save(sample()) }
        DriverManager.getConnection("jdbc:sqlite:$file").use { db -> db.createStatement().use { it.execute("PRAGMA user_version = 3") } }
        SqliteMonitoringHistory(file).use { assertFailsWith<IllegalStateException> { it.latest() } }
        DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM burn_samples").use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) } }
        }
    }
}
