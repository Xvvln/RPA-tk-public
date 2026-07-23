from __future__ import annotations

import time
from dataclasses import asdict, dataclass
from pathlib import Path
from xml.etree import ElementTree as ET

import uiautomator2 as u2

from .device import (
    TIKTOK_PACKAGE,
    dump_hierarchy_with_retry,
    ensure_screen_ready,
    has_validated_network,
)
from .state import detect_ui_state, raise_for_blockers


SEARCH_FIELD_ID = f"{TIKTOK_PACKAGE}:id/hdj"
SEARCH_SUBMIT_ID = f"{TIKTOK_PACKAGE}:id/tv_search_textview"


@dataclass(frozen=True)
class SearchResult:
    query: str
    status: str
    visible_texts: list[str]

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


def parse_search_result(hierarchy: str, query: str) -> SearchResult:
    root = ET.fromstring(hierarchy)
    nodes = [node.attrib for node in root.iter("node")]
    visible_texts: list[str] = []
    for node in nodes:
        text = node.get("text", "").strip()
        if text and text not in visible_texts:
            visible_texts.append(text)

    normalized_text = " ".join(visible_texts).casefold()
    empty_markers = (
        "未找到相关结果",
        "没有找到相关结果",
        "no results found",
        "couldn't find any results",
    )
    if any(marker in normalized_text for marker in empty_markers):
        status = "empty"
    else:
        query_visible = any(
            node.get("resource-id") == SEARCH_FIELD_ID
            and node.get("text", "").strip().casefold() == query.casefold()
            for node in nodes
        )
        selected_tab = any(
            node.get("selected") == "true"
            and node.get("text", "").strip() in {"综合", "用户", "视频", "Top", "Users", "Videos"}
            for node in nodes
        )
        status = "results" if query_visible and selected_tab else "unknown"

    return SearchResult(
        query=query,
        status=status,
        visible_texts=visible_texts[:30],
    )


class TikTokController:
    def __init__(self, device: u2.Device) -> None:
        self.device = device

    def launch(self, wait_seconds: float = 5.0) -> dict[str, object]:
        ensure_screen_ready(self.device)
        self.device.app_start(TIKTOK_PACKAGE, stop=False)
        time.sleep(wait_seconds)
        return self.device.app_current()

    def snapshot(self, output_directory: Path) -> tuple[Path, Path]:
        ensure_screen_ready(self.device)
        screenshot_path = output_directory / "screen.png"
        hierarchy_path = output_directory / "hierarchy.xml"
        self.device.screenshot(str(screenshot_path))
        hierarchy_path.write_text(
            dump_hierarchy_with_retry(self.device),
            encoding="utf-8",
        )
        return screenshot_path, hierarchy_path

    def search(self, query: str, wait_seconds: float = 4.0) -> SearchResult:
        normalized = query.strip()
        if not normalized:
            raise ValueError("搜索关键词不能为空")
        if len(normalized) > 100:
            raise ValueError("搜索关键词不能超过 100 个字符")

        ensure_screen_ready(self.device)
        current = self.device.app_current()
        if current.get("package") != TIKTOK_PACKAGE:
            raise RuntimeError("请先在手机上手动打开国际版 TikTok")

        field = self.device(resourceId=SEARCH_FIELD_ID)
        if not field.exists:
            entry = self.device(description="搜索")
            if not entry.exists:
                entry = self.device(description="Search")
            if not entry.exists:
                raise RuntimeError("当前页面找不到 TikTok 搜索入口")
            entry.click()
            if not field.wait(timeout=3.0):
                raise RuntimeError("点击搜索入口后没有进入搜索页")

        field.click()
        field.clear_text()
        field.set_text(normalized)
        time.sleep(0.4)
        if (field.get_text() or "").strip() != normalized:
            raise RuntimeError("搜索关键词没有完整写入")

        submit = self.device(resourceId=SEARCH_SUBMIT_ID)
        if submit.exists:
            submit.click()
        else:
            self.device.press("enter")
        time.sleep(wait_seconds)

        if self.device.app_current().get("package") != TIKTOK_PACKAGE:
            raise RuntimeError("提交搜索后 TikTok 离开前台")
        hierarchy = dump_hierarchy_with_retry(self.device)
        raise_for_blockers(
            detect_ui_state(
                hierarchy,
                network_available=has_validated_network(self.device),
            )
        )
        return parse_search_result(hierarchy, normalized)
