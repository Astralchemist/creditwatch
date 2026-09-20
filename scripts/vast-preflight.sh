#!/usr/bin/env bash
# Checks the live Vast.ai API against exactly what VastProvider parses, before trusting
# CreditWatch with a running instance. Prints verdicts only: never the key, email or ssh key.
#
#   printf '%s' 'YOUR_KEY' > ~/.vast_api_key && chmod 600 ~/.vast_api_key
#   ./scripts/vast-preflight.sh
#
# Or: VAST_API_KEY=... ./scripts/vast-preflight.sh
set -uo pipefail

BASE="${VAST_BASE_URL:-https://console.vast.ai}"
KEY="${VAST_API_KEY:-}"
[ -z "$KEY" ] && [ -f "$HOME/.vast_api_key" ] && KEY="$(tr -d '[:space:]' < "$HOME/.vast_api_key")"
if [ -z "$KEY" ]; then
    echo "No key. Set VAST_API_KEY or write it to ~/.vast_api_key (chmod 600)." >&2
    exit 2
fi
command -v jq >/dev/null || { echo "jq is required: brew install jq" >&2; exit 2; }

pass=0; fail=0
ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fail=$((fail+1)); }
info() { printf '        %s\n' "$1"; }

body=$(mktemp); trap 'rm -f "$body" "$body.i"' EXIT

get() { # path -> echoes status code, body into $body
    curl -sS -o "$body" -w '%{http_code}' \
         -H "Authorization: Bearer $KEY" -H 'Accept: application/json' \
         --max-time 20 "$BASE$1" 2>/dev/null
}

echo
echo "Vast.ai preflight for CreditWatch  ($BASE)"
echo

# 1. Account endpoint -------------------------------------------------------
echo "1. Account  GET /api/v0/users/current/   (VastProvider.getAccountSnapshot)"
code=$(get "/api/v0/users/current/")
info "HTTP $code"
case "$code" in
  200) ok "reachable without a redirect (client has followRedirects=false)";;
  30*) bad "redirect -> VastProvider treats this as Unavailable and never connects"
       info "the path lost its trailing slash";;
  401|403) bad "key rejected (Unauthorized)"
       if grep -q "Two Factor" "$body" 2>/dev/null; then
           info "Vast says: the key lacks privileges and needs a 2FA-authenticated login."
           info "Create a fresh key at https://cloud.vast.ai/manage-keys/ after logging in with 2FA."
       else
           info "Vast says: $(jq -r '.msg // "no message"' "$body" 2>/dev/null | cut -c1-140)"
       fi;;
  *)   bad "unexpected status; VastProvider maps this to Unavailable";;
esac

if [ "$code" = "200" ]; then
    jq -e . "$body" >/dev/null 2>&1 && ok "body is JSON" || bad "body is not JSON -> InvalidResponse"
    jq -e 'has("id")' "$body" >/dev/null 2>&1 \
        && ok 'has "id" (required: UserDto.id is non-null)' \
        || bad 'missing "id" -> InvalidResponse'
    hasb=$(jq -r 'has("balance")' "$body" 2>/dev/null)
    hasc=$(jq -r 'has("credit")'  "$body" 2>/dev/null)
    info "balance present: $hasb    credit present: $hasc"
    if [ "$hasb" = "true" ]; then
        ok 'has "balance" -- the field VastProvider reads'
        info "balance parses as a number: $(jq -r '(.balance|tostring|test("^-?[0-9.]+$"))' "$body")"
    elif [ "$hasc" = "true" ]; then
        bad 'only "credit" is returned; VastProvider reads "balance" -> InvalidResponse on every poll'
        info 'fix: have UserDto accept credit as a fallback'
    else
        bad 'neither "balance" nor "credit" -> InvalidResponse'
    fi
    info "top-level keys: $(jq -r '[keys[]]|join(", ")' "$body" 2>/dev/null)"
fi

# The slashless form must stay broken-by-redirect; assert we are not using it.
codeslashless=$(get "/api/v0/users/current")
info "slashless variant /api/v0/users/current -> HTTP $codeslashless (expected 301)"
echo

# 2. Instances endpoint -----------------------------------------------------
echo "2. Instances  GET /api/v1/instances/?limit=25   (VastProvider.getInstances)"
code=$(get "/api/v1/instances/?limit=25")
info "HTTP $code"
if [ "$code" = "200" ]; then
    ok "reachable"
elif grep -q "Two Factor" "$body" 2>/dev/null; then
    bad "status $code -> Unauthorized; the key needs a 2FA-authenticated login"
else
    bad "status $code -> Unavailable"
    info "Vast says: $(jq -r '.msg // "no message"' "$body" 2>/dev/null | cut -c1-140)"
fi

if [ "$code" = "200" ]; then
    jq -e '.success == true' "$body" >/dev/null 2>&1 \
        && ok '"success": true (VastProvider throws InvalidResponse otherwise)' \
        || bad '"success" is not true -> InvalidResponse'
    jq -e 'has("instances")' "$body" >/dev/null 2>&1 \
        && ok 'has "instances" array' \
        || bad 'missing "instances" -> InvalidResponse'
    n=$(jq -r '.instances|length' "$body" 2>/dev/null)
    info "instances returned: $n    next_token: $(jq -r '.next_token // "null"' "$body")"

    if [ "${n:-0}" -gt 0 ]; then
        jq -e '.instances[0]|has("id")' "$body" >/dev/null 2>&1 \
            && ok 'instance has "id"' || bad 'instance missing "id" -> InvalidResponse'
        st=$(jq -r '.instances[0].actual_status // "null"' "$body")
        info "actual_status: $st"
        case "$(echo "$st" | tr '[:upper:]' '[:lower:]')" in
          running|stopped|exited) ok "actual_status maps to a known InstanceState";;
          *) bad "actual_status \"$st\" maps to UNKNOWN (runway still computes, state shows Unknown)";;
        esac
        jq -e '.instances[0].instance.gpuCostPerHour' "$body" >/dev/null 2>&1 \
            && ok 'instance.gpuCostPerHour present (compute rate)' \
            || bad 'instance.gpuCostPerHour missing -> no burn rate, runway unavailable'
        jq -e '.instances[0].instance.diskHour' "$body" >/dev/null 2>&1 \
            && ok 'instance.diskHour present (storage rate)' \
            || info 'instance.diskHour absent -> storage counted as an unknown cost (by design)'
        info "instance[0] keys under .instance: $(jq -r '.instances[0].instance|[keys[]]|join(", ")' "$body" 2>/dev/null | cut -c1-160)"
        info "your instances: $(jq -r '[.instances[].id]|join(", ")' "$body" 2>/dev/null)"
    else
        bad "no instances returned -- burn rate would be zero and runway unbounded"
    fi
fi

echo
echo "  $pass passed, $fail failed"
[ "$fail" -eq 0 ] && echo "  CreditWatch should connect and compute runway against this account." \
                  || echo "  Fix the failures above before trusting the dashboard."
echo
exit $([ "$fail" -eq 0 ] && echo 0 || echo 1)
