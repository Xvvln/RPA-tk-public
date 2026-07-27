from rpa_tt.device import TIKTOK_PACKAGE
from rpa_tt.recovery import plan_recovery, plan_recovery_steps
from rpa_tt.state import detect_ui_state


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


def test_comments_are_kept_when_comments_are_expected() -> None:
    state = detect_ui_state(
        xml(
            node(resource_id=f"{TIKTOK_PACKAGE}:id/eal"),
            node(description="关闭"),
        )
    )
    assert plan_recovery(state, "comments") == []
    assert plan_recovery(state, "feed") == ["close_comments"]


def test_inbox_recovery_depends_on_workflow_target() -> None:
    state = detect_ui_state(xml(node(text="Inbox", selected="true")))
    assert plan_recovery(state, "inbox") == []
    assert plan_recovery(state, "feed") == ["navigate_home"]


def test_profile_recovery_depends_on_workflow_target() -> None:
    state = detect_ui_state(xml(node(text="主页菜单")))
    assert plan_recovery(state, "profile") == []
    assert plan_recovery(state, "feed") == ["navigate_home"]


def test_shop_page_returns_home_for_feed_workflow() -> None:
    state = detect_ui_state(xml(node(text="商城", selected="true")))
    assert plan_recovery(state, "feed") == ["navigate_home"]


def test_manual_blocker_never_generates_auto_recovery() -> None:
    state = detect_ui_state(xml(node(text="请完成验证")))
    assert plan_recovery(state, "feed") == []


def test_bottom_sheet_is_closed_before_generic_popup() -> None:
    state = detect_ui_state(
        xml(
            node(description="Bottom sheet"),
            node(text="Download"),
            node(text="Not now"),
        )
    )
    assert plan_recovery(state, "feed") == ["press_back", "dismiss_popup"]


def test_got_it_and_notification_prompt_are_recoverable() -> None:
    got_it = detect_ui_state(xml(node(text="我知道了"), node(text="功能说明")))
    notify = detect_ui_state(
        xml(node(text="开启通知，及时收到消息"), node(text="以后再说"))
    )
    assert plan_recovery(got_it, "feed") == ["dismiss_got_it"]
    assert "dismiss_popup" in plan_recovery(notify, "feed")
    assert "notification_prompt" in notify.interruptions


def test_contacts_sync_uses_deny_action_with_code() -> None:
    state = detect_ui_state(
        xml(node(text="同步手机通讯录好友"), node(text="不允许"), node(text="以后再说"))
    )
    assert "contacts_sync_prompt" in state.interruptions
    steps = plan_recovery_steps(state, "feed")
    assert steps[0].interruption_code == "contacts_sync_prompt"
    assert steps[0].action == "deny_contacts"
    assert plan_recovery(state, "feed")[0] == "deny_contacts"


def test_blocker_plan_has_no_steps() -> None:
    state = detect_ui_state(xml(node(text="请完成验证"), node(text="以后再说")))
    assert plan_recovery_steps(state, "feed") == []


def test_interest_survey_uses_skip_recovery() -> None:
    state = detect_ui_state(
        xml(node(text="选择你感兴趣的内容"), node(text="跳过"))
    )
    assert "interest_survey" in state.interruptions
    assert plan_recovery(state, "feed") == ["dismiss_interest_survey"]
