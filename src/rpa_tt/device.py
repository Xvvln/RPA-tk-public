from __future__ import annotations

from dataclasses import asdict, dataclass
import time
from typing import Any

import uiautomator2 as u2


TIKTOK_PACKAGE = "com.zhiliaoapp.musically"


@dataclass(frozen=True)
class DeviceReport:
    serial: str
    manufacturer: str
    model: str
    android_version: str
    sdk: int
    display_width: int
    display_height: int
    tiktok_installed: bool
    current_package: str
    screen_on: bool
    locked: bool

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def connect(serial: str | None = None) -> u2.Device:
    return u2.connect(serial) if serial else u2.connect()


def parse_validated_network(connectivity_dump: str) -> bool:
    """Return whether Android reports a validated physical network transport."""
    physical_transports = ("WIFI", "CELLULAR", "ETHERNET", "BLUETOOTH")
    for line in connectivity_dump.splitlines():
        if "NetworkAgentInfo{" not in line or "CONNECTED" not in line:
            continue
        if "INTERNET" not in line or "VALIDATED" not in line:
            continue
        transport_section = line.split("Capabilities:", 1)[0]
        if any(transport in transport_section for transport in physical_transports):
            return True
    return False


def has_validated_network(device: u2.Device) -> bool | None:
    """Read Android connectivity state; return None when it cannot be inspected."""
    try:
        output = device.shell(["dumpsys", "connectivity"]).output
    except Exception:
        return None
    if not output.strip():
        return None
    return parse_validated_network(output)


def dump_hierarchy_with_retry(
    device: u2.Device,
    *,
    attempts: int = 3,
    retry_delay: float = 0.5,
) -> str:
    """Retry read-only UI hierarchy dumps without repeating UI actions."""
    if attempts < 1:
        raise ValueError("attempts 必须至少为 1")
    last_error: Exception | None = None
    for attempt in range(attempts):
        try:
            return device.dump_hierarchy(compressed=False)
        except Exception as error:
            last_error = error
            if attempt + 1 >= attempts:
                break
            try:
                device.start_uiautomator()
            except Exception:
                pass
            time.sleep(retry_delay)
    raise RuntimeError(
        f"连续 {attempts} 次读取手机 UI 结构失败，请检查 ADB/UIAutomator 连接"
    ) from last_error


def is_locked(device: u2.Device) -> bool:
    window_state = device.shell("dumpsys window").output
    lock_markers = (
        "mDreamingLockscreen=true",
        "mShowingLockscreen=true",
        "isStatusBarKeyguard=true",
    )
    return any(marker in window_state for marker in lock_markers)


def ensure_screen_ready(device: u2.Device) -> None:
    if not device.info.get("screenOn", False):
        device.shell(["input", "keyevent", "KEYCODE_WAKEUP"])
        time.sleep(0.5)
    if is_locked(device):
        raise RuntimeError("手机处于锁屏状态，请先在手机上手动解锁后再运行命令")


def build_report(device: u2.Device) -> DeviceReport:
    info = device.info
    display_width, display_height = device.window_size()
    app_info = device.app_info(TIKTOK_PACKAGE)
    return DeviceReport(
        serial=device.serial,
        manufacturer=device.shell("getprop ro.product.manufacturer").output.strip(),
        model=device.shell("getprop ro.product.model").output.strip(),
        android_version=device.shell("getprop ro.build.version.release").output.strip(),
        sdk=int(info["sdkInt"]),
        display_width=display_width,
        display_height=display_height,
        tiktok_installed=app_info is not None,
        current_package=info.get("currentPackageName", ""),
        screen_on=bool(info.get("screenOn", False)),
        locked=is_locked(device),
    )
