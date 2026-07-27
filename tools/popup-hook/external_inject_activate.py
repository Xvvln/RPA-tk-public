#!/usr/bin/env python3
"""
免 root · 不改包 · 外部注入激活 + UI 树抓取

硬边界（本机已实测）：
  frida.attach(TikTok) → PermissionDeniedError
  原因：正式包不可调试 + SELinux 禁止非 root 跨进程 ptrace/inject。
  因此本脚本 **不** 做进程内 Java/Native hook，也不改 APK。

本脚本做的「外部注入」是 Android 官方允许的进程外输入注入：
  adb shell input (tap / swipe / keyevent)  →  打开 TikTok 真实中断面
  uiautomator dump / screencap              →  抓树 + 截图
  再按 POPUP_CATALOG / InterruptPolicy 分类与应对方案

Usage:
  python tools/popup-hook/external_inject_activate.py --serial DEVICE_SERIAL
  python tools/popup-hook/external_inject_activate.py --modes sheet,share,comments
"""

from __future__ import annotations

import argparse
import json
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PKG = "com.zhiliaoapp.musically"

# 1080x2400 @ density 480 (OnePlus 8T). 按比例可改 --width/--height。
DEFAULT_W, DEFAULT_H = 1080, 2400

# 分类规则：与 run_hook_lab / PageAnalyzer / POPUP_CATALOG 对齐
INTERRUPT_RULES: list[tuple[str, tuple[str, ...]]] = [
    ("video_options_sheet", ("底部工作表", "bottom sheet", "不感兴趣", "not interested")),
    ("video_options_sheet", ("下载", "download", "举报", "report", "播放速度", "倍速")),
    ("generic_dismiss_popup", ("以后再说", "稍后再说", "下次再说", "not now", "maybe later", "no thanks")),
    ("got_it_popup", ("我知道了", "知道了", "got it")),
    ("contacts_sync_prompt", ("同步通讯录", "访问你的通讯录", "sync your contacts", "access your contacts")),
    ("notification_prompt", ("开启通知", "打开通知", "turn on notifications", "enable notifications")),
    ("comments_open", ("条评论", "添加评论", "添加一条评论")),
    ("share_panel", ("分享到", "share to", "复制链接", "copy link", "短信", "消息")),
]

RECOVERY_PLAN: dict[str, str] = {
    "video_options_sheet": "press_back",
    "generic_dismiss_popup": "dismiss_popup (soft CTA only; never Allow/开启)",
    "got_it_popup": "dismiss_got_it",
    "contacts_sync_prompt": "deny_contacts / Not now",
    "notification_prompt": "dismiss_popup soft-deny",
    "comments_open": "close_comments / press_back",
    "share_panel": "press_back / close share",
    "unknown_no_soft_cta": "STOP_HUMAN + dump (do not click primary accept)",
}


@dataclass
class ModeResult:
    mode: str
    inject: str
    codes: list[str]
    recovery: list[str]
    labels_preview: list[str]
    path: str


def adb(serial: str | None, *args: str, check: bool = False) -> subprocess.CompletedProcess[str]:
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    return subprocess.run(cmd, text=True, capture_output=True, check=check)


def xy(w: int, h: int, nx: float, ny: float) -> tuple[str, str]:
    """Normalized 0..1 → pixel strings."""
    return str(int(w * nx)), str(int(h * ny))


