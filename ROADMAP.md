# Roadmap

Track individual features and bugs as GitHub Issues. Assign each issue to the next release milestone, and link it from the pull request that closes it. Keep this file to release goals and acceptance checks.

## 0.1.0 — dependable Vast monitoring

- [x] JDK 21 multi-module desktop project
- [x] Vast account and instance reads through a provider adapter
- [x] Decimal balance, known burn, raw runway, and safe runway calculations
- [x] Local Keychain credential storage on macOS
- [x] Polling, stale state, backoff, and bounded SQLite history
- [x] Menu bar quick view as the only window; search removed with the dashboard it navigated
- [x] Low-runway rule with persisted deduplication and tray notifications
- [ ] Verify account, billing, and stopped-instance behavior against a real Vast account ([#1](https://github.com/Astralchemist/creditwatch/issues/1))
  - [x] Account and billing reads verified live: paths need trailing slashes, and the spendable
    figure is `credit`, not the `balance` the account schema documents
  - [ ] Stopped-instance behavior still unverified
- [x] Implement Windows Credential Manager and Linux Secret Service stores ([#2](https://github.com/Astralchemist/creditwatch/issues/2))
- [ ] Verify Windows ARM64 and x64 runtime compatibility before claiming Windows support ([#9](https://github.com/Astralchemist/creditwatch/issues/9))
- [ ] Verify notifications and tray behavior on each supported OS ([#3](https://github.com/Astralchemist/creditwatch/issues/3))
- [ ] CI passes on macOS, Windows, and Linux ([#4](https://github.com/Astralchemist/creditwatch/issues/4))
- [ ] Test native installers and application restart on each supported OS ([#3](https://github.com/Astralchemist/creditwatch/issues/3))
  - [x] macOS bundle builds with its own bundle id and icon, which notifications depend on
  - [ ] Dmg, Msi and Deb packaging untested
- [ ] Complete the manual acceptance cases in the build specification

## 0.2.0 — telemetry and efficiency

- [ ] Decide the fate of [#5](https://github.com/Astralchemist/creditwatch/issues/5), the
  configurable 3 × 4 dashboard: the dashboard window it extended has been removed in favour of
  the popover, so the issue as written no longer has a surface to live on
- [ ] Burn-spike alerts ([#6](https://github.com/Astralchemist/creditwatch/issues/6))
- [ ] Monitor more than one provider account at once. The settings pane already lists providers
  as rows, but `MonitoringHistory.latest()` takes no account, the controller holds one provider,
  and the headline shows one runway — a second account needs all three
- [ ] Reach a phone while the computer sleeps. Publishing from the desktop (shipped) covers only
  a running app; anything further needs an always-on monitor, which is a decision against the
  "no central backend" principle rather than a feature
- [ ] Optional Linux telemetry prototype ([#7](https://github.com/Astralchemist/creditwatch/issues/7))
- [ ] Transparent idle and low-GPU recommendations ([#8](https://github.com/Astralchemist/creditwatch/issues/8))

## Release rule

The Gradle `version` is the planned next release. A version is released only after its checklist passes, a changelog entry is written, and a `vX.Y.Z` tag is created. No installer is published automatically yet.
