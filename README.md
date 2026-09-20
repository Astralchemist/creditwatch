# CreditWatch

CreditWatch is a local-first desktop utility for monitoring prepaid cloud credits. It aims to show your balance, known burn rate, estimated runway, and obvious signs of underused resources in a compact dashboard. Vast.ai is the first provider.

**Status:** Private development. On macOS, the app can connect with a Vast.ai key, save it in Keychain, refresh automatically every 60 seconds, and show balance, known burn, and runway from known costs. Samples are stored locally in SQLite for 72 hours. The one-hour burn average feeds safe runway when enough recent samples exist. The app opens a compact menu bar view and has a searchable provider/action palette. Fresh low-runway readings trigger 12h, 6h, and 1h alerts with persisted deduplication and tray notifications. Provider and monitoring tests use fake data. Real-account verification, burn-spike alerts, configurable tiles, and Windows/Linux key storage remain to be built.

## Principles

- Credentials stay on the user's computer in operating-system secure storage.
- Cloud access is read-only; V1 does not create, stop, or delete resources.
- Distinguish provider-reported values from measured and estimated values.
- Keep provider-specific formats out of the calculation engine.
- Telemetry is optional. Billing and runway remain useful without an agent.
- The dashboard has at most twelve equal-sized tiles.
- No CreditWatch account, central backend, or mandatory analytics.

## Technology

Kotlin/JVM and Java 21, Gradle, Compose Multiplatform Desktop, Ktor Client, Kotlin coroutines, and SQLite/JDBC. Java source can live beside Kotlin source in each module under `src/main/java`. Dependencies for later milestones will be added when their implementation begins.

## Get started

Install JDK 21, then from the repository root:

```sh
./gradlew test
./gradlew :app-desktop:run
```

On macOS, look for the CreditWatch pulse icon near the right end of the menu bar. Click it for the compact view; its menu opens the full dashboard, refreshes data, or quits the app. Search providers and actions from either view with **⌘K**, or click **Search**. On Windows and Linux, where a system tray is available, the same tray workflow is used. If no tray is available, the full dashboard opens directly.

On this Mac, Homebrew installed JDK 21 at `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`. If your shell still selects another JDK, set `JAVA_HOME` to that path before running the commands. Open the root directory as a Gradle project in IntelliJ IDEA and select JDK 21 as the Gradle JVM. The build requests a JDK 21 toolchain.

## Modules

| Module | Responsibility |
| --- | --- |
| `core-domain` | Provider-neutral values and snapshots |
| `provider-api` | Read-only provider contract |
| `provider-vast` | Vast.ai client and mapping |
| `core-engine` | Burn, runway, alert, and efficiency calculations |
| `persistence` | SQLite schema, migrations, repositories |
| `notifications` | Desktop notifications and deduplication |
| `telemetry-api` | Optional telemetry contract |
| `telemetry-agent` | Independently runnable Linux agent, later |
| `app-desktop` | Compose UI and application wiring |
| `test-fixtures` | Provider response fixtures |

The app is the composition root. The core modules must not depend on Compose, HTTP, SQLite, or Vast.ai. Provider DTOs stay inside `provider-vast`.

## Build order

1. **Skeleton:** launchable desktop window, modules, test setup.
2. **Vast connection:** minimum-permission API key, OS secret store, validation, account and instance reads.
3. **First useful view:** balance, known burn, raw runway, and safe runway with clear data quality labels.
4. **Monitoring:** polling, bounded history, stale/offline state, backoff.
5. **Alerts:** low-runway rules, hysteresis, persisted deduplication, and tray notifications are in place; burn-spike and efficiency alerts remain.
6. **Dashboard and tray:** compact tray view and search are in place; twelve configurable tiles remain.
7. **Optional telemetry:** Linux agent and transparent efficiency hints.
8. **Packaging:** native artifacts and release checks.

The first vertical slice is API key → Vast account and instances → known burn → planning runway → three cards. The adapter currently uses the [Vast OpenAPI specification](https://github.com/vast-ai/docs/blob/main/api-reference/openapi.yaml) for `/api/v0/users/current` and paginated `/api/v1/instances`. Missing compute or storage prices make runway unavailable; bandwidth is displayed as excluded.

## Cost accuracy

Balances use decimal money, not floating point. Rates normalize to hourly values. Unknown bandwidth cost stays unknown; a known partial burn is never shown as an exact total. A stopped Vast instance may still incur storage charges. Safe runway uses the greater of current burn and the available one-hour moving average, multiplied by a safety factor of 1.10. History gaps do not count as zero burn. The factor is fixed for now; it will become configurable in settings. Runway excludes unprojected bandwidth and is an estimate, not a guarantee.

## Original specification

The full product specification was supplied in the project discussion. This README captures the product boundary and starting architecture; detailed alert thresholds, telemetry transport, persistence schema, acceptance cases, and release criteria will be implemented in their respective milestones.
# creditwatch
