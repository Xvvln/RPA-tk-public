import pytest

from rpa_tt.device import TIKTOK_PACKAGE
from rpa_tt.state import detect_ui_state, raise_for_blockers


def xml(*nodes: str) -> str:
    return f"<hierarchy>{''.join(nodes)}</hierarchy>"


def node(
    *,
    text: str = "",
    resource_id: str = "",
    description: str = "",
    package: str = TIKTOK_PACKAGE,
    selected: str = "false",
) -> str:
    return (
        f'<node text="{text}" resource-id="{resource_id}" '
        f'content-desc="{description}" package="{package}" '
        f'selected="{selected}" />'
    )


def test_detect_photo_feed_and_capabilities() -> None:
    hierarchy = xml(
        node(text="creator", resource_id=f"{TIKTOK_PACKAGE}:id/title"),
        node(resource_id=f"{TIKTOK_PACKAGE}:id/fud"),
        node(resource_id=f"{TIKTOK_PACKAGE}:id/hqz"),
        node(resource_id=f"{TIKTOK_PACKAGE}:id/ef2"),
        node(text="照片", resource_id=f"{TIKTOK_PACKAGE}:id/zt2"),
    )
    state = detect_ui_state(hierarchy)
    assert state.page == "feed"
    assert state.content_kind == "photo"
    assert state.capabilities["can_like"]
    assert state.capabilities["can_favorite"]
    assert state.capabilities["can_comment"]
    assert state.features["comment_entry_available"]
    assert not state.features["inline_comment_composer"]
    assert not state.features["comments_panel_open"]


def test_detect_comments_and_keyboard() -> None:
    hierarchy = xml(
        node(resource_id=f"{TIKTOK_PACKAGE}:id/eal"),
        node(description="关闭"),
        node(package="com.tencent.wetype", text="Q"),
    )
    state = detect_ui_state(hierarchy)
    assert state.page == "comments"
    assert state.keyboard_visible
    assert "keyboard_visible" in state.warnings
    assert state.features["comments_panel_open"]
    assert not state.features["inline_comment_composer"]


def test_inline_comment_composer_stays_on_feed() -> None:
    hierarchy = xml(
        node(text="creator", resource_id=f"{TIKTOK_PACKAGE}:id/title"),
        node(resource_id=f"{TIKTOK_PACKAGE}:id/fud"),
        node(resource_id=f"{TIKTOK_PACKAGE}:id/hqz"),
        node(resource_id=f"{TIKTOK_PACKAGE}:id/ef2"),
        node(resource_id=f"{TIKTOK_PACKAGE}:id/eal"),
    )
    state = detect_ui_state(hierarchy)
    assert state.page == "feed"
    assert state.content_kind == "video"
    assert state.capabilities["can_like"]
    assert state.capabilities["can_comment"]
    assert state.features["comment_entry_available"]
    assert state.features["inline_comment_composer"]
    assert not state.features["comments_panel_open"]


def test_detect_empty_search_as_warning_not_blocker() -> None:
    hierarchy = xml(
        node(text="GPT", resource_id=f"{TIKTOK_PACKAGE}:id/hdj"),
        node(text="未找到相关结果"),
    )
    state = detect_ui_state(hierarchy)
    assert state.page == "search_empty"
    assert state.safe_to_continue
    assert state.blockers == []
    assert "search_empty" in state.warnings


@pytest.mark.parametrize(
    ("message", "code"),
    [
        ("请完成验证", "verification_required"),
        ("登录以继续", "login_required"),
        ("网络异常，点击重试", "network_error"),
        ("操作过于频繁，请稍后再试", "rate_limited"),
        ("账号受到限制", "account_restricted"),
        ("视频不可用", "content_unavailable"),
        ("请更新 TikTok", "update_required"),
        ("确认你的年龄", "age_gate"),
    ],
)
def test_detect_blocking_states(message: str, code: str) -> None:
    state = detect_ui_state(xml(node(text=message)))
    assert code in state.blockers
    assert not state.safe_to_continue
    with pytest.raises(RuntimeError, match="状态检测已停止任务"):
        raise_for_blockers(state)


