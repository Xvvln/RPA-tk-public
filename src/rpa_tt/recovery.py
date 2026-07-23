from __future__ import annotations

import time
from dataclasses import asdict, dataclass

import uiautomator2 as u2

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
class RecoveryReport:
    expected_page: str
    recovered: bool
    actions: list[str]
    final_state: UIState

    def to_dict(self) -> dict[str, object]:
        result = asdict(self)
        result["final_state"] = self.final_state.to_dict()
        return result


def plan_recovery(state: UIState, expected_page: str) -> list[str]:
    """Return context-aware reversible recovery actions in priority order."""
    if state.blockers:
        return []

    actions: list[str] = []
    interruptions = set(state.interruptions)
    if "system_input_method_dialog" in interruptions:
        actions.append("press_back")
    if "video_options_sheet" in interruptions:
        actions.append("press_back")
    if state.keyboard_visible and expected_page not in INPUT_PAGES:
        actions.append("hide_keyboard")
    if state.page == "comments" and expected_page != "comments":
        actions.append("close_comments")
    if "got_it_popup" in interruptions:
        actions.append("dismiss_got_it")
    if "interest_survey" in interruptions:
        actions.append("dismiss_interest_survey")
    if interruptions.intersection(
        {
            "collections_popup",
            "follow_friends_popup",
            "contacts_sync_prompt",
            "link_email_popup",
            "notification_prompt",
            "promo_banner",
            "generic_dismiss_popup",
        }
    ):
        actions.append("dismiss_popup")
    if "notification_banner" in interruptions:
        actions.append("dismiss_notification")
    if "suggestion_page" in interruptions and expected_page == "feed":
        actions.append("skip_suggestion")
    if state.page in HOME_RECOVERABLE_PAGES and expected_page == "feed":
        actions.append("navigate_home")
    return list(dict.fromkeys(actions))


class SafeRecovery:
    """Recover only known, reversible UI interruptions and re-check after each action."""

    def __init__(self, device: u2.Device) -> None:
        self.device = device

    def recover(self, expected_page: str, max_actions: int = 3) -> RecoveryReport:
        actions: list[str] = []
        state = self._read_state()

        for _ in range(max_actions):
            raise_for_blockers(state)
            planned = plan_recovery(state, expected_page)
            if not planned:
                break
            action = planned[0]
            if not self._execute(action):
                break
            actions.append(action)
            time.sleep(0.6)
            state = self._read_state()

        raise_for_blockers(state)
        return RecoveryReport(
            expected_page=expected_page,
            recovered=state.page == expected_page,
            actions=actions,
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
            return self._click_text(("我知道了", "知道了", "Got it")) or self._click_text(
                ("以后再说", "暂不", "Not now", "Dismiss")
            ) or self._click_description(("关闭", "Close"))
        if action == "dismiss_interest_survey":
            return self._click_text(
                ("跳过", "Skip", "以后再说", "Not now", "Maybe later")
            ) or self._press_back()
        if action == "dismiss_popup":
            return self._click_text(
                (
                    "以后再说",
                    "下次再说",
                    "稍后再说",
                    "暂不",
                    "先不了",
                    "不用了",
                    "跳过",
                    "不允许",
                    "Not now",
                    "Maybe later",
                    "No thanks",
                    "No, thanks",
                    "Don't allow",
                    "Don’t allow",
                    "Dismiss",
                    "Skip",
                )
            ) or self._click_description(("关闭", "Close")) or self._press_back()
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
