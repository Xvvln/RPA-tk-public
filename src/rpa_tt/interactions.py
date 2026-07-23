from __future__ import annotations

import time
from dataclasses import dataclass

import uiautomator2 as u2

from .device import TIKTOK_PACKAGE


LIKE_BUTTON_ID = f"{TIKTOK_PACKAGE}:id/fud"
FAVORITE_BUTTON_ID = f"{TIKTOK_PACKAGE}:id/hqz"
FAVORITE_ICON_ID = f"{TIKTOK_PACKAGE}:id/hr0"
COMMENT_FIELD_ID = f"{TIKTOK_PACKAGE}:id/eal"
COMMENT_SEND_ID = f"{TIKTOK_PACKAGE}:id/cuv"


@dataclass(frozen=True)
class InteractionResult:
    action: str
    changed: bool = False


def is_favorite_color(red: int, green: int, blue: int) -> bool:
    """Return whether a pixel matches TikTok's selected yellow icon color."""
    return red > 180 and green > 130 and blue < 110 and red > green + 20


def favorite_color_ratio(image: object, bounds: dict[str, int]) -> float:
    """Measure selected-yellow pixels around the center of the favorite icon."""
    center_x = (bounds["left"] + bounds["right"]) // 2
    center_y = (bounds["top"] + bounds["bottom"]) // 2
    width = bounds["right"] - bounds["left"]
    height = bounds["bottom"] - bounds["top"]
    radius = max(12, min(width, height) // 8)
    matching = 0
    total = 0
    for x in range(center_x - radius, center_x + radius + 1):
        for y in range(center_y - radius, center_y + radius + 1):
            red, green, blue = image.getpixel((x, y))[:3]  # type: ignore[attr-defined]
            matching += is_favorite_color(red, green, blue)
            total += 1
    return matching / total


class TikTokInteractions:
    def __init__(self, device: u2.Device) -> None:
        self.device = device

    def like(self, *, apply: bool) -> InteractionResult:
        liked = self.device(
            resourceId=LIKE_BUTTON_ID,
            descriptionStartsWith="点赞的视频",
        )
        if liked.exists:
            return InteractionResult("already_liked")

        unliked = self.device(
            resourceId=LIKE_BUTTON_ID,
            descriptionStartsWith="点赞视频",
        )
        if not unliked.exists:
            raise RuntimeError("找不到当前视频的点赞按钮")
        if not apply:
            return InteractionResult("would_like")

        unliked.click()
        if not liked.wait(timeout=2.0):
            raise RuntimeError("点击点赞后未检测到已点赞状态，任务已停止")
        return InteractionResult("liked", changed=True)

    def favorite(self, *, apply: bool) -> InteractionResult:
        selected_before = self._is_favorited()
        if selected_before:
            return InteractionResult("already_favorited")
        if not apply:
            return InteractionResult("would_favorite")

        button = self.device(resourceId=FAVORITE_BUTTON_ID)
        if not button.exists:
            raise RuntimeError("找不到当前视频的收藏按钮")
        button.click()
        time.sleep(0.6)
        if not self._is_favorited():
            raise RuntimeError("点击收藏后未检测到已收藏状态，任务已停止")
        return InteractionResult("favorited", changed=True)

    def comment(self, text: str, *, apply: bool) -> InteractionResult:
        normalized = text.strip()
        if not normalized:
            raise ValueError("评论内容不能为空")
        if not apply:
            return InteractionResult("would_comment")

        field = self.device(resourceId=COMMENT_FIELD_ID)
        if not field.exists:
            raise RuntimeError("评论区已打开，但找不到评论输入框")
        field.click()
        field.set_text(normalized)
        time.sleep(0.5)
        if (field.get_text() or "").strip() != normalized:
            self._clear_comment_draft(field)
            raise RuntimeError("评论文字没有完整写入，已清空草稿并停止")

        send = self.device(resourceId=COMMENT_SEND_ID)
        if not send.exists:
            self._clear_comment_draft(field)
            raise RuntimeError("找不到评论发送按钮，已清空草稿并停止")

        send.click()
        time.sleep(1.2)
        if field.exists and (field.get_text() or "").strip() == normalized:
            self._clear_comment_draft(field)
            raise RuntimeError("点击发送后结果不明确；为避免重复评论，任务已停止且不会重试")
        return InteractionResult("commented", changed=True)

    def _is_favorited(self) -> bool:
        icon = self.device(resourceId=FAVORITE_ICON_ID)
        if not icon.exists:
            raise RuntimeError("找不到当前视频的收藏图标")
        bounds = icon.info.get("bounds")
        if not isinstance(bounds, dict):
            raise RuntimeError("无法读取收藏图标位置")
        screenshot = self.device.screenshot()
        return favorite_color_ratio(screenshot, bounds) >= 0.25

    def _clear_comment_draft(self, field: object) -> None:
        try:
            field.clear_text()  # type: ignore[attr-defined]
        finally:
            self.device.press("back")
