from __future__ import annotations

from dataclasses import asdict, dataclass, field
from xml.etree import ElementTree as ET


@dataclass(frozen=True)
class VideoContent:
    author: str
    description: str
    music: str


@dataclass
class VideoRecord:
    index: int
    author: str
    description: str
    music: str
    relevant: bool
    matched_keywords: list[str] = field(default_factory=list)
    comments: list[str] = field(default_factory=list)
    feedback: str = "none"
    watch_seconds: float = 0.0
    comment_seconds: float = 0.0
    comment_scroll_pauses: list[float] = field(default_factory=list)
    comment_gestures: list[dict[str, object]] = field(default_factory=list)
    reply_threads_expanded: int = 0
    like_action: str = "none"
    favorite_action: str = "none"
    comment_action: str = "none"
    comment_text: str = ""
    page_state: str = "unknown"
    content_kind: str = "unknown"
    state_warnings: list[str] = field(default_factory=list)
    recovery_actions: list[str] = field(default_factory=list)
    next_video_swipe_seconds: float = 0.0
    next_video_pause_seconds: float = 0.0
    next_video_gesture: dict[str, object] | None = None
    next_video_gesture_attempts: list[dict[str, object]] = field(default_factory=list)

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


def _nodes(xml: str) -> list[dict[str, str]]:
    root = ET.fromstring(xml)
    return [node.attrib for node in root.iter("node")]


def extract_video_content(xml: str) -> VideoContent:
    author = ""
    description = ""
    music = ""
    for node in _nodes(xml):
        resource_id = node.get("resource-id", "")
        text = node.get("text", "").strip()
        content_description = node.get("content-desc", "").strip()
        if resource_id.endswith(":id/title") and text and not author:
            author = text
        elif resource_id.endswith(":id/desc") and text and not description:
            description = text
        elif content_description.startswith("音乐：") and not music:
            music = content_description.removeprefix("音乐：").strip()
    return VideoContent(author=author, description=description, music=music)


def extract_comment_texts(xml: str) -> list[str]:
    comments: list[str] = []
    for node in _nodes(xml):
        resource_id = node.get("resource-id", "")
        text = node.get("text", "").strip()
        if resource_id.endswith(":id/eu1") and text and text not in comments:
            comments.append(text)
    return comments


def classify_relevance(
    content: VideoContent,
    interest_keywords: list[str],
) -> tuple[bool, list[str]]:
    normalized_keywords = [keyword.strip().casefold() for keyword in interest_keywords if keyword.strip()]
    if not normalized_keywords:
        return True, []
    haystack = " ".join((content.author, content.description, content.music)).casefold()
    matched = [keyword for keyword in normalized_keywords if keyword in haystack]
    return bool(matched), matched
