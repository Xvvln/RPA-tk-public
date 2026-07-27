#!/usr/bin/env bash
# 1) Static candidates from unpack (already generated)
# 2) Try Frida attach + activate popup
# 3) Always capture UI tree via adb
#
# Usage:
#   ./tools/popup-hook/run_activate_and_dump.sh [serial] [mode]
# mode: softask | email | sheet | discover
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SERIAL="${1:-}"
MODE="${2:-softask}"
PKG="com.zhiliaoapp.musically"
FRIDA_BIN="${FRIDA_BIN:-}"
if [[ -z "$FRIDA_BIN" ]]; then
  if command -v frida >/dev/null 2>&1; then FRIDA_BIN=frida
  else FRIDA_BIN="/opt/homebrew/Caskroom/miniforge/base/envs/rpa-tt/bin/frida"
  fi
fi

ADB=(adb)
if [[ -n "$SERIAL" ]]; then ADB=(adb -s "$SERIAL"); fi

OUT="$ROOT/out/$(date +%Y%m%d-%H%M%S)-$MODE"
mkdir -p "$OUT"
cp "$ROOT/candidates/from_unpack_46.1.3.json" "$OUT/candidates.json"

echo "== device =="
"${ADB[@]}" get-state
"${ADB[@]}" shell "getprop ro.product.model; getprop ro.build.version.release"

echo "== ensure TikTok foreground =="
"${ADB[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 2

activate_with_frida() {
  local mode="$1"
  # frida-server must already run with enough privilege
  if ! "${ADB[@]}" shell "pidof frida-server" >/dev/null 2>&1; then
    echo "frida-server not running on device"
    return 1
  fi
  if ! "$FRIDA_BIN" -U -n "TikTok" -q -e 'rpc.exports.ping()' 2>/dev/null | rg -q pong; then
    # try by package
    if ! "$FRIDA_BIN" -U -n "$PKG" -q -e '1' 2>/dev/null; then
      echo "cannot attach Frida to TikTok (need root/Magisk or gadget)"
      return 1
    fi
  fi

  local script="$ROOT/scripts/hook_activate.js"
  # spawn helper python to load script, call rpc, exit
  /opt/homebrew/Caskroom/miniforge/base/envs/rpa-tt/bin/python - <<PY
import frida, sys, time
mode = "$mode"
pkg = "$PKG"
script_src = open("$script", encoding="utf-8").read()
device = frida.get_usb_device(timeout=5)
# prefer spawn attach by package name
session = None
for name in ("TikTok", pkg, "com.zhiliaoapp.musically"):
    try:
        session = device.attach(name)
        print("attached", name)
        break
    except Exception as e:
        print("attach fail", name, e)
if session is None:
    sys.exit(2)
script = session.create_script(script_src)
script.on("message", lambda m, d: print("msg", m))
script.load()
time.sleep(0.5)
api = script.exports_sync
print("ping", api.ping())
print("activity", api.activity())
if mode == "email":
    print("activateEmail", api.activate_email())
else:
    print("activateSoftask", api.activate_softask(
        "Lab soft-ask (from unpack CTAs)",
        "Buttons use Not now / 以后再说 from 46.1.3 string pool."))
time.sleep(1.2)
session.detach()
print("frida done")
PY
}

activate_sheet_via_adb() {
  # Real TikTok surface: long-press center to open video options sheet
  echo "activating video options sheet via adb long-press"
  # screen center-ish
  "${ADB[@]}" shell input swipe 540 1200 540 1200 900
  sleep 1.0
}

echo "== mode=$MODE =="
FRIDA_OK=0
case "$MODE" in
  softask|email|discover)
    if activate_with_frida "$MODE"; then FRIDA_OK=1; else
      echo "Frida path failed; falling back to adb sheet long-press for a real TikTok tree"
      activate_sheet_via_adb
    fi
    ;;
  sheet)
    activate_sheet_via_adb
    ;;
  *)
    echo "unknown mode $MODE"; exit 2
    ;;
esac

echo "== capture UI tree =="
bash "$ROOT/scripts/capture_ui_tree.sh" "${SERIAL}" "$OUT" "$MODE" | tee "$OUT/capture.log"

echo "== summary =="
{
  echo "mode=$MODE"
  echo "frida_ok=$FRIDA_OK"
  echo "out=$OUT"
  echo "candidates=$OUT/candidates.json"
  if [[ -f "$OUT/soft-cta-hits.txt" ]]; then
    echo "soft_cta_hits:"
    cat "$OUT/soft-cta-hits.txt"
  fi
} | tee "$OUT/SUMMARY.txt"

echo "DONE $OUT"
