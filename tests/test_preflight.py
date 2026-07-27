from rpa_tt.preflight import evaluate_environment


def test_all_green_passes() -> None:
    report = evaluate_environment(
        accessibility_connected=True,
        screen_on=True,
        device_locked=False,
        require_network=True,
        network_available=True,
        tiktok_installed=True,
        overlay_allowed=True,
        notifications_ok=True,
    )
    assert report.status == "pass"
    assert report.hard_failures == ()
    assert report.warnings == ()
    payload = report.to_dict()
    assert payload["status"] == "pass"
    assert len(payload["checks"]) == 7


def test_accessibility_hard_fail() -> None:
    report = evaluate_environment(
        accessibility_connected=False,
        screen_on=True,
        device_locked=False,
        require_network=True,
        network_available=True,
        tiktok_installed=True,
    )
    assert report.status == "fail"
    assert report.hard_failures[0].id == "P01"
    assert report.hard_failures[0].code == "accessibility_disabled"


def test_overlay_soft_warning() -> None:
    report = evaluate_environment(
        accessibility_connected=True,
        screen_on=True,
        device_locked=False,
        require_network=True,
        network_available=True,
        tiktok_installed=True,
        overlay_allowed=False,
    )
    assert report.status == "pass_with_warnings"
    assert report.hard_failures == ()
    assert report.warnings[0].code == "overlay_unavailable"


def test_network_optional() -> None:
    report = evaluate_environment(
        accessibility_connected=True,
        screen_on=True,
        device_locked=False,
        require_network=False,
        network_available=False,
        tiktok_installed=True,
    )
    assert report.status == "pass"


def test_unknown_network_when_required_fails() -> None:
    report = evaluate_environment(
        accessibility_connected=True,
        screen_on=True,
        device_locked=False,
        require_network=True,
        network_available=None,
        tiktok_installed=True,
    )
    assert report.status == "fail"
    assert report.hard_failures[0].code == "network_unavailable"
