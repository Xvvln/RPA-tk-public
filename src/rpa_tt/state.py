from __future__ import annotations

from dataclasses import asdict, dataclass
import time
from xml.etree import ElementTree as ET

import uiautomator2 as u2

from .device import (
    TIKTOK_PACKAGE,
    dump_hierarchy_with_retry,
    has_validated_network,
    is_locked,
)


COMMENT_FIELD_ID = f"{TIKTOK_PACKAGE}:id/eal"
COMMENT_BUTTON_ID = f"{TIKTOK_PACKAGE}:id/ef2"
FAVORITE_BUTTON_ID = f"{TIKTOK_PACKAGE}:id/hqz"
LIKE_BUTTON_ID = f"{TIKTOK_PACKAGE}:id/fud"
SEARCH_FIELD_ID = f"{TIKTOK_PACKAGE}:id/hdj"
SEARCH_SUBMIT_ID = f"{TIKTOK_PACKAGE}:id/tv_search_textview"


BLOCKER_LABELS = {
    "screen_off": "屏幕已关闭",
    "device_locked": "手机处于锁屏状态",
    "tiktok_not_foreground": "TikTok 不在前台",
    "invalid_hierarchy": "页面结构无法解析",
    "verification_required": "需要人工完成验证",
    "login_required": "登录状态失效或页面要求登录",
    "network_error": "页面显示网络或加载错误",
    "rate_limited": "操作过于频繁或受到频率限制",
    "account_restricted": "账号受到限制",
    "content_unavailable": "当前内容不可用",
    "permission_required": "系统权限弹窗需要人工处理",
    "update_required": "TikTok 要求更新应用",
    "age_gate": "页面要求确认年龄",
    "privacy_consent_required": "隐私或数据传输确认需要人工处理",
    "unknown_system_dialog": "未知系统弹窗需要人工处理",
    "network_unavailable": "Android 未检测到可用网络",
}

INTERRUPTION_ACTIONS = {
    "keyboard_visible": "hide_keyboard",
    "comments_open": "close_comments",
    "collections_popup": "dismiss_popup",
    "follow_friends_popup": "dismiss_popup",
    "link_email_popup": "dismiss_popup",
    "promo_banner": "dismiss_popup",
    "generic_dismiss_popup": "dismiss_popup",
    "notification_banner": "dismiss_notification",
    "suggestion_page": "skip_suggestion",
    "video_options_sheet": "press_back",
    "system_input_method_dialog": "press_back",
    "inbox_page": "navigate_home",
}


@dataclass(frozen=True)
class UIState:
    current_package: str
    page: str
    content_kind: str
    safe_to_continue: bool
    blockers: list[str]
    blocker_labels: list[str]
    warnings: list[str]
    interruptions: list[str]
    recommended_actions: list[str]
    capabilities: dict[str, bool]
    features: dict[str, bool]
    keyboard_visible: bool

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


def _contains_any(haystack: str, markers: tuple[str, ...]) -> bool:
    return any(marker.casefold() in haystack for marker in markers)