def test_detect_permission_dialog() -> None:
    state = detect_ui_state(
        xml(node(text="仅在使用该应用时", package="com.android.permissioncontroller"))
    )
    assert "permission_required" in state.blockers


def test_detect_input_method_dialog_as_recoverable() -> None:
    state = detect_ui_state(
        xml(
            node(
                text="选择输入法",
                resource_id="android:id/select_dialog_listview",
                package="android",
            )
        )
    )
    assert "system_input_method_dialog" in state.interruptions
    assert "unknown_system_dialog" not in state.blockers
    assert "press_back" in state.recommended_actions


def test_unknown_android_dialog_requires_manual_attention() -> None:
    state = detect_ui_state(
        xml(node(resource_id="android:id/parentPanel", package="android"))
    )
    assert "unknown_system_dialog" in state.blockers
    assert not state.safe_to_continue


def test_invalid_hierarchy_stops_the_task() -> None:
    state = detect_ui_state("<hierarchy>")
    assert "invalid_hierarchy" in state.blockers
    assert not state.safe_to_continue


def test_user_comment_cannot_trigger_false_rate_limit() -> None:
    state = detect_ui_state(
        xml(
            node(resource_id=f"{TIKTOK_PACKAGE}:id/eal"),
            node(
                text="try again later",
                resource_id=f"{TIKTOK_PACKAGE}:id/eu1",
            ),
        )
    )
    assert state.page == "comments"
    assert "rate_limited" not in state.blockers


def test_detect_device_level_blockers() -> None:
    state = detect_ui_state(
        "<hierarchy />",
        current_package="com.android.launcher",
        screen_on=False,
        locked=True,
    )
    assert state.blockers == [
        "screen_off",
        "device_locked",
        "tiktok_not_foreground",
    ]


def test_detect_network_unavailable_even_with_cached_feed() -> None:
    state = detect_ui_state(
        xml(
            node(text="creator", resource_id=f"{TIKTOK_PACKAGE}:id/title"),
            node(resource_id=f"{TIKTOK_PACKAGE}:id/fud"),
        ),
        network_available=False,
    )
    assert "network_unavailable" in state.blockers
    assert not state.safe_to_continue


def test_unknown_page_is_not_safe_to_continue() -> None:
    state = detect_ui_state(xml(node(text="unrecognized")))
    assert state.page == "unknown"
    assert not state.safe_to_continue
    assert "unknown_page" in state.warnings


@pytest.mark.parametrize(
    ("message", "interruption"),
    [
        ("与好友创建共享收藏", "collections_popup"),
        ("关注你的朋友", "follow_friends_popup"),
        ("关联邮箱", "link_email_popup"),
        ("Hatch a Streak Pet", "promo_banner"),
        ("Alex sent you new messages", "notification_banner"),
        ("Swipe up to skip", "suggestion_page"),
        ("以后再说", "generic_dismiss_popup"),
    ],
)
def test_detect_recoverable_interruptions(message: str, interruption: str) -> None:
    state = detect_ui_state(xml(node(text=message)))
    assert interruption in state.interruptions
    assert state.recommended_actions


def test_privacy_notice_requires_manual_attention() -> None:
    state = detect_ui_state(xml(node(text="Transfers of EEA User Data to China")))
    assert "privacy_consent_required" in state.blockers


def test_detect_inbox_only_when_selected() -> None:
    state = detect_ui_state(xml(node(text="收件箱", selected="true")))
    assert state.page == "inbox"
    assert "inbox_page" in state.interruptions


def test_detect_shop_only_when_selected() -> None:
    state = detect_ui_state(xml(node(text="商城", selected="true")))
    assert state.page == "shop"


def test_detect_video_options_bottom_sheet() -> None:
    state = detect_ui_state(
        xml(
            node(description="Bottom sheet"),
            node(text="下载"),
            node(text="举报"),
        )
    )
    assert "video_options_sheet" in state.interruptions
    assert "press_back" in state.recommended_actions


def test_detect_localized_video_options_bottom_sheet() -> None:
    state = detect_ui_state(
        xml(
            node(description="底部工作表"),
            node(text="下载"),
            node(text="举报"),
        )
    )
    assert "video_options_sheet" in state.interruptions
