#!/usr/bin/env python3
"""
Popup lab: activate interruptions → capture UI tree → map countermeasures → verify.

Without device root, Frida cannot attach to stock TikTok (PermissionDenied).
This runner:
  1) Tries Frida attach + RPC activate (hook path)
  2) Falls back to adb-driven real TikTok surfaces (sheet/share)
  3) Captures uiautomator XML
  4) Classifies interruption codes from labels (aligned with PageAnalyzer/POPUP_CATALOG)
  5) Emits recovery plan and asserts expected codes

Usage:
  python tools/popup-hook/run_hook_lab.py --serial DEVICE_SERIAL
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent
HOOK_JS = ROOT / "scripts" / "hook_activate.js"
PKG = "com.zhiliaoapp.musically"

# Mirrors docs/POPUP_CATALOG + PageAnalyzer markers (text/content-desc).
INTERRUPT_RULES: list[tuple[str, tuple[str, ...]]] = [
    ("video_options_sheet", ("底部工作表", "bottom sheet", "不感兴趣", "not interested")),
    ("video_options_sheet", ("下载", "download", "举报", "report", "播放速度")),
    ("generic_dismiss_popup", ("以后再说", "稍后再说", "下次再说", "not now", "maybe later", "no thanks")),
    ("got_it_popup", ("我知道了", "知道了", "got it")),
    ("contacts_sync_prompt", ("同步通讯录", "访问你的通讯录", "sync your contacts", "access your contacts")),
    ("notification_prompt", ("开启通知", "打开通知", "turn on notifications", "enable notifications")),
    ("comments_open", ("条评论", "添加评论", "关闭")),
]

# Recovery plan aligned with InterruptPolicy / SafeRecovery.
RECOVERY_PLAN: dict[str, str] = {
    "video_options_sheet": "press_back",
    "generic_dismiss_popup": "dismiss_popup (soft CTA only; never Allow/开启)",
    "got_it_popup": "dismiss_got_it",
    "contacts_sync_prompt": "deny_contacts / Not now",
    "notification_prompt": "dismiss_popup soft-deny",
    "comments_open": "close_comments / press_back",
    "unknown_no_soft_cta": "STOP_HUMAN + dump (do not click primary accept)",
}


@dataclass
class CaptureResult:
    mode: str
    path: str
    labels: list[str]
    codes: list[str]
    recovery: list[str]
    frida: bool


def adb(serial: str | None, *args: str) -> subprocess.CompletedProcess[str]:
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    return subprocess.run(cmd, text=True, capture_output=True)


def ensure_tiktok(serial: str | None) -> None:
    adb(serial, "shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(2.0)
    adb(serial, "shell", "input", "tap", "840", "190")  # 推荐
    time.sleep(0.8)
    adb(serial, "shell", "input", "tap", "108", "2225")  # 首页
    time.sleep(1.0)


def try_frida_activate(mode: str) -> bool:
    try:
        import frida  # type: ignore
    except ImportError:
        print("frida python module missing")
        return False
    try:
        device = frida.get_usb_device(timeout=5)
        # find process
        target = None
        for p in device.enumerate_processes():
            if p.name in ("TikTok", PKG) or "tiktok" in p.name.lower():
                target = p
                break
        if not target:
            print("TikTok process not found for Frida")
            return False
        session = device.attach(target.pid)
        script = session.create_script(HOOK_JS.read_text(encoding="utf-8"))
        script.on("message", lambda m, d: print("frida:", m))
        script.load()
        time.sleep(0.5)
        api = script.exports_sync
        print("frida ping", api.ping(), "activity", api.activity())
        if mode == "email":
            print("activateEmail", api.activate_email())
        else:
            print(
                "activateSoftask",
                api.activate_softask(
                    "Lab soft-ask (unpack CTAs)",
                    "以后再说 / Not now are soft. 开启 / Allow must not be auto-clicked.",
                ),
            )
        time.sleep(1.2)
        session.detach()
        return True
    except Exception as e:
        print(f"Frida attach/activate failed: {type(e).__name__}: {e}")
        return False


def activate_adb(mode: str, serial: str | None) -> None:
    ensure_tiktok(serial)
    if mode == "sheet":
        adb(serial, "shell", "input", "swipe", "540", "1100", "540", "1100", "1100")
    elif mode == "share":
        adb(serial, "shell", "input", "tap", "980", "1900")
    elif mode == "comments":
        adb(serial, "shell", "input", "tap", "980", "1550")
    elif mode == "softask":
        # No-root path cannot inject AlertDialog into TT; use sheet as real interruption surface.
        adb(serial, "shell", "input", "swipe", "540", "1100", "540", "1100", "1100")
    time.sleep(1.3)


def capture_tree(serial: str | None, out_dir: Path) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/rpa_popup_ui.xml")
    adb(serial, "pull", "/sdcard/rpa_popup_ui.xml", str(out_dir / "ui-hierarchy.xml"))
    adb(serial, "shell", "screencap", "-p", "/sdcard/rpa_popup_screen.png")
    adb(serial, "pull", "/sdcard/rpa_popup_screen.png", str(out_dir / "screen.png"))
    return out_dir / "ui-hierarchy.xml"


def labels_from_xml(xml_path: Path) -> list[str]:
    tree = ET.parse(xml_path)
    labels: list[str] = []
    for el in tree.iter("node"):
        for key in ("text", "content-desc"):
            v = (el.attrib.get(key) or "").strip()
            if v:
                labels.append(v)
    return labels


def classify(labels: list[str]) -> list[str]:
    blob = "\n".join(labels).lower()
    codes: list[str] = []
    for code, needles in INTERRUPT_RULES:
        for n in needles:
            if n.lower() in blob:
                if code not in codes:
                    codes.append(code)
                break
    # sheet needs structure-ish: bottom sheet desc OR (download+report)
    if "video_options_sheet" in codes:
        has_sheet = any("工作表" in x or "bottom sheet" in x.lower() for x in labels)
        has_actions = any(x in blob for x in ("下载", "download", "举报", "report", "不感兴趣"))
        if not (has_sheet or has_actions):
            codes = [c for c in codes if c != "video_options_sheet"]
    if not codes:
        soft = any(
            any(s in x for s in ("以后再说", "Not now", "Maybe later", "跳过")) for x in labels
        )
        if not soft:
            codes.append("unknown_no_soft_cta")
    return codes


def recovery_for(codes: list[str]) -> list[str]:
    return [f"{c} → {RECOVERY_PLAN.get(c, 'review dump')}" for c in codes]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="")
    ap.add_argument(
        "--modes",
        default="softask,sheet,share",
        help="comma list: softask,sheet,share,comments",
    )
    args = ap.parse_args()
    serial = args.serial or None
    modes = [m.strip() for m in args.modes.split(",") if m.strip()]
    out_root = ROOT / "out" / f"lab-{time.strftime('%Y%m%d-%H%M%S')}"
    out_root.mkdir(parents=True, exist_ok=True)

    results: list[CaptureResult] = []
    print("OUT", out_root)

    for mode in modes:
        print(f"\n=== mode={mode} ===")
        frida_ok = False
        if mode in ("softask", "email", "discover"):
            frida_ok = try_frida_activate("email" if mode == "email" else "softask")
            if not frida_ok:
                print("NOTE: stock TikTok attach requires root/Magisk frida-server as root.")
                print("      Using adb activation of a real TikTok interruption surface instead.")
                activate_adb("sheet" if mode != "comments" else "comments", serial)
            else:
                time.sleep(0.5)
        else:
            activate_adb(mode, serial)

        dest = out_root / mode
        xml_path = capture_tree(serial, dest)
        labels = labels_from_xml(xml_path)
        codes = classify(labels)
        recovery = recovery_for(codes)
        (dest / "labels.txt").write_text("\n".join(labels), encoding="utf-8")
        (dest / "analysis.json").write_text(
            json.dumps(
                {
                    "mode": mode,
                    "frida": frida_ok,
                    "codes": codes,
                    "recovery": recovery,
                    "labels_preview": labels[:40],
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        print("codes", codes)
        print("recovery", recovery)
        results.append(
            CaptureResult(
                mode=mode,
                path=str(dest),
                labels=labels[:50],
                codes=codes,
                recovery=recovery,
                frida=frida_ok,
            )
        )
        # dismiss
        adb(serial, "shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.5)

    report = {
        "out": str(out_root),
        "results": [asdict(r) for r in results],
        "countermeasures": RECOVERY_PLAN,
        "frida_attach_any": any(r.frida for r in results),
    }
    (out_root / "lab-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    # Assertions: at least one real interruption surface with a known code
    known = [r for r in results if any(c != "unknown_no_soft_cta" for c in r.codes)]
    if not known:
        print("FAIL: no known interruption captured")
        return 1
    # sheet-like must map to video_options_sheet when labels match
    for r in results:
        if any("底部工作表" in x or "不感兴趣" in x for x in r.labels):
            if "video_options_sheet" not in r.codes:
                print("FAIL: sheet labels present but not classified", r.mode)
                return 1
            if not any("press_back" in x for x in r.recovery):
                print("FAIL: sheet recovery must be press_back", r.mode)
                return 1
    print("\nPASS lab classification/recovery checks")
    print("report", out_root / "lab-report.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
