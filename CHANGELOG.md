# Changelog

## Unreleased

- Vast.ai account and instance reads with local macOS Keychain storage
- Known burn, raw runway, and safe runway with bounded local history
- Menu bar quick view, dashboard, and provider/action search
- Low-runway alerts with persisted deduplication and tray notifications
- Windows Credential Manager and Linux Secret Service credential adapters, with connection blocked when secure storage is unavailable
- One-hour trends in dashboard cards and the menu bar view
- Saved System, Dark, and Light appearance, inline provider/action search, and provider connection guidance
- Linux ARM64 JDK 21 build and live Secret Service round-trip verification; Windows runtime verification remains open
- Compact menu bar view anchored beneath the tray icon, a clearer runway-first dashboard, and running instance rows from provider data
- Windows credential memory is wiped and closed immediately after a key is saved
- A depleted balance says "Out of credit" in red instead of "0m", and the runway headline is coloured by health at every level
- Area-filled trend charts that leave forecasts and gaps unfilled
- Runway alert thresholds are switched individually, with a threshold the runway already sits under refused rather than fired immediately
- Alerts can be published to a phone through an ntfy topic paired by QR code
- Settings lists providers as rows, ready for a second account, and names the ones with no adapter yet
- Runway alert thresholds sit side by side, and appearance is one small icon that cycles System, Dark and Light
- A flat trend series is held at mid-height so its fill reads as steady rather than absent
- Instance address and published ports are read from Vast where reported, for a future telemetry
  agent the desktop polls; unverified against a live instance and parsed so a surprise cannot
  break the instance list

No version has been released yet.
