"""Soft-dismiss whitelist, primary-accept blacklist, and recovery selection.

Aligns with docs/POPUP_CATALOG.md and docs/INTERRUPT_AND_PREFLIGHT.md.
"""

from __future__ import annotations

from typing import Iterable

SOFT_DISMISS_LABELS: tuple[str, ...] = (
    "以后再说",
    "下次再说",
    "稍后再说",
    "暂不",
    "先不了",
    "不用了",
    "跳过",
    "不允许",
    "拒绝",
    "Not now",
    "Maybe later",
    "No thanks",
    "No, thanks",
    "Don't allow",
    "Don’t allow",
    "Deny",
    "Dismiss",
    "Skip",
    "Close",
    "关闭",
)

PRIMARY_ACCEPT_BLACKLIST: tuple[str, ...] = (
    "Allow",
    "Continue",
    "Yes",
    "Update",
    "OK",
    "好的",
    "同意",
    "开启",
    "允许",
    "仅在使用该应用时允许",
    "始终允许",
    "更新",
    "登录",
    "注册",
    "继续",
    "确定",
)

GOT_IT_LABELS: tuple[str, ...] = ("我知道了", "知道了", "Got it")

INTEREST_SKIP_LABELS: tuple[str, ...] = (
    "跳过",
    "Skip",
    "以后再说",
    "Not now",
    "Maybe later",
)

CONTACTS_DENY_LABELS: tuple[str, ...] = (
    "拒绝",
    "不允许",
    "Don't allow",
    "Don’t allow",
    "Deny",
    "Not now",
    "以后再说",
    "暂不",
)

SYSTEM_PERMISSION_DENY_LABELS: tuple[str, ...] = (
    "拒绝",
    "不允许",
    "Deny",
    "Don't allow",
    "Don’t allow",
    "Not now",
    "以后再说",
    "暂不",
)

# Recovery plan action names used by SafeRecovery / plan_recovery.
RECOVERY_ACTIONS = frozenset(
    {
        "press_back",
        "hide_keyboard",
        "close_comments",
        "dismiss_got_it",
        "dismiss_interest_survey",
        "dismiss_popup",
        "dismiss_notification",
        "skip_suggestion",
        "navigate_home",
        "deny_contacts",
        "deny_system_permission",
    }
)


def is_soft_dismiss_label(label: str | None) -> bool:
    if not label:
        return False
    target = label.strip().casefold()
    return any(item.casefold() == target for item in SOFT_DISMISS_LABELS)


def is_primary_accept_label(label: str | None) -> bool:
    if not label:
        return False
    target = label.strip().casefold()
    return any(item.casefold() == target for item in PRIMARY_ACCEPT_BLACKLIST)


def filter_soft_labels(labels: Iterable[str]) -> tuple[str, ...]:
    kept: list[str] = []
    for label in labels:
        if not label or not str(label).strip():
            continue
        trimmed = str(label).strip()
        if is_primary_accept_label(trimmed):
            continue
        kept.append(trimmed)
    return tuple(kept)


def soft_and_blacklist_disjoint() -> bool:
    for label in (*SOFT_DISMISS_LABELS, *GOT_IT_LABELS):
        if is_primary_accept_label(label):
            return False
    return True


def soft_dismiss_click_labels() -> tuple[str, ...]:
    return filter_soft_labels(SOFT_DISMISS_LABELS)
