package dev.creditwatch.persistence

import dev.creditwatch.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant

class SqliteMonitoringHistory(private val file: Path) : MonitoringHistory {
    private var connection: Connection? = null
    private var closed = false

    private fun database(): Connection {
        check(!closed) { "History is closed" }
        connection?.let { return it }
        Files.createDirectories(file.toAbsolutePath().parent)
        Class.forName("org.sqlite.JDBC")
        val db = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        try {
            db.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout = 5000")
                statement.execute("PRAGMA journal_mode = WAL")
                val version = statement.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
                check(version <= 2) { "History database was created by a newer CreditWatch version" }
                for (target in (version + 1)..2) {
                    db.autoCommit = false
                    try {
                        val resource = when (target) {
                            1 -> "/migrations/001_monitoring_history.sql"
                            2 -> "/migrations/002_runway_alerts.sql"
                            else -> error("Unknown migration")
                        }
                        val migration = requireNotNull(javaClass.getResource(resource)).readText()
                        migration.split(';').filter { it.isNotBlank() }.forEach { statement.execute(it) }
                        statement.execute("PRAGMA user_version = $target")
                        db.commit()
                    } catch (failure: Exception) {
                        db.rollback()
                        throw failure
                    } finally { db.autoCommit = true }
                }
            }
            connection = db
            return db
        } catch (failure: Exception) {
            db.close()
            throw failure
        }
    }

    @Synchronized override fun latest(): MonitoringSample? = database().createStatement().use { statement ->
        statement.executeQuery("SELECT * FROM burn_samples ORDER BY observed_at DESC LIMIT 1").use {
            if (it.next()) it.sample() else null
        }
    }

    @Synchronized override fun since(accountId: AccountId, since: Instant): List<MonitoringSample> =
        database().prepareStatement("SELECT * FROM burn_samples WHERE account_id = ? AND observed_at >= ? ORDER BY observed_at").use { statement ->
            statement.setString(1, accountId.value)
            statement.setLong(2, since.toEpochMilli())
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.sample()) } }
        }

    @Synchronized override fun save(sample: MonitoringSample) {
        database().prepareStatement("""
            INSERT INTO burn_samples VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(account_id, minute_bucket) DO UPDATE SET
                observed_at = excluded.observed_at, balance_observed_at = excluded.balance_observed_at,
                balance_amount = excluded.balance_amount, currency = excluded.currency,
                known_per_hour = excluded.known_per_hour, unknown_costs = excluded.unknown_costs
        """.trimIndent()).use { statement ->
            statement.setString(1, sample.accountId.value)
            statement.setLong(2, Math.floorDiv(sample.observedAt.epochSecond, 60))
            statement.setLong(3, sample.observedAt.toEpochMilli())
            statement.setLong(4, sample.balanceObservedAt.toEpochMilli())
            statement.setString(5, sample.balance.amount.toPlainString())
            statement.setString(6, sample.balance.currency.value)
            statement.setString(7, sample.knownRate.amountPerHour.toPlainString())
            statement.setString(8, sample.unknownCosts.sortedBy { it.name }.joinToString(",") { it.name })
            statement.executeUpdate()
        }
    }

    @Synchronized override fun prune(before: Instant) {
        database().prepareStatement("DELETE FROM burn_samples WHERE observed_at < ?").use {
            it.setLong(1, before.toEpochMilli())
            it.executeUpdate()
        }
    }

    @Synchronized override fun runwayAlert(accountId: AccountId): RunwayAlertState? =
        database().prepareStatement("SELECT threshold_hours, last_notified_at FROM runway_alert_state WHERE account_id = ?").use {
            it.setString(1, accountId.value)
            it.executeQuery().use { row ->
                if (!row.next()) null else {
                    val threshold = row.getInt(1).takeUnless { row.wasNull() }
                    val lastNotified = row.getLong(2).let { value ->
                        if (row.wasNull()) null else Instant.ofEpochMilli(value)
                    }
                    RunwayAlertState(threshold, lastNotified)
                }
            }
        }

    @Synchronized override fun saveRunwayAlert(accountId: AccountId, state: RunwayAlertState) {
        database().prepareStatement("""
            INSERT INTO runway_alert_state(account_id, threshold_hours, last_notified_at) VALUES (?, ?, ?)
            ON CONFLICT(account_id) DO UPDATE SET
                threshold_hours = excluded.threshold_hours,
                last_notified_at = excluded.last_notified_at
        """.trimIndent()).use {
            it.setString(1, accountId.value)
            val threshold = state.activeThresholdHours
            val notified = state.lastNotifiedAt
            if (threshold == null) it.setNull(2, java.sql.Types.INTEGER)
            else it.setInt(2, threshold)
            if (notified == null) it.setNull(3, java.sql.Types.BIGINT)
            else it.setLong(3, notified.toEpochMilli())
            it.executeUpdate()
        }
    }

    @Synchronized override fun clear() {
        val db = database()
        db.autoCommit = false
        try {
            db.createStatement().use {
                it.executeUpdate("DELETE FROM burn_samples")
                it.executeUpdate("DELETE FROM runway_alert_state")
            }
            db.commit()
        } catch (failure: Exception) {
            db.rollback()
            throw failure
        } finally { db.autoCommit = true }
    }

    @Synchronized override fun close() {
        closed = true
        connection?.close()
        connection = null
    }

    private fun ResultSet.sample(): MonitoringSample {
        val currency = CurrencyCode(getString("currency"))
        return MonitoringSample(
            AccountId(getString("account_id")), Instant.ofEpochMilli(getLong("observed_at")),
            Instant.ofEpochMilli(getLong("balance_observed_at")),
            Money(getString("balance_amount").toBigDecimal(), currency),
            MoneyRate(getString("known_per_hour").toBigDecimal(), currency),
            getString("unknown_costs").split(',').filter { it.isNotEmpty() }.map(CostType::valueOf).toSet(),
        )
    }
}
