#!/usr/bin/env bash
# Shows Still's link QR code in the Windows browser, for Signal on the same phone to scan.
# Open Still's "Link to Signal" screen first. Stops once the phone reports it is linked.
set -u
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here/link-code.html}"
zxing="$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.google.zxing/core" -name 'core-*.jar' ! -name '*sources*' | head -1)"
[ -n "$zxing" ] || { echo "zxing not in the Gradle cache; build the app once first" >&2; exit 1; }
last=""; opened=""
while :; do
    log="$(adb.exe logcat -d -s StillLink | tr -d '\r')"
    if grep -q "Linked as device" <<<"$log"; then echo "Linked."; break; fi
    url="$(grep -o 'Link code: .*' <<<"$log" | tail -1 | cut -d' ' -f3-)"
    if [ -n "$url" ] && [ "$url" != "$last" ]; then
        java -cp "$zxing" "$here/QrPage.java" "$url" "$out" && last="$url" && echo "New code shown."
        if [ -z "$opened" ]; then explorer.exe "$(wslpath -w "$out")" >/dev/null 2>&1; opened=1; fi
    fi
    timeout 2 tail -f /dev/null || true
done
