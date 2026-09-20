#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" != "inside" ]]; then
    exec dbus-run-session -- bash "$0" inside
fi

export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-$(mktemp -d)}"
chmod 700 "$XDG_RUNTIME_DIR"
eval "$(printf 'creditwatch-disposable-keyring-password\n' | gnome-keyring-daemon --login --components=secrets)"
eval "$(gnome-keyring-daemon --start --components=secrets)"
export GNOME_KEYRING_CONTROL
export CREDITWATCH_SECRET_STORE_SMOKE=1
./gradlew :app-desktop:test --tests '*LinuxSecretServiceIntegrationTest' --no-daemon --console=plain
