from rpa_tt.interrupt_policy import (
    PRIMARY_ACCEPT_BLACKLIST,
    SOFT_DISMISS_LABELS,
    filter_soft_labels,
    is_primary_accept_label,
    is_soft_dismiss_label,
    soft_and_blacklist_disjoint,
    soft_dismiss_click_labels,
)


def test_soft_and_blacklist_are_disjoint() -> None:
    assert soft_and_blacklist_disjoint()
    for label in SOFT_DISMISS_LABELS:
        assert not is_primary_accept_label(label)
        assert is_soft_dismiss_label(label)
    for label in PRIMARY_ACCEPT_BLACKLIST:
        assert is_primary_accept_label(label)
        assert not is_soft_dismiss_label(label)


def test_filter_soft_labels_drops_primary_accept() -> None:
    filtered = filter_soft_labels(
        ["以后再说", "Allow", "Continue", "Not now", "同意", "  ", ""]
    )
    assert filtered == ("以后再说", "Not now")
    assert all(not is_primary_accept_label(item) for item in filtered)


def test_soft_dismiss_click_labels_never_include_allow() -> None:
    labels = soft_dismiss_click_labels()
    assert "以后再说" in labels
    assert "Allow" not in labels
    assert "同意" not in labels
