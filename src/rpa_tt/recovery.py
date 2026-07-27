from __future__ import annotations

import time
from dataclasses import asdict, dataclass

import uiautomator2 as u2

from .interrupt_policy import (
    CONTACTS_DENY_LABELS,
    GOT_IT_LABELS,
    INTEREST_SKIP_LABELS,
    filter_soft_labels,
    soft_dismiss_click_labels,
)
from .state import UIState, inspect_device_state, raise_for_blockers


INPUT_PAGES = {"comments", "search_input"}
HOME_RECOVERABLE_PAGES = {
    "content_preferences",
    "inbox",
    "profile",
    "search_empty",
    "search_input",
    "search_results",
    "settings",
    "shop",
}


@dataclass(frozen=True)
class RecoveryAction:
    """One planned recovery step with the interruption code that motivated it."""

    interruption_code: str
    action: str

    def to_dict(self) -> dict[str, str]:
        return asdict(self)


@dataclass(frozen=True)
class RecoveryReport:
    expected_page: str
    recovered: bool
    actions: list[str]
    steps: list[RecoveryAction]
    final_state: UIState

    def to_dict(self) -> dict[str, object]:
        result = asdict(self)
        result["final_state"] = self.final_state.to_dict()
        result["steps"] = [step.to_dict() for step in self.steps]
        return result


def plan_recovery_steps(state: UIState, expected_page: str) -> list[RecoveryAction]:
    """Return context-aware reversible recovery actions in priority order."""
    if state.blockers:
        return []

    steps: list[RecoveryAction] = []
    interruptions = set(state.interruptions)
    if "system_input_method_dialog" in interruptions:
        steps.append(RecoveryAction("system_input_method_dialog", "press_back"))
    if "video_options_sheet" in interruptions:
        steps.append(RecoveryAction("video_options_sheet", "press_back"))
    if state.keyboard_visible and expected_page not in INPUT_PAGES:
        steps.append(RecoveryAction("keyboard_visible", "hide_keyboard"))
    if state.page == "comments" and expected_page != "comments":
        steps.append(RecoveryAction("comments_open", "close_comments"))
    if "got_it_popup" in interruptions:
        steps.append(RecoveryAction("got_it_popup", "dismiss_got_it"))
    if "interest_survey" in interruptions:
        steps.append(RecoveryAction("interest_survey", "dismiss_interest_survey"))
    if "contacts_sync_prompt" in interruptions:
        steps.append(RecoveryAction("contacts_sync_prompt", "deny_contacts"))
    if interruptions.intersection(
        {
            "collections_popup",
            "follow_friends_popup",
            "link_email_popup",
            "notification_prompt",
            "promo_banner",
            "generic_dismiss_popup",
        }
    ):
        # Prefer the most specific named code when present.
        for code in (
            "collections_popup",
            "follow_friends_popup",
            "link_email_popup",
            "notification_prompt",
            "promo_banner",
            "generic_dismiss_popup",
        ):
            if code in interruptions:
                steps.append(RecoveryAction(code, "dismiss_popup"))
                break
    if "notification_banner" in interruptions:
        steps.append(RecoveryAction("notification_banner", "dismiss_notification"))
    if "suggestion_page" in interruptions and expected_page == "feed":
        steps.append(RecoveryAction("suggestion_page", "skip_suggestion"))
    if state.page in HOME_RECOVERABLE_PAGES and expected_page == "feed":
        steps.append(RecoveryAction(state.page, "navigate_home"))

    # Deduplicate by action while keeping first interruption_code.
    seen_actions: set[str] = set()
    unique: list[RecoveryAction] = []
    for step in steps:
        if step.action in seen_actions:
            continue
        seen_actions.add(step.action)
        unique.append(step)
    return unique


def plan_recovery(state: UIState, expected_page: str) -> list[str]:
    """Return recovery action names only (backward-compatible)."""
    return [step.action for step in plan_recovery_steps(state, expected_page)]


class SafeRecovery:
    """Recover only known, reversible UI interruptions and re-check after each action."""

    def __init__(self, device: u2.Device) -> None:
        self.device = device

    def recover(self, expected_page: str, max_actions: int = 3) -> RecoveryReport:
        actions: list[str] = []
        steps: list[RecoveryAction] = []
        state = self._read_state()

        for _ in range(max_actions):
            raise_for_blockers(state)
            planned = plan_recovery_steps(state, expected_page)
            if not planned:
                break
            step = planned[0]
            if not self._execute(step.action):
                break
            actions.append(step.action)
            steps.append(step)
            time.sleep(0.6)
            state = self._read_state()

        raise_for_blockers(state)
        return RecoveryReport(
            expected_page=expected_page,
            recovered=state.page == expected_page,
            actions=actions,
            steps=steps,
            final_state=state,
        )

    def _read_state(self) -> UIState:
        return inspect_device_state(self.device)

    def _execute(self, action: str) -> bool:
        if action in {"press_back", "hide_keyboard"}:
            self.device.press("back")
            return True
        if action == "close_comments":
            return self._click_description(("关闭", "Close")) or self._press_back()
        if action == "dismiss_got_it":
            return self._click_text(filter_soft_labels(GOT_IT_LABELS)) or self._click_text(
                soft_dismiss_click_labels()
            ) or self._click_description(("关闭", "Close"))
        if action == "dismiss_interest_survey":
            return self._click_text(
                filter_soft_labels(INTEREST_SKIP_LABELS)
            ) or self._press_back()
        if action == "deny_contacts":
            return self._click_text(
                filter_soft_labels(CONTACTS_DENY_LABELS)
            ) or self._click_text(soft_dismiss_click_labels()) or self._press_back()
        if action == "dismiss_popup":
            return self._click_text(soft_dismiss_click_labels()) or self._click_description(
                ("关闭", "Close")
            ) or self._press_back()
        if action == "dismiss_notification":
            width, height = self.device.window_size()
            self.device.swipe(
                width // 2,
                int(height * 0.12),
                width // 2,
                int(height * 0.01),
                duration=0.15,
            )
            return True
        if action == "skip_suggestion":
            width, height = self.device.window_size()
            self.device.swipe(
                width // 2,
                int(height * 0.78),
                width // 2,
                int(height * 0.22),
                duration=0.3,
            )
            return True
        if action == "navigate_home":
            return self._click_description(("首页", "Home")) or self._press_back()
        return False

    def _click_text(self, labels: tuple[str, ...]) -> bool:
        for label in labels:
            element = self.device(text=label)
            if element.exists:
                element.click()
                return True
        return False

    def _click_description(self, labels: tuple[str, ...]) -> bool:
        for label in labels:
            element = self.device(description=label)
            if element.exists:
                element.click()
                return True
        return False

    def _press_back(self) -> bool:
        self.device.press("back")
        return True
