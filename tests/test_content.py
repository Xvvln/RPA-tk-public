from rpa_tt.content import (
    VideoContent,
    classify_relevance,
    extract_comment_texts,
    extract_video_content,
)


def test_extract_video_content() -> None:
    xml = """<hierarchy>
      <node resource-id="com.zhiliaoapp.musically:id/title" text="Creator" content-desc="" />
      <node resource-id="com.zhiliaoapp.musically:id/desc" text="Python tips" content-desc="" />
      <node resource-id="x" text="" content-desc="音乐：Original sound" />
    </hierarchy>"""

    assert extract_video_content(xml) == VideoContent(
        author="Creator",
        description="Python tips",
        music="Original sound",
    )


def test_extract_unique_comment_texts() -> None:
    xml = """<hierarchy>
      <node resource-id="com.zhiliaoapp.musically:id/eu1" text="first" />
      <node resource-id="com.zhiliaoapp.musically:id/eu1" text="first" />
      <node resource-id="com.zhiliaoapp.musically:id/eu1" text="second" />
    </hierarchy>"""

    assert extract_comment_texts(xml) == ["first", "second"]


def test_empty_keywords_treat_every_video_as_relevant() -> None:
    relevant, matched = classify_relevance(VideoContent("A", "B", "C"), [])
    assert relevant is True
    assert matched == []


def test_relevance_matches_case_insensitively() -> None:
    relevant, matched = classify_relevance(
        VideoContent("Creator", "Useful Python Tips", "Original"),
        ["python", "java"],
    )
    assert relevant is True
    assert matched == ["python"]
