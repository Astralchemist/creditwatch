CREATE TABLE runway_alert_state (
    account_id TEXT PRIMARY KEY,
    threshold_hours INTEGER,
    last_notified_at INTEGER
);
