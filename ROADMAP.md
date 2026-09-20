# Roadmap

Track individual features and bugs as GitHub Issues. Assign each issue to the next release milestone, and link it from the pull request that closes it. Keep this file to release goals and acceptance checks.

## 0.1.0 — dependable Vast monitoring

- [x] JDK 21 multi-module desktop project
- [x] Vast account and instance reads through a provider adapter
- [x] Decimal balance, known burn, raw runway, and safe runway calculations
- [x] Local Keychain credential storage on macOS
- [x] Polling, stale state, backoff, and bounded SQLite history
- [x] Menu bar quick view and searchable providers/actions
- [x] Low-runway rule with persisted deduplication and tray notifications
- [ ] Verify account, billing, and stopped-instance behavior against a real Vast account
- [ ] Add Windows and Linux secure credential stores before claiming support there
- [ ] Verify notifications and tray behavior on each supported OS
- [ ] Test native installers and application restart on each supported OS
- [ ] Complete the manual acceptance cases in the build specification

## 0.2.0 — dashboard and efficiency

- [ ] Configurable 3 × 4 dashboard with saved layout
- [ ] Burn-spike alerts
- [ ] Optional Linux telemetry prototype
- [ ] Transparent idle and low-GPU recommendations

## Release rule

The Gradle `version` is the planned next release. A version is released only after its checklist passes, a changelog entry is written, and a `vX.Y.Z` tag is created. No installer is published automatically yet.
