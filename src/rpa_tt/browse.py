from __future__ import annotations

import json
import random
import time
from dataclasses import dataclass
from pathlib import Path

import uiautomator2 as u2

from .artifacts import create_run_directory
from .content import (
    VideoRecord,
    classify_relevance,
    extract_comment_texts,
    extract_video_content,
)
from .device import (
    TIKTOK_PACKAGE,
    dump_hierarchy_with_retry,
    ensure_screen_ready,
    has_validated_network,
)
from .gestures import build_gesture_plan, execute_gesture
from .interactions import TikTokInteractions
from .recovery import HOME_RECOVERABLE_PAGES, SafeRecovery
from .state import UIState, detect_ui_state, raise_for_blockers


@dataclass(frozen=True)
class BrowseConfig:
    video_count: int = 3
    watch_seconds_min: float = 7.0
    watch_seconds_max: float = 15.0
    open_comments: bool = True
    comment_seconds_min: float = 2.0
    comment_seconds_max: float = 6.0
    comment_scrolls: int = 1
    comment_scroll_pause_min: float = 0.8
    comment_scroll_pause_max: float = 1.8
    comment_swipe_seconds_min: float = 0.28
    comment_swipe_seconds_max: float = 0.9
    max_reply_threads: int = 1
    video_swipe_seconds_min: float = 0.18
    video_swipe_seconds_max: float = 0.95
    between_video_pause_min: float = 1.5
    between_video_pause_max: float = 3.5
    max_session_minutes: float = 20.0
    interest_keywords: tuple[str, ...] = ()
    apply_not_interested: bool = False
    like_probability: float = 0.0
    favorite_probability: float = 0.0
    comment_probability: float = 0.0
    comment_texts: tuple[str, ...] = ()
    apply_interactions: bool = False
    max_likes: int = 3
    max_favorites: int = 2
    max_comments: int = 1
    auto_recover_interruptions: bool = True
    max_recovery_actions: int = 3


