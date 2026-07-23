import pytest

from rpa_tt.browse import BrowseConfig, BrowseRunner


def test_interaction_probabilities_must_be_between_zero_and_one() -> None:
    with pytest.raises(ValueError, match="like_probability"):
        BrowseRunner._validate_config(BrowseConfig(like_probability=1.1))


def test_comment_rate_requires_comment_text() -> None:
    with pytest.raises(ValueError, match="comment_text"):
        BrowseRunner._validate_config(BrowseConfig(comment_probability=0.5))


def test_comment_rate_requires_open_comments() -> None:
    with pytest.raises(ValueError, match="open_comments"):
        BrowseRunner._validate_config(
            BrowseConfig(
                comment_probability=0.5,
                comment_texts=("Nice",),
                open_comments=False,
            )
        )
