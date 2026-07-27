"""L0 environment preflight for the Python diagnostic prototype.

Schema mirrors Android PreflightReport (docs/INTERRUPT_AND_PREFLIGHT.md §3).
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, Literal

from .device import (
    TIKTOK_PACKAGE,
    build_report,
    has_validated_network,
    is_locked,
)

Severity = Literal["hard", "soft", "info"]
Status = Literal["pass", "fail", "pass_with_warnings"]


@dataclass(frozen=True)
class PreflightCheck:
    id: str
    severity: Severity
    ok: bool
    code: str
    message: str

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class PreflightReport:
    status: Status
    checks: tuple[PreflightCheck, ...]
    device_payload: dict[str, Any] | None = None

    @property
    def hard_failures(self) -> tuple[PreflightCheck, ...]:
        return tuple(c for c in self.checks if not c.ok and c.severity == "hard")

    @property
    def warnings(self) -> tuple[PreflightCheck, ...]:
        return tuple(c for c in self.checks if not c.ok and c.severity in {"soft", "info"})

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "status": self.status,
            "checks": [c.to_dict() for c in self.checks],
            "hard_failures": [c.to_dict() for c in self.hard_failures],
            "warnings": [c.to_dict() for c in self.warnings],
        }
        if self.device_payload is not None:
            payload["device"] = self.device_payload
        return payload


def evaluate_environment(
    *,
    accessibility_connected: bool = True,
    screen_on: bool,
    device_locked: bool,
    require_network: bool = True,
    network_available: bool | None,
    tiktok_installed: bool,
    overlay_allowed: bool = True,
    notifications_ok: bool = True,
    device_payload: dict[str, Any] | None = None,
) -> PreflightReport:
    checks: list[PreflightCheck] = []
    checks.append(
        PreflightCheck(
            id="P01",
            severity="hard",
            ok=accessibility_connected,
            code="" if accessibility_connected else "accessibility_disabled",
            message="无障碍服务已开启" if accessibility_connected else "无障碍服务未开启",
        )
    )
    checks.append(
        PreflightCheck(
            id="P02",
            severity="hard",
            ok=screen_on,
            code="" if screen_on else "screen_off",
            message="屏幕已点亮" if screen_on else "屏幕未点亮",
        )
    )
    checks.append(
        PreflightCheck(
            id="P03",
            severity="hard",
            ok=not device_locked,
            code="device_locked" if device_locked else "",
            message="手机仍处于锁屏状态" if device_locked else "设备已解锁",
        )
    )
    if network_available is None and require_network:
        network_ok = False
        network_code = "network_unavailable"
        network_msg = "无法检测 Android 网络状态"
    elif not require_network:
        network_ok = True
        network_code = ""
        network_msg = "未要求网络检查"
    else:
        network_ok = bool(network_available)
        network_code = "" if network_ok else "network_unavailable"
        network_msg = "网络可用" if network_ok else "Android 未检测到可用网络"
    checks.append(
        PreflightCheck(
            id="P04",
            severity="hard",
            ok=network_ok,
            code=network_code,
            message=network_msg,
        )
    )
    checks.append(
        PreflightCheck(
            id="P05",
            severity="hard",
            ok=tiktok_installed,
            code="" if tiktok_installed else "tiktok_not_installed",
            message="TikTok 已安装" if tiktok_installed else "未找到 TikTok",
        )
    )
    checks.append(
        PreflightCheck(
            id="P07",
            severity="soft",
            ok=overlay_allowed,
            code="" if overlay_allowed else "overlay_unavailable",
            message="悬浮窗可用" if overlay_allowed else "悬浮窗权限未授予，操控可能变差",
        )
    )
    checks.append(
        PreflightCheck(
            id="P08",
            severity="soft",
            ok=notifications_ok,
            code="" if notifications_ok else "notifications_unavailable",
            message="通知权限可用" if notifications_ok else "通知权限未授予，前台提示可能受限",
        )
    )

    hard_fail = any(not c.ok and c.severity == "hard" for c in checks)
    soft_fail = any(not c.ok and c.severity == "soft" for c in checks)
    if hard_fail:
        status: Status = "fail"
    elif soft_fail:
        status = "pass_with_warnings"
    else:
        status = "pass"

    return PreflightReport(
        status=status,
        checks=tuple(checks),
        device_payload=device_payload,
    )


def run_preflight(device: Any, *, require_network: bool = True) -> PreflightReport:
    """Inspect a live uiautomator2 device and build a PreflightReport."""
    report = build_report(device)
    network = has_validated_network(device) if require_network else True
    # Python prototype does not own an accessibility service; treat as N/A pass for doctor.
    return evaluate_environment(
        accessibility_connected=True,
        screen_on=report.screen_on,
        device_locked=report.locked or is_locked(device),
        require_network=require_network,
        network_available=network,
        tiktok_installed=report.tiktok_installed,
        overlay_allowed=True,
        notifications_ok=True,
        device_payload={
            **report.to_dict(),
            "package": TIKTOK_PACKAGE,
            "preflight_note": "Python doctor: P01/P07/P08 are App-side checks; marked pass for CLI",
        },
    )
