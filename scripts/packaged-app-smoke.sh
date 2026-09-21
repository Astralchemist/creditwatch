#!/usr/bin/env bash
# Builds the macOS .dmg, installs it, and watches the installed app do its actual job.
#
# This exists because a packaged Compose app can pass every unit test, build cleanly, launch,
# draw its window — and still be broken. jpackage trims the bundled runtime with jlink, and
# jlink cannot see a dependency reached by reflection. When java.sql was pruned, the installed
# app could not open its own database and monitored nothing, silently. Nothing but running the
# installed binary and watching for a written reading would have caught it.
#
#   ./scripts/packaged-app-smoke.sh
#
# Needs a connected account: the check is that the app records a reading, which means it read
# the key, reached the provider, and wrote to SQLite.
set -uo pipefail
cd "$(dirname "$0")/.."

DB="$HOME/Library/Application Support/CreditWatch/creditwatch.db"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"; hdiutil detach /Volumes/CreditWatch -quiet 2>/dev/null' EXIT

pass=0; fail=0
ok()  { printf '  \033[32mPASS\033[0m  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fail=$((fail+1)); }

[ "$(uname)" = "Darwin" ] || { echo "macOS only." >&2; exit 2; }
[ -f "$DB" ] || { echo "No local database. Connect an account in the app first." >&2; exit 2; }

# Compose refuses Homebrew's JDK for packaging (compose-multiplatform#3107).
if [ -z "${JAVA_HOME:-}" ] || [[ "$JAVA_HOME" == *"/homebrew/"* ]]; then
    found=$(/usr/libexec/java_home -v 21 2>/dev/null)
    if [ -z "$found" ] || [[ "$found" == *"/homebrew/"* ]]; then
        echo "Set JAVA_HOME to a non-Homebrew JDK 21 (Microsoft, Temurin, Corretto)." >&2
        exit 2
    fi
    export JAVA_HOME="$found"
fi
echo "JDK: $JAVA_HOME"

echo
echo "1. Package"
./gradlew :app-desktop:packageDmg >"$WORK/build.log" 2>&1 \
    && ok "packageDmg" || { bad "packageDmg -- see $WORK/build.log"; tail -20 "$WORK/build.log"; exit 1; }
DMG=$(find app-desktop/build/compose/binaries/main/dmg -name '*.dmg' | head -1)
[ -n "$DMG" ] && ok "artifact: $(basename "$DMG") ($(du -h "$DMG" | cut -f1))" || { bad "no .dmg produced"; exit 1; }

echo
echo "2. Install"
pkill -f "CreditWatch.app/Contents/MacOS" 2>/dev/null
hdiutil detach /Volumes/CreditWatch -quiet 2>/dev/null
hdiutil attach -nobrowse -quiet "$DMG" && ok "mounts" || { bad "will not mount"; exit 1; }
cp -R /Volumes/CreditWatch/CreditWatch.app "$WORK/" && ok "copies out" || bad "cannot copy"
hdiutil detach /Volumes/CreditWatch -quiet
APP="$WORK/CreditWatch.app"
id=$(/usr/libexec/PlistBuddy -c "Print :CFBundleIdentifier" "$APP/Contents/Info.plist" 2>/dev/null)
[ "$id" = "dev.creditwatch.app" ] \
    && ok "bundle id $id -- notifications depend on it" \
    || bad "bundle id is \"$id\", not dev.creditwatch.app"

echo
echo "3. Run the installed app against the live account"
before=$(sqlite3 "$DB" "select coalesce(max(observed_at),0) from burn_samples;")
"$APP/Contents/MacOS/CreditWatch" >"$WORK/app.log" 2>&1 &
apppid=$!
for _ in $(seq 1 24); do
    sleep 5
    now=$(sqlite3 "$DB" "select coalesce(max(observed_at),0) from burn_samples;")
    [ "$now" != "$before" ] && break
done
kill "$apppid" 2>/dev/null

if [ "$now" != "$before" ]; then
    ok "recorded a reading: $(sqlite3 "$DB" "select datetime(max(observed_at)/1000,'unixepoch','localtime') from burn_samples;")"
else
    bad "no reading in 120s -- the installed app is not monitoring"
fi
if grep -qE "NoClassDefFoundError|ClassNotFoundException|Exception in thread" "$WORK/app.log"; then
    bad "threw on startup (a pruned runtime module looks exactly like this):"
    grep -m3 -E "NoClassDefFoundError|ClassNotFoundException|Exception in thread" "$WORK/app.log" | sed 's/^/        /'
else
    ok "no startup exceptions"
fi

echo
echo "  $pass passed, $fail failed"
exit $([ "$fail" -eq 0 ] && echo 0 || echo 1)
