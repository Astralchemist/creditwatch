CREATE TABLE burn_samples (
    account_id TEXT NOT NULL,
    minute_bucket INTEGER NOT NULL,
    observed_at INTEGER NOT NULL,
    balance_observed_at INTEGER NOT NULL,
    balance_amount TEXT NOT NULL,
    currency TEXT NOT NULL,
    known_per_hour TEXT NOT NULL,
    unknown_costs TEXT NOT NULL,
    PRIMARY KEY (account_id, minute_bucket)
);
CREATE INDEX burn_samples_time ON burn_samples(observed_at);