def detect_ui_state(
    hierarchy: str,
    *,
    current_package: str = TIKTOK_PACKAGE,
    screen_on: bool = True,
    locked: bool = False,
    network_available: bool | None = None,
) -> UIState:
    blockers: list[str] = []
    warnings: list[str] = []
    interruptions: list[str] = []

    if not screen_on:
        blockers.append("screen_off")
    if locked:
        blockers.append("device_locked")
    if current_package != TIKTOK_PACKAGE:
        blockers.append("tiktok_not_foreground")
    if network_available is False:
        blockers.append("network_unavailable")

    try:
        root = ET.fromstring(hierarchy)
        nodes = [node.attrib for node in root.iter("node")]
    except ET.ParseError:
        nodes = []
        blockers.append("invalid_hierarchy")

    resource_ids = {node.get("resource-id", "") for node in nodes}
    node_packages = {node.get("package", "") for node in nodes}
    text_blob = " ".join(
        value.strip()
        for node in nodes
        for value in (
            node.get("text", ""),
            node.get("content-desc", ""),
            node.get("hint", ""),
        )
        if value.strip()
    ).casefold()
    user_content_suffixes = (
        ":id/desc",
        ":id/eu1",
        ":id/title",
        ":id/tv_reply_to_reply",
    )
    system_text_blob = " ".join(
        value.strip()
        for node in nodes
        if not node.get("resource-id", "").endswith(user_content_suffixes)
        for value in (
            node.get("text", ""),
            node.get("content-desc", ""),
            node.get("hint", ""),
        )
        if value.strip()
    ).casefold()

    blocker_markers = {
        "verification_required": (
            "请完成验证",
            "验证以继续",
            "verify to continue",
            "complete the verification",
            "captcha",
            "拖动滑块",
        ),
        "login_required": (
            "登录以继续",
            "注册或登录",
            "log in to continue",
            "sign in to continue",
            "log in or sign up",
        ),
        "network_error": (
            "无网络连接",
            "网络连接不稳定",
            "网络异常",
            "加载失败",
            "点击重试",
            "no internet connection",
            "network error",
            "couldn't load",
            "tap to retry",
            "something went wrong",
        ),
        "rate_limited": (
            "操作过于频繁",
            "操作太快",
            "请稍后再试",
            "too many attempts",
            "you're tapping too fast",
            "try again later",
        ),
        "account_restricted": (
            "账号已被封禁",
            "账号已被停用",
            "账号受到限制",
            "account suspended",
            "account banned",
            "your account has been restricted",
        ),
        "content_unavailable": (
            "视频不可用",
            "内容不可用",
            "视频已被删除",
            "此账号为私密账号",
            "video unavailable",
            "content unavailable",
            "this video has been removed",
            "private account",
        ),
        "update_required": (
            "请更新 tiktok",
            "需要更新",
            "update tiktok",
            "update required",
        ),
        "age_gate": (
            "确认你的年龄",
            "请输入你的生日",
            "confirm your age",
            "enter your birthday",
        ),
    }
    for code, markers in blocker_markers.items():
        if _contains_any(system_text_blob, markers):
            blockers.append(code)

    permission_packages = {
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    }
    permission_text = _contains_any(
        system_text_blob,
        (
            "允许 tiktok",
            "while using the app",
            "only this time",
            "仅在使用该应用时",
            "仅限这一次",
        ),
    )
    if node_packages.intersection(permission_packages) or permission_text:
        blockers.append("permission_required")

    privacy_notice = _contains_any(
        system_text_blob,
        (
            "欧洲经济区用户数据",
            "数据传输至中国",
            "transfers of eea user data to china",
            "eea user data",
            "transferts de données des utilisateurs de l'eee",
        ),
    )
    if privacy_notice:
        blockers.append("privacy_consent_required")

    system_input_method_dialog = (
        "android:id/select_dialog_listview" in resource_ids
        or _contains_any(
            system_text_blob,
            (
                "选择输入法",
                "选择键盘",
                "choose input method",
                "select input method",
            ),
        )
    )
    if system_input_method_dialog:
        interruptions.append("system_input_method_dialog")

    has_unknown_system_dialog = any(
        node.get("package") == "android"
        and node.get("resource-id") == "android:id/parentPanel"
        for node in nodes
    ) and not system_input_method_dialog
    if has_unknown_system_dialog:
        blockers.append("unknown_system_dialog")

    keyboard_visible = any(
        package.startswith(("com.tencent.wetype", "com.google.android.inputmethod"))
        for package in node_packages
    )
    if keyboard_visible:
        warnings.append("keyboard_visible")
        interruptions.append("keyboard_visible")

    has_comment_field = COMMENT_FIELD_ID in resource_ids
    has_search_field = SEARCH_FIELD_ID in resource_ids
    has_comment_list = any(
        resource_id.endswith(":id/eu1") for resource_id in resource_ids
    )
    has_comments_close = any(
        node.get("content-desc", "").strip() in {"关闭", "Close"}
        for node in nodes
    )
    has_comments_header = any(
        node.get("resource-id", "").endswith(":id/vcu")
        and _contains_any(
            node.get("text", "").casefold(),
            ("条评论", " comments"),
        )
        for node in nodes
    )
    comments_panel_open = has_comment_field and (
        has_comment_list or has_comments_close or has_comments_header
    )
    inline_comment_composer = has_comment_field and not comments_panel_open
    search_empty = _contains_any(
        text_blob,
        ("未找到相关结果", "没有找到相关结果", "no results found"),
    )

    inbox_selected = any(
        node.get("selected") == "true"
        and _contains_any(
            " ".join(
                (node.get("text", ""), node.get("content-desc", ""))
            ).casefold(),
            ("收件箱", "inbox"),
        )
        for node in nodes
    )
    shop_selected = any(
        node.get("selected") == "true"
        and _contains_any(
            " ".join(
                (node.get("text", ""), node.get("content-desc", ""))
            ).casefold(),
            ("商城", "shop"),
        )
        for node in nodes
    )

    if comments_panel_open:
        page = "comments"
    elif has_search_field and search_empty:
        page = "search_empty"
    elif has_search_field and SEARCH_SUBMIT_ID in resource_ids:
        page = "search_input"
    elif has_search_field:
        page = "search_results"
    elif _contains_any(text_blob, ("内容偏好", "content preferences")):
        page = "content_preferences"
    elif _contains_any(text_blob, ("设置与隐私", "settings and privacy")):
        page = "settings"
    elif _contains_any(text_blob, ("主页菜单", "profile menu")):
        page = "profile"
    elif inbox_selected:
        page = "inbox"
    elif shop_selected:
        page = "shop"
    elif any(resource_id.endswith(":id/title") for resource_id in resource_ids) and (
        LIKE_BUTTON_ID in resource_ids or COMMENT_BUTTON_ID in resource_ids
    ):
        page = "feed"
    else:
        page = "unknown"

    if page == "feed":
        if any(
            node.get("resource-id", "").endswith(":id/zt2")
            and node.get("text", "").strip() in {"照片", "Photo"}
            for node in nodes
        ):
            content_kind = "photo"
            warnings.append("photo_post")
        elif _contains_any(system_text_blob, ("直播中", "live now")):
            content_kind = "live"
            warnings.append("live_content")
        elif _contains_any(system_text_blob, ("赞助内容", "sponsored")):
            content_kind = "advertisement"
            warnings.append("sponsored_content")
        else:
            content_kind = "video"
    else:
        content_kind = "none"

    comments_disabled = _contains_any(
        system_text_blob,
        ("评论已关闭", "评论功能已关闭", "comments are turned off"),
    )
    if comments_disabled:
        warnings.append("comments_disabled")
    if page == "comments":
        interruptions.append("comments_open")
    if page == "inbox":
        interruptions.append("inbox_page")
    if search_empty:
        warnings.append("search_empty")

    interruption_markers = {
        "collections_popup": (
            "共享收藏",
            "与好友创建收藏",
            "shared collections",
            "collections with a friend",
        ),
        "follow_friends_popup": (
            "关注你的朋友",
            "查找联系人",
            "follow your friends",
            "find your contacts",
            "find friends",
        ),
        "contacts_sync_prompt": (
            "同步手机通讯录好友",
            "同步通讯录好友",
            "访问你的通讯录",
            "sync your phone contacts",
            "sync your contacts",
            "access your contacts",
        ),
        "link_email_popup": (
            "关联邮箱",
            "连接邮箱",
            "添加邮箱",
            "link email",
            "linking your android email",
        ),
        "notification_prompt": (
            "开启通知",
            "打开通知",
            "打开推送通知",
            "允许通知",
            "turn on notifications",
            "enable notifications",
            "turn on notification",
        ),
        "promo_banner": (
            "连续互动宠物",
            "孵化连续互动",
            "hatch a streak pet",
            "新功能",
        ),
        "notification_banner": (
            "给你发来了新消息",
            "向你发送了消息",
            "sent you new messages",
            "sent you a message",
        ),
        "suggestion_page": (
            "上滑跳过",
            "swipe up to skip",
        ),
        "interest_survey": (
            "选择你感兴趣",
            "choose your interests",
            "内容偏好",
            "content preferences",
        ),
        "got_it_popup": (
            "我知道了",
            "知道了",
            "got it",
        ),
    }
    for code, markers in interruption_markers.items():
        if _contains_any(system_text_blob, markers):
            interruptions.append(code)

    video_options_sheet = any(
        node.get("content-desc", "").strip().casefold()
        in {"bottom sheet", "底部工作表"}
        for node in nodes
    ) and _contains_any(
        system_text_blob,
        ("下载", "举报", "播放速度", "不感兴趣", "download", "report", "speed", "not interested"),
    )
    if video_options_sheet:
        interruptions.append("video_options_sheet")

    known_popup = any(
        code in interruptions
        for code in (
            "collections_popup",
            "follow_friends_popup",
            "contacts_sync_prompt",
            "link_email_popup",
            "notification_prompt",
            "promo_banner",
            "got_it_popup",
            "interest_survey",
        )
    )
    generic_dismiss = _contains_any(
        system_text_blob,
        (
            "以后再说",
            "下次再说",
            "稍后再说",
            "暂不",
            "先不了",
            "不用了",
            "not now",
            "maybe later",
            "no thanks",
            "no, thanks",
            "dismiss",
        ),
    )
    if generic_dismiss and not known_popup:
        interruptions.append("generic_dismiss_popup")

    interruptions = list(dict.fromkeys(interruptions))
    for interruption in interruptions:
        warning = f"interruption:{interruption}"
        if warning not in warnings:
            warnings.append(warning)
    if page == "unknown" and not blockers:
        warnings.append("unknown_page")

    blocked = bool(blockers)
    capabilities = {
        "can_search": not blocked
        and (has_search_field or _contains_any(text_blob, ("搜索", "search"))),
        "can_like": not blocked and page == "feed" and LIKE_BUTTON_ID in resource_ids,
        "can_favorite": not blocked
        and page == "feed"
        and FAVORITE_BUTTON_ID in resource_ids,
        "can_comment": not blocked
        and not comments_disabled
        and (COMMENT_BUTTON_ID in resource_ids or has_comment_field),
        "can_read_comments": not blocked
        and (COMMENT_BUTTON_ID in resource_ids or comments_panel_open),
        "can_swipe_feed": not blocked and page == "feed",
        "can_open_search_result": not blocked and page == "search_results",
    }
    features = {
        "comment_entry_available": COMMENT_BUTTON_ID in resource_ids,
        "inline_comment_composer": inline_comment_composer,
        "comments_panel_open": comments_panel_open,
        "like_button_available": LIKE_BUTTON_ID in resource_ids,
        "favorite_button_available": FAVORITE_BUTTON_ID in resource_ids,
    }
    safe_to_continue = not blocked and page != "unknown"
    recommended_actions = list(
        dict.fromkeys(
            INTERRUPTION_ACTIONS[code]
            for code in interruptions
            if code in INTERRUPTION_ACTIONS
        )
    )
    return UIState(
        current_package=current_package,
        page=page,
        content_kind=content_kind,
        safe_to_continue=safe_to_continue,
        blockers=blockers,
        blocker_labels=[BLOCKER_LABELS[code] for code in blockers],
        warnings=warnings,
        interruptions=interruptions,
        recommended_actions=recommended_actions,
        capabilities=capabilities,
        features=features,
        keyboard_visible=keyboard_visible,
    )


def inspect_device_state(device: u2.Device) -> UIState:
    last_error: Exception | None = None
    for attempt in range(3):
        try:
            info = device.info
            screen_on = bool(info.get("screenOn", False))
            locked = is_locked(device)
            current_package = device.app_current().get("package", "")
            hierarchy = (
                dump_hierarchy_with_retry(device, attempts=1)
                if screen_on
                else "<hierarchy />"
            )
            return detect_ui_state(
                hierarchy,
                current_package=current_package,
                screen_on=screen_on,
                locked=locked,
                network_available=has_validated_network(device),
            )
        except Exception as error:
            last_error = error
            if attempt == 2:
                break
            try:
                device.start_uiautomator()
            except Exception:
                pass
            time.sleep(0.5)
    raise RuntimeError(
        "连续 3 次读取设备状态失败，请检查 ADB/UIAutomator 连接"
    ) from last_error


def raise_for_blockers(state: UIState) -> None:
    if state.blockers:
        details = "、".join(state.blocker_labels)
        raise RuntimeError(f"状态检测已停止任务：{details}")
