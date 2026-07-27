#!/usr/bin/env bash
# Dump current UI hierarchy + screenshot via adb.
set -euo pipefail
SERIAL="${1:-}"
OUT_DIR="${2:-}"
TAG="${3:-capture}"

ADB=(adb)
if [[ -n "$SERIAL" ]]; then ADB=(adb -s "$SERIAL"); fi

if [[ -z "$OUT_DIR" ]]; then
  OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/out/$(date +%Y%m%d-%H%M%S)-$TAG"
fi
mkdir -p "$OUT_DIR"

"${ADB[@]}" shell uiautomator dump /sdcard/rpa_popup_ui.xml >/dev/null
"${ADB[@]}" pull /sdcard/rpa_popup_ui.xml "$OUT_DIR/ui-hierarchy.xml" >/dev/null
"${ADB[@]}" shell screencap -p /sdcard/rpa_popup_screen.png >/dev/null
"${ADB[@]}" pull /sdcard/rpa_popup_screen.png "$OUT_DIR/screen.png" >/dev/null

# compact text list for quick inspect
python3 - <<PY
import re, pathlib
p = pathlib.Path("$OUT_DIR/ui-hierarchy.xml")
t = p.read_text(encoding="utf-8", errors="ignore")
texts = re.findall(r'text="([^"]+)"', t)
descs = re.findall(r'content-desc="([^"]+)"', t)
lines = []
for x in texts + descs:
    x = x.strip()
    if x:
        lines.append(x)
pathlib.Path("$OUT_DIR/visible-labels.txt").write_text("\n".join(lines), encoding="utf-8")
print("labels", len(lines))
# soft CTA hits
soft = [
    "以后再说","稍后再说","Not now","Maybe later","跳过","Got it","我知道了","不允许",
    "底部工作表","Bottom sheet","不感兴趣","下载","举报","分享至","转发","关闭","添加评论",
]
hits = [s for s in soft if any(s in L for L in lines)]
pathlib.Path("$OUT_DIR/soft-cta-hits.txt").write_text("\n".join(hits), encoding="utf-8")
print("soft_hits", hits)
PY

echo "OUT=$OUT_DIR"
ls -la "$OUT_DIR"
