#!/usr/bin/env python3
"""Convert a uiautomator dump into a compact fixture JSON for offline tests."""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def parse_bounds(value: str) -> list[int] | None:
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", value or "")
    if not m:
        return None
    return [int(m.group(i)) for i in range(1, 5)]


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: dump_to_fixture.py <ui-hierarchy.xml> <out.json> [tag]", file=sys.stderr)
        return 2
    src = Path(sys.argv[1])
    out = Path(sys.argv[2])
    tag = sys.argv[3] if len(sys.argv) > 3 else src.parent.name

    tree = ET.parse(src)
    nodes = []
    for el in tree.iter("node"):
        text = el.attrib.get("text") or ""
        desc = el.attrib.get("content-desc") or ""
        rid = el.attrib.get("resource-id") or ""
        pkg = el.attrib.get("package") or ""
        cls = el.attrib.get("class") or ""
        clickable = el.attrib.get("clickable") == "true"
        bounds = parse_bounds(el.attrib.get("bounds", ""))
        if not (text or desc or clickable):
            continue
        nodes.append(
            {
                "text": text,
                "content_desc": desc,
                "resource_id": rid,
                "package": pkg,
                "class": cls,
                "clickable": clickable,
                "bounds": bounds,
            }
        )

    labels = [n["text"] or n["content_desc"] for n in nodes if (n["text"] or n["content_desc"])]
    markers = {
        "video_options_sheet": any(
            x in labels for x in ("底部工作表", "Bottom sheet", "不感兴趣", "下载", "举报")
        ),
        "comments": any("评论" in (x or "") for x in labels) and any(
            "关闭" in (x or "") or "添加评论" in (x or "") for x in labels
        ),
        "soft_cta": any(
            x in labels
            for x in ("以后再说", "Not now", "Maybe later", "稍后再说", "跳过", "Got it", "我知道了")
        ),
        "share_sheet": any(x in labels for x in ("分享至", "转发", "发送")),
    }

    payload = {
        "tag": tag,
        "source": str(src),
        "node_count": len(nodes),
        "markers": markers,
        "sample_labels": labels[:80],
        "nodes": nodes[:400],
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"out": str(out), "markers": markers, "nodes": len(nodes)}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
