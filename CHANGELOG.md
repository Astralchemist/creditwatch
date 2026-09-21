# Changelog

## 0.1.0 — unreleased

The first release: one Vast.ai account, watched from the macOS menu bar, answering one
question — how long does the credit last at the current burn.

### Monitoring

- Vast.ai account and instance reads through a read-only provider adapter, refreshed every
  60 seconds with backoff, stale marking, and no overlapping cycles
- Decimal balance, known burn, raw runway, and a safe runway that adds a 10% buffer over the
  higher of the current burn and the one-hour average
- Bounded local history in SQLite, pruned at 72 hours, keyed by account
- `credit` rather than `balance` is read as the spendable figure, which a live account proved
  is the one that matters

### The popover

- The menu bar popover is the whole application; it anchors under the tray icon and there is
  no main window
- The runway headline is coloured by health — green while there is room, amber once a
  threshold trips, red in the last hour — and reads "Out of credit" rather than "0m" when the
  balance is gone
- Balance and burn tiles carry area-filled sparklines over six hours. The fill covers measured
  readings only: forecasts stay dashed and unfilled, gaps break the fill rather than inventing
  one, and a flat series is held at mid-height
- Running instances with the chip icon, their label, and their known hourly compute rate
- System, Dark and Light appearance, saved locally, cycled from one icon

### Alerts

- Low-runway rules at 12h, 6h and 1h with hysteresis and persisted deduplication, delivered as
  native notifications from a bundle with its own identity
- Each threshold switches on and off individually. One the runway has already fallen under
  cannot be armed, because it would fire at once instead of warning early; a threshold armed
  before the fall still fires
- Alerts can also be published to a phone over an ntfy topic paired by QR code, while
  CreditWatch is running

### Credentials

- Keys are held in macOS Keychain, Windows Credential Manager, or Linux Secret Service, and
  connection is blocked when secure storage is unavailable
- Credential buffers are wiped after use, and the adapter's copy is erasable

### Known limits

- **One provider account.** Settings lists RunPod, Lambda and Paperspace and marks them
  unavailable, because only Vast.ai has an adapter. Storage and the burn engine are already
  keyed by account; the session and the popover are not
- **Phone alerts need the app running.** A sleeping computer measures nothing and so sends
  nothing
- **macOS is the verified platform.** Windows and Linux credential stores are implemented and
  unit tested, and the Linux Secret Service round trip passes in a container, but neither
  desktop has been verified at runtime, and `.msi` and `.deb` packaging is untested
- Bandwidth is reported as an unknown cost and excluded from burn
- Instance address and published ports are read where Vast reports them, for a telemetry agent
  that does not exist yet. The field shapes are unverified against a live instance

### Packaging

- macOS `.dmg` builds, installs and runs. Packaging requires a non-Homebrew JDK 21; Compose
  refuses Homebrew's distribution because jpackage produces broken bundles from it
- The bundled runtime explicitly keeps `java.instrument`, `java.management`, `java.prefs`,
  `java.sql` and `jdk.unsupported`. jlink prunes what it cannot see, and the SQLite driver is
  loaded reflectively: without this the installed app opened no database and silently monitored
  nothing, while every test passed
- `scripts/packaged-app-smoke.sh` packages, installs, and checks that the installed binary
  records a real reading
- The macOS bundle version is pinned to 1.0.0 because jpackage rejects a zero major version

No version has been released yet.