def collapse_system_ui(serial: str | None) -> None:
    """关掉通知栏/控制中心，避免挡住 TikTok。"""
    adb(serial, "shell", "cmd", "statusbar", "collapse")
    adb(serial, "shell", "service", "call", "statusbar", "2")
    for _ in range(3):
        adb(serial, "shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.12)
    time.sleep(0.2)


def device_unlocked(serial: str | None) -> bool:
    """Keyguard 亮着时 uiautomator 只会抓到 systemui（充电完成等），注入无效。"""
    r = adb(serial, "shell", "dumpsys", "window")
    text = (r.stdout or "") + (r.stderr or "")
    if "mIsShowing=true" in text and "KeyguardStateMonitor" in text:
        # crude: look near KeyguardStateMonitor block
        if "isKeyguardShowing=true" in text or "mDreamingLockscreen=true" in text:
            return False
    if "mCurrentFocus=Window{" in text and "NotificationShade" in text:
        # shade alone is not always lock; still try collapse first
        pass
    focus = adb(serial, "shell", "dumpsys", "window", "displays")
    ft = focus.stdout or ""
    if "isKeyguardShowing=true" in ft or "mDreamingLockscreen=true" in text:
        return False
    # secondary: dump packages
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/rpa_unlock_probe.xml")
    pull = adb(serial, "pull", "/sdcard/rpa_unlock_probe.xml", "/tmp/rpa_unlock_probe.xml")
    if pull.returncode != 0:
        return True  # can't probe; let later steps fail loudly
    try:
        probe = Path("/tmp/rpa_unlock_probe.xml").read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return True
    # locked lockscreen often only systemui + 充电完成 / 滑动解锁
    if 'package="com.zhiliaoapp.musically"' in probe or 'package="com.ss.android.ugc' in probe:
        return True
    if "充电完成" in probe and "com.android.systemui" in probe and "musically" not in probe:
        return False
    if 'package="com.android.systemui"' in probe and "musically" not in probe:
        # only systemui visible → almost certainly lock/shade covering
        return False
    return True


def ensure_feed(serial: str | None, w: int, h: int) -> None:
    collapse_system_ui(serial)
    adb(serial, "shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(2.2)
    # 推荐 tab（顶栏靠右）
    x, y = xy(w, h, 0.78, 0.08)
    adb(serial, "shell", "input", "tap", x, y)
    time.sleep(0.6)
    # 首页 tab（底栏最左）
    x, y = xy(w, h, 0.10, 0.93)
    adb(serial, "shell", "input", "tap", x, y)
    time.sleep(1.0)


def inject_activate(mode: str, serial: str | None, w: int, h: int) -> str:
    """
    进程外注入：只发 input 事件，不进 TT 地址空间。
    返回本次用的注入描述。
    """
    ensure_feed(serial, w, h)
    cx, cy = xy(w, h, 0.50, 0.46)

    if mode == "sheet":
        # 长按画面中部 → 视频更多/不感兴趣 工作表
        adb(serial, "shell", "input", "swipe", cx, cy, cx, cy, "1100")
        desc = f"long_press center ({cx},{cy}) 1100ms → video options sheet"
    elif mode == "share":
        # 右侧分享按钮附近
        x, y = xy(w, h, 0.91, 0.79)
        adb(serial, "shell", "input", "tap", x, y)
        desc = f"tap share ({x},{y})"
    elif mode == "comments":
        x, y = xy(w, h, 0.91, 0.65)
        adb(serial, "shell", "input", "tap", x, y)
        desc = f"tap comments ({x},{y})"
    elif mode == "like_area":
        x, y = xy(w, h, 0.91, 0.52)
        adb(serial, "shell", "input", "tap", x, y)
        desc = f"tap like ({x},{y})"
    elif mode == "softask":
        # 正式包无法进程内弹 AlertDialog；用真实 sheet 当中断面样本
        adb(serial, "shell", "input", "swipe", cx, cy, cx, cy, "1100")
        desc = f"softask-proxy: long_press center ({cx},{cy}) (no in-process dialog without root/repack)"
    else:
        raise ValueError(f"unknown mode: {mode}")

    time.sleep(1.4)
    return desc


def capture(serial: str | None, out_dir: Path) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/rpa_ext_inject_ui.xml")
    adb(serial, "pull", "/sdcard/rpa_ext_inject_ui.xml", str(out_dir / "ui-hierarchy.xml"))
    adb(serial, "shell", "screencap", "-p", "/sdcard/rpa_ext_inject_screen.png")
    adb(serial, "pull", "/sdcard/rpa_ext_inject_screen.png", str(out_dir / "screen.png"))
    return out_dir / "ui-hierarchy.xml"


def labels_from_xml(xml_path: Path) -> list[str]:
    if not xml_path.exists() or xml_path.stat().st_size < 20:
        return []
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
    if "video_options_sheet" in codes:
        has_sheet = any("工作表" in x or "bottom sheet" in x.lower() for x in labels)
        has_actions = any(x in blob for x in ("下载", "download", "举报", "report", "不感兴趣", "倍速"))
        if not (has_sheet or has_actions):
            codes = [c for c in codes if c != "video_options_sheet"]
    if not codes:
        soft = any(any(s in x for s in ("以后再说", "Not now", "Maybe later", "跳过")) for x in labels)
        if not soft:
            codes.append("unknown_no_soft_cta")
    return codes


def dismiss(serial: str | None) -> None:
    adb(serial, "shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.45)


def main() -> int:
    ap = argparse.ArgumentParser(description="No-root external inject activate + UI dump")
    ap.add_argument("--serial", default="")
    ap.add_argument("--modes", default="sheet,share,comments")
    ap.add_argument("--width", type=int, default=DEFAULT_W)
    ap.add_argument("--height", type=int, default=DEFAULT_H)
    args = ap.parse_args()
    serial = args.serial or None
    modes = [m.strip() for m in args.modes.split(",") if m.strip()]
    w, h = args.width, args.height

    # 探测真实分辨率
    size = adb(serial, "shell", "wm", "size")
    if size.stdout and "Physical size:" in size.stdout:
        try:
            part = size.stdout.strip().split("Physical size:")[-1].strip().split()[0]
            ww, hh = part.split("x")
            w, h = int(ww), int(hh)
        except Exception:
            pass

    out_root = ROOT / "out" / f"ext-inject-{time.strftime('%Y%m%d-%H%M%S')}"
    out_root.mkdir(parents=True, exist_ok=True)

    print("=== 免 root / 不改包 / 外部注入激活 ===")
    print(f"device serial={serial or 'default'} size={w}x{h}")
    print(f"OUT {out_root}")
    print("说明: 进程内 Frida/Xposed 注入在正式 TikTok 上会 PermissionDenied；")
    print("      本路径用 adb input 外部注入手势，激活真实 UI 后抓树。")
    print()

    adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    collapse_system_ui(serial)
    if not device_unlocked(serial):
        print("FAIL: 手机处于锁屏/通知栏遮挡状态，外部注入打不到 TikTok。")
        print("请先手动解锁手机，保持 TikTok 可进推荐流，再重跑本脚本。")
        (out_root / "report.json").write_text(
            json.dumps(
                {
                    "error": "device_locked",
                    "hint": "unlock phone, open TikTok feed, re-run",
                    "process_hook_possible": False,
                },
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        return 2

    results: list[ModeResult] = []
    for mode in modes:
        print(f"=== mode={mode} ===")
        inject_desc = inject_activate(mode, serial, w, h)
        print("inject:", inject_desc)
        dest = out_root / mode
        xml_path = capture(serial, dest)
        labels = labels_from_xml(xml_path)
        codes = classify(labels)
        recovery = [f"{c} → {RECOVERY_PLAN.get(c, 'review dump')}" for c in codes]
        (dest / "labels.txt").write_text("\n".join(labels), encoding="utf-8")
        analysis = {
            "mode": mode,
            "method": "external_input_inject",
            "root": False,
            "repack": False,
            "process_hook": False,
            "inject": inject_desc,
            "codes": codes,
            "recovery": recovery,
            "labels_preview": labels[:50],
        }
        (dest / "analysis.json").write_text(
            json.dumps(analysis, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print("codes:", codes)
        print("recovery:", recovery)
        results.append(
            ModeResult(
                mode=mode,
                inject=inject_desc,
                codes=codes,
                recovery=recovery,
                labels_preview=labels[:30],
                path=str(dest),
            )
        )
        dismiss(serial)

    report = {
        "constraints": {
            "root": False,
            "repack": False,
            "external_inject": True,
            "process_hook_possible": False,
            "reason": "stock TikTok attach → PermissionDeniedError without root/gadget",
        },
        "size": f"{w}x{h}",
        "out": str(out_root),
        "results": [asdict(r) for r in results],
        "countermeasures": RECOVERY_PLAN,
    }
    report_path = out_root / "report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    known = [r for r in results if any(c != "unknown_no_soft_cta" for c in r.codes)]
    if not known:
        print("\nFAIL: no known interruption surface captured (feed not ready / coords off?)")
        print("report", report_path)
        return 1

    print("\nPASS external inject lab")
    print("report", report_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
