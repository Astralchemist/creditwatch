# Roadmap

Track individual features and bugs as GitHub Issues. Assign each issue to the next release milestone, and link it from the pull request that closes it. Keep this file to release goals and acceptance checks.

## 0.1.0 — dependable Vast monitoring on macOS

Scope: one Vast.ai account, on macOS. Several providers at once is 0.2.0, and the settings pane
already says so on screen. Windows and Linux are 0.2.0 too — their credential stores are written
and unit tested, and the Linux Secret Service round trip passes in a container, but no release
should claim a platform nobody has run it on. The code stays cross-platform; only the promise is
narrowed.

- [x] JDK 21 multi-module desktop project
- [x] Vast account and instance reads through a provider adapter
- [x] Decimal balance, known burn, raw runway, and safe runway calculations
- [x] Local Keychain credential storage on macOS
- [x] Polling, stale state, backoff, and bounded SQLite history
- [x] Menu bar quick view as the only window; search removed with the dashboard it navigated
- [x] Low-runway rule with persisted deduplication and tray notifications
- [x] Each runway threshold switched individually, with one the runway already sits under
  refused rather than fired immediately
- [x] Alerts published to a phone over an ntfy topic paired by QR code, while the app is running
- [x] Settings lists providers as rows and names the ones with no adapter yet
- [ ] Verify account, billing, and stopped-instance behavior against a real Vast account ([#1](https://github.com/Astralchemist/creditwatch/issues/1))
  - [x] Account and billing reads verified live: paths need trailing slashes, and the spendable
    figure is `credit`, not the `balance` the account schema documents
  - [ ] Stopped-instance behavior still unverified
- [x] Implement Windows Credential Manager and Linux Secret Service stores ([#2](https://github.com/Astralchemist/creditwatch/issues/2))
- [x] Verify notifications and tray behavior on macOS ([#3](https://github.com/Astralchemist/creditwatch/issues/3)):
  tray icon, popover anchoring and a real low-runway notification all fired against a live
  account that reached zero
- [ ] CI passes on macOS ([#4](https://github.com/Astralchemist/creditwatch/issues/4))
- [x] macOS installer and application restart ([#3](https://github.com/Astralchemist/creditwatch/issues/3))
  - [x] The bundle carries its own id and icon, which notifications depend on
  - [x] The `.dmg` builds, installs, and records a live reading from the installed copy —
    verified with `scripts/packaged-app-smoke.sh`, not by watching it launch. Packaging needs a
    non-Homebrew JDK 21; Compose refuses Homebrew's, which is why this gate read as untested
  - [x] The bundled runtime keeps the modules jlink cannot infer. `java.sql` was pruned and the
    installed app could not open its database; `java.prefs` would have lost every saved setting
- [ ] Complete the manual acceptance cases in the build specification
  - [x] Installed-app smoke test scripted (`scripts/packaged-app-smoke.sh`): package, install,
    and confirm the installed binary records a reading. A unit suite cannot see this class of
    fault, because it runs on a full JDK

## 0.2.0 — telemetry and efficiency

- [ ] Decide the fate of [#5](https://github.com/Astralchemist/creditwatch/issues/5), the
  configurable 3 × 4 dashboard: the dashboard window it extended has been removed in favour of
  the popover, so the issue as written no longer has a surface to live on
- [ ] Burn-spike alerts ([#6](https://github.com/Astralchemist/creditwatch/issues/6))
- [ ] Monitor more than one provider account at once. The settings pane lists providers as rows
  already, and a second adapter is the smaller half: `MonitoringHistory.latest()` takes no
  account, the controller holds one provider and one `vast-default` secret, and the headline
  shows one runway — a second account needs all four
- [ ] A second provider adapter, RunPod first
- [ ] Windows and Linux as supported platforms, deferred out of 0.1.0 rather than dropped
  - [ ] Windows ARM64 and x64 runtime compatibility ([#9](https://github.com/Astralchemist/creditwatch/issues/9))
  - [ ] Notifications and tray behaviour on Windows and Linux ([#3](https://github.com/Astralchemist/creditwatch/issues/3))
  - [ ] `.msi` and `.deb` packaging, each checked the way macOS was: install it, then watch the
    installed binary record a reading
  - [ ] CI across the full macOS, Windows and Linux matrix ([#4](https://github.com/Astralchemist/creditwatch/issues/4))
- [ ] Telemetry agent paired to the desktop ([#7](https://github.com/Astralchemist/creditwatch/issues/7)).
  The agent is polled by the desktop rather than calling home, since a laptop behind NAT is not
  reachable from a rented instance
  - [x] Map the instance address and published ports the poll would need, parsed defensively
  - [ ] Confirm `public_ipaddr`, `ssh_port` and the `ports` shape against a live instance with
    `scripts/vast-preflight.sh`. If no port can be published, the pull design does not work and
    the agent needs a relay instead
- [ ] Reach a phone while the computer sleeps. Publishing from the desktop shipped in 0.1.0 and
  covers only a running app; anything further needs an always-on monitor, which is a decision
  against the "no central backend" principle rather than a feature
- [ ] Optional Linux telemetry prototype ([#7](https://github.com/Astralchemist/creditwatch/issues/7))
- [ ] Transparent idle and low-GPU recommendations ([#8](https://github.com/Astralchemist/creditwatch/issues/8))

## Release rule

The Gradle `version` is the planned next release. A version is released only after its checklist passes, a changelog entry is written, and a `vX.Y.Z` tag is created. No installer is published automatically yet.
