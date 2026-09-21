#!/usr/bin/env bash
# Checks whether the built .dmg is actually distributable.
#
# A signed build and a distributable build are not the same thing, and the build log cannot
# tell them apart: jpackage signs ad-hoc when no identity is configured and reports success
# either way. An ad-hoc bundle runs on the machine that built it, because a locally produced
# file carries no quarantine flag, and is refused by Gatekeeper on every machine that
# downloads it. The only honest check is the one macOS itself performs, against the artifact.
#
#   ./scripts/verify-signature.sh [path/to/CreditWatch-1.0.0.dmg]
#
# Exits 0 only if the .dmg would install on someone else's Mac.
set -uo pipefail
cd "$(dirname "$0")/.."

pass=0; fail=0
ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fail=$((fail+1)); }

[ "$(uname)" = "Darwin" ] || { echo "macOS only." >&2; exit 2; }

DMG=${1:-$(find app-desktop/build/compose/binaries/main/dmg -name '*.dmg' 2>/dev/null | head -1)}
[ -n "$DMG" ] && [ -f "$DMG" ] || {
    echo "No .dmg found. Build one first: ./gradlew :app-desktop:packageDmg" >&2
    exit 2
}
echo "Artifact: $DMG"

WORK=$(mktemp -d)
trap 'hdiutil detach /Volumes/CreditWatch -quiet 2>/dev/null; rm -rf "$WORK"' EXIT

echo
echo "1. The disk image itself"
hdiutil verify "$DMG" >/dev/null 2>&1 && ok "checksum valid" || bad "checksum invalid"
# Apple staples the ticket to the .dmg, so this is checked before mounting.
if xcrun stapler validate "$DMG" >/dev/null 2>&1; then
    ok "notarization ticket stapled to the .dmg"
else
    bad "no ticket on the .dmg -- run :app-desktop:notarizeDmg"
fi

echo
echo "2. The application inside it"
hdiutil detach /Volumes/CreditWatch -quiet 2>/dev/null
hdiutil attach -nobrowse -quiet "$DMG" || { bad "will not mount"; exit 1; }
APP=/Volumes/CreditWatch/CreditWatch.app

siginfo=$(codesign -dvvv "$APP" 2>&1)
if grep -q "Signature=adhoc" <<<"$siginfo"; then
    bad "ad-hoc signature -- no Developer ID, Gatekeeper will refuse this"
else
    authority=$(grep -m1 "^Authority=" <<<"$siginfo" | cut -d= -f2-)
    ok "signed by: ${authority:-unknown authority}"
fi

team=$(grep -m1 "^TeamIdentifier=" <<<"$siginfo" | cut -d= -f2-)
[ -n "$team" ] && [ "$team" != "not set" ] \
    && ok "team identifier $team" \
    || bad "no team identifier -- the signature traces to no developer"

# Hardened runtime is a precondition for notarization; the Compose plugin passes
# --options runtime, and this confirms the flag survived into the artifact.
grep -q "flags=.*runtime" <<<"$siginfo" \
    && ok "hardened runtime enabled" \
    || bad "hardened runtime missing -- Apple will reject the submission"

codesign --verify --deep --strict "$APP" >/dev/null 2>&1 \
    && ok "signature covers every nested file" \
    || bad "signature does not verify -- something in the bundle is unsigned or altered"

echo
echo "3. The verdict Gatekeeper gives a downloader"
verdict=$(spctl -a -vvv -t exec "$APP" 2>&1)
if grep -q "accepted" <<<"$verdict"; then
    ok "spctl: accepted -- this installs on someone else's Mac"
else
    bad "spctl: $(grep -m1 . <<<"$verdict" | sed 's/^.*: //') -- this does not"
fi

echo
echo "  $pass passed, $fail failed"
exit $([ "$fail" -eq 0 ] && echo 0 || echo 1)
