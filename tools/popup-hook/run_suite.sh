#!/usr/bin/env bash
# Multi-scenario adb activation + UI tree capture for TikTok interruptions.
# Does not require root. Modes: feed, sheet, share, comments, all
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SERIAL="${1:-}"
SUITE="${2:-all}"
PKG="com.zhiliaoapp.musically"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

stamp() { date +%Y%m%d-%H%M%S; }
OUT_ROOT="$ROOT/out/suite-$(stamp)"
mkdir -p "$OUT_ROOT"

ensure_feed() {
  "${ADB[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  sleep 2
  # tap 推荐 tab area (right-upper on 1080x2400-ish)
  "${ADB[@]}" shell input tap 840 190
  sleep 1
  # home tab
  "${ADB[@]}" shell input tap 108 2225
  sleep 1.2
}

capture() {
  local tag="$1"
  bash "$ROOT/scripts/capture_ui_tree.sh" "${SERIAL}" "$OUT_ROOT/$tag" "$tag" | tee -a "$OUT_ROOT/suite.log"
}

activate_sheet() {
  ensure_feed
  # long-press center of video
  "${ADB[@]}" shell input swipe 540 1100 540 1100 1100
  sleep 1.3
}

activate_share() {
  ensure_feed
  # right rail share button ~ lower third (device-dependent; 1080x2249 from dumps)
  "${ADB[@]}" shell input tap 980 1900
  sleep 1.4
}

activate_comments() {
  ensure_feed
  # comment button right rail
  "${ADB[@]}" shell input tap 980 1550
  sleep 1.5
}

close_back() {
  "${ADB[@]}" shell input keyevent KEYCODE_BACK
  sleep 0.6
}

run_one() {
  local mode="$1"
  echo "==== suite mode=$mode ====" | tee -a "$OUT_ROOT/suite.log"
  case "$mode" in
    feed)
      ensure_feed
      capture feed
      ;;
    sheet)
      activate_sheet
      capture sheet
      close_back
      ;;
    share)
      activate_share
      capture share
      close_back
      close_back
      ;;
    comments)
      activate_comments
      capture comments
      close_back
      ;;
    *)
      echo "unknown $mode" >&2
      return 1
      ;;
  esac
}

echo "OUT_ROOT=$OUT_ROOT"
if [[ "$SUITE" == "all" ]]; then
  for m in feed sheet share comments; do
    run_one "$m" || true
  done
else
  run_one "$SUITE"
fi

# summarize all soft/interruption markers
python3 - <<PY
import re, pathlib, json
root = pathlib.Path("$OUT_ROOT")
markers = [
    "以后再说","稍后再说","Not now","Maybe later","跳过","Got it","我知道了",
    "不允许","底部工作表","Bottom sheet","不感兴趣","下载","举报","分享至","转发",
    "添加评论","条评论","关闭","同步通讯录","开启通知",
]
summary = []
for d in sorted(root.iterdir()):
    if not d.is_dir():
        continue
    xml = d / "ui-hierarchy.xml"
    if not xml.exists():
        continue
    t = xml.read_text(encoding="utf-8", errors="ignore")
    labels = re.findall(r'text="([^"]+)"', t) + re.findall(r'content-desc="([^"]+)"', t)
    hits = [m for m in markers if any(m in (L or "") for L in labels)]
    summary.append({"mode": d.name, "hits": hits, "label_count": len([x for x in labels if x.strip()])})
    print(f"{d.name}: hits={hits} labels={summary[-1]['label_count']}")
(root / "suite-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
print("wrote", root / "suite-summary.json")
PY

echo "DONE $OUT_ROOT"