class BrowseRunner:
    def __init__(
        self,
        device: u2.Device,
        output_root: Path = Path("runtime"),
        rng: random.Random | None = None,
    ) -> None:
        self.device = device
        self.output_root = output_root
        self.rng = rng or random.SystemRandom()
        self.interactions = TikTokInteractions(device)
        self.recovery = SafeRecovery(device)
        self._interaction_attempts: dict[str, int] = {}
        self._current_recovery_actions: list[str] = []

    def run(self, config: BrowseConfig) -> tuple[Path, list[VideoRecord]]:
        self._validate_config(config)
        ensure_screen_ready(self.device)
        current = self.device.app_current()
        if current.get("package") != TIKTOK_PACKAGE:
            raise RuntimeError("请先在手机上手动打开国际版 TikTok 并停在推荐页")

        run_directory = create_run_directory(self.output_root)
        records_path = run_directory / "videos.jsonl"
        records: list[VideoRecord] = []
        session_started = time.monotonic()
        self._interaction_attempts = {"like": 0, "favorite": 0, "comment": 0}

        for index in range(1, config.video_count + 1):
            elapsed_minutes = (time.monotonic() - session_started) / 60
            if elapsed_minutes >= config.max_session_minutes:
                break
            self._assert_tiktok_foreground()
            record = self._process_current_video(index, config)
            if index < config.video_count:
                (
                    gesture_attempts,
                    record.next_video_pause_seconds,
                ) = self._swipe_to_next_video(
                    config,
                    previous_author=record.author,
                    previous_description=record.description,
                )
                record.next_video_gesture_attempts = gesture_attempts
                record.next_video_gesture = gesture_attempts[-1]
                record.next_video_swipe_seconds = float(
                    record.next_video_gesture["duration_seconds"]
                )
            records.append(record)
            with records_path.open("a", encoding="utf-8") as stream:
                stream.write(json.dumps(record.to_dict(), ensure_ascii=False) + "\n")

        return run_directory, records

    def _process_current_video(self, index: int, config: BrowseConfig) -> VideoRecord:
        self._current_recovery_actions = []
        watch_seconds = self._sample(
            config.watch_seconds_min,
            config.watch_seconds_max,
        )
        time.sleep(watch_seconds)
        hierarchy = dump_hierarchy_with_retry(self.device)
        state, hierarchy = self._check_and_recover(hierarchy, "feed", config)
        if state.page != "feed":
            raise RuntimeError(f"预期推荐页，实际检测到页面：{state.page}")
        content = extract_video_content(hierarchy)
        if not content.author and not content.description:
            raise RuntimeError("未能读取当前视频信息，任务已停止")

        relevant, matched_keywords = classify_relevance(
            content,
            list(config.interest_keywords),
        )
        record = VideoRecord(
            index=index,
            author=content.author,
            description=content.description,
            music=content.music,
            relevant=relevant,
            matched_keywords=matched_keywords,
            watch_seconds=watch_seconds,
            page_state=state.page,
            content_kind=state.content_kind,
            state_warnings=state.warnings,
        )

        planned_like = relevant and self._plan_interaction(
            "like", config.like_probability, config.max_likes
        )
        planned_favorite = relevant and self._plan_interaction(
            "favorite", config.favorite_probability, config.max_favorites
        )
        planned_comment = relevant and self._plan_interaction(
            "comment", config.comment_probability, config.max_comments
        )

        if planned_like:
            record.like_action = self.interactions.like(
                apply=config.apply_interactions
            ).action
        if planned_favorite:
            record.favorite_action = self.interactions.favorite(
                apply=config.apply_interactions
            ).action
        if planned_comment:
            record.comment_text = self.rng.choice(config.comment_texts).strip()

        if config.open_comments:
            (
                record.comments,
                record.comment_seconds,
                record.comment_scroll_pauses,
                record.comment_gestures,
                record.reply_threads_expanded,
                record.comment_action,
            ) = self._read_comments(
                config,
                comment_text=record.comment_text if planned_comment else "",
            )

        if not relevant:
            if config.apply_not_interested:
                self._mark_not_interested()
                record.feedback = "not_interested"
            else:
                record.feedback = "would_mark_not_interested"
        record.recovery_actions = list(self._current_recovery_actions)
        return record

    def _read_comments(
        self,
        config: BrowseConfig,
        *,
        comment_text: str,
    ) -> tuple[list[str], float, list[float], list[dict[str, object]], int, str]:
        button = self.device(descriptionContains="阅读或添加评论")
        if not button.exists:
            if comment_text:
                raise RuntimeError("计划评论，但当前视频没有可用的评论入口")
            return [], 0.0, [], [], 0, "none"
        button.click()
        comment_seconds = self._sample(
            config.comment_seconds_min,
            config.comment_seconds_max,
        )
        time.sleep(comment_seconds)

        comments: list[str] = []
        scroll_pauses: list[float] = []
        gesture_reports: list[dict[str, object]] = []
        reply_threads_expanded = 0
        for scroll_index in range(config.comment_scrolls + 1):
            hierarchy = dump_hierarchy_with_retry(self.device)
            state, hierarchy = self._check_and_recover(
                hierarchy, "comments", config
            )
            if state.page != "comments":
                raise RuntimeError(f"预期评论页，实际检测到页面：{state.page}")
            for comment in extract_comment_texts(hierarchy):
                if comment not in comments:
                    comments.append(comment)
            if reply_threads_expanded < config.max_reply_threads:
                expanded = self._expand_one_reply_thread()
                if expanded:
                    reply_threads_expanded += 1
                    hierarchy = dump_hierarchy_with_retry(self.device)
                    for comment in extract_comment_texts(hierarchy):
                        if comment not in comments:
                            comments.append(comment)
            if scroll_index < config.comment_scrolls:
                width, height = self.device.window_size()
                plan = build_gesture_plan(
                    width,
                    height,
                    area_top=0.38,
                    area_bottom=0.86,
                    duration_min=config.comment_swipe_seconds_min,
                    duration_max=config.comment_swipe_seconds_max,
                    context="comments",
                    rng=self.rng,
                )
                gesture_reports.append(execute_gesture(self.device, plan).to_dict())
                pause_seconds = self._sample(
                    config.comment_scroll_pause_min,
                    config.comment_scroll_pause_max,
                )
                scroll_pauses.append(pause_seconds)
                time.sleep(pause_seconds)

        comment_action = "none"
        if comment_text:
            comment_action = self.interactions.comment(
                comment_text,
                apply=config.apply_interactions,
            ).action

        close_button = self.device(description="关闭")
        if close_button.exists:
            close_button.click()
        else:
            self.device.press("back")
        time.sleep(1.0)
        return (
            comments,
            comment_seconds,
            scroll_pauses,
            gesture_reports,
            reply_threads_expanded,
            comment_action,
        )

    def _plan_interaction(self, name: str, probability: float, maximum: int) -> bool:
        if probability <= 0 or self._interaction_attempts[name] >= maximum:
            return False
        if self.rng.random() >= probability:
            return False
        self._interaction_attempts[name] += 1
        return True

    def _expand_one_reply_thread(self) -> bool:
        reply_resource_id = f"{TIKTOK_PACKAGE}:id/tv_reply_comment_title"
        buttons = self.device(resourceId=reply_resource_id)
        for index in range(buttons.count):
            button = buttons[index]
            text = (button.get_text() or "").strip().casefold()
            if not text.startswith(("查看", "view", "voir", "ver")):
                continue
            button.click()
            time.sleep(self._sample(0.7, 1.4))
            return True
        return False

    def _mark_not_interested(self) -> None:
        video = self.device(description="视频")
        if not video.exists:
            raise RuntimeError("找不到当前视频容器，无法标记不感兴趣")
        video.long_click(duration=0.8)
        time.sleep(1.5)
        option = self.device(textContains="不感兴趣")
        if not option.exists:
            self.device.press("back")
            raise RuntimeError("长按菜单中未找到“不感兴趣”")
        option.click()
        time.sleep(1.0)

    def _swipe_to_next_video(
        self,
        config: BrowseConfig,
        *,
        previous_author: str,
        previous_description: str,
    ) -> tuple[list[dict[str, object]], float]:
        width, height = self.device.window_size()
        attempts: list[dict[str, object]] = []
        total_pause = 0.0
        previous_fingerprint = (previous_author.strip(), previous_description.strip())

        for attempt in range(3):
            retrying = attempt > 0
            plan = build_gesture_plan(
                width,
                height,
                area_top=0.10,
                area_bottom=0.84,
                duration_min=0.12 if retrying else config.video_swipe_seconds_min,
                duration_max=0.32 if retrying else config.video_swipe_seconds_max,
                context="video",
                rng=self.rng,
                profile_override="flick" if retrying else None,
            )
            attempts.append(execute_gesture(self.device, plan).to_dict())
            pause_seconds = self._sample(
                config.between_video_pause_min,
                config.between_video_pause_max,
            )
            total_pause += pause_seconds
            time.sleep(pause_seconds)

            hierarchy = dump_hierarchy_with_retry(self.device)
            state, hierarchy = self._check_and_recover(hierarchy, "feed", config)
            if state.page != "feed":
                raise RuntimeError(f"翻页后未回到推荐页，当前页面：{state.page}")
            current = extract_video_content(hierarchy)
            current_fingerprint = (current.author.strip(), current.description.strip())
            if current_fingerprint != previous_fingerprint and any(current_fingerprint):
                return attempts, round(total_pause, 2)

        raise RuntimeError("连续三次变速翻页后视频仍未变化，任务已停止")

    def _check_and_recover(
        self,
        hierarchy: str,
        expected_page: str,
        config: BrowseConfig,
    ) -> tuple[UIState, str]:
        state = detect_ui_state(
            hierarchy,
            network_available=has_validated_network(self.device),
        )
        raise_for_blockers(state)
        planned_interruptions = {
            "keyboard_visible",
            "collections_popup",
            "follow_friends_popup",
            "contacts_sync_prompt",
            "link_email_popup",
            "notification_prompt",
            "promo_banner",
            "generic_dismiss_popup",
            "got_it_popup",
            "interest_survey",
            "notification_banner",
            "suggestion_page",
            "video_options_sheet",
            "system_input_method_dialog",
        }
        needs_recovery = bool(planned_interruptions.intersection(state.interruptions))
        needs_recovery = needs_recovery or (
            state.page == "comments" and expected_page != "comments"
        ) or (
            state.page in HOME_RECOVERABLE_PAGES and expected_page == "feed"
        )
        if not needs_recovery:
            return state, hierarchy
        if not config.auto_recover_interruptions:
            details = "、".join(state.interruptions)
            raise RuntimeError(f"检测到页面干扰但自动恢复已关闭：{details}")

        report = self.recovery.recover(
            expected_page,
            max_actions=config.max_recovery_actions,
        )
        self._current_recovery_actions.extend(report.actions)
        if not report.recovered:
            raise RuntimeError(
                f"恢复后仍未到达 {expected_page}，当前页面：{report.final_state.page}"
            )
        refreshed_hierarchy = dump_hierarchy_with_retry(self.device)
        refreshed_state = detect_ui_state(
            refreshed_hierarchy,
            network_available=has_validated_network(self.device),
        )
        raise_for_blockers(refreshed_state)
        return refreshed_state, refreshed_hierarchy

    def _assert_tiktok_foreground(self) -> None:
        for attempt in range(3):
            current = self.device.app_current()
            if current.get("package") == TIKTOK_PACKAGE:
                return
            if attempt < 2:
                time.sleep(0.5)
        raise RuntimeError("TikTok 连续三次检测都不在前台，任务已停止")

    def _sample(self, minimum: float, maximum: float) -> float:
        return round(self.rng.uniform(minimum, maximum), 2)

    @staticmethod
    def _validate_config(config: BrowseConfig) -> None:
        if not 1 <= config.video_count <= 100:
            raise ValueError("video_count 必须介于 1 和 100 之间")
        ranges = (
            ("watch_seconds", config.watch_seconds_min, config.watch_seconds_max),
            ("comment_seconds", config.comment_seconds_min, config.comment_seconds_max),
            ("comment_scroll_pause", config.comment_scroll_pause_min, config.comment_scroll_pause_max),
            ("comment_swipe_seconds", config.comment_swipe_seconds_min, config.comment_swipe_seconds_max),
            ("video_swipe_seconds", config.video_swipe_seconds_min, config.video_swipe_seconds_max),
            ("between_video_pause", config.between_video_pause_min, config.between_video_pause_max),
        )
        for name, minimum, maximum in ranges:
            if minimum < 0 or maximum < minimum:
                raise ValueError(f"{name} 的区间配置无效")
        if not 0 <= config.comment_scrolls <= 10:
            raise ValueError("comment_scrolls 必须介于 0 和 10 之间")
        if not 0 <= config.max_reply_threads <= 10:
            raise ValueError("max_reply_threads 必须介于 0 和 10 之间")
        if not 0 < config.max_session_minutes <= 120:
            raise ValueError("max_session_minutes 必须介于 0 和 120 之间")
        if not 0 <= config.max_recovery_actions <= 10:
            raise ValueError("max_recovery_actions 必须介于 0 和 10 之间")
        probabilities = (
            ("like_probability", config.like_probability),
            ("favorite_probability", config.favorite_probability),
            ("comment_probability", config.comment_probability),
        )
        for name, probability in probabilities:
            if not 0 <= probability <= 1:
                raise ValueError(f"{name} 必须介于 0 和 1 之间")
        limits = (
            ("max_likes", config.max_likes),
            ("max_favorites", config.max_favorites),
            ("max_comments", config.max_comments),
        )
        for name, maximum in limits:
            if not 0 <= maximum <= 100:
                raise ValueError(f"{name} 必须介于 0 和 100 之间")
        if config.comment_probability > 0:
            if not config.open_comments:
                raise ValueError("启用评论互动时必须同时启用 open_comments")
            if not any(text.strip() for text in config.comment_texts):
                raise ValueError("启用评论互动时至少需要一条非空 comment_text")
