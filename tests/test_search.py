from rpa_tt.tiktok import parse_search_result


def hierarchy(*nodes: str) -> str:
    return f"<hierarchy>{''.join(nodes)}</hierarchy>"


def test_parse_empty_search_result() -> None:
    xml = hierarchy(
        '<node text="GPT" resource-id="com.zhiliaoapp.musically:id/hdj" />',
        '<node text="未找到相关结果" resource-id="com.zhiliaoapp.musically:id/y3k" />',
    )
    result = parse_search_result(xml, "GPT")
    assert result.status == "empty"


def test_parse_loaded_search_result() -> None:
    xml = hierarchy(
        '<node text="GPT" resource-id="com.zhiliaoapp.musically:id/hdj" />',
        '<node text="综合" selected="true" />',
        '<node text="ChatGPT tutorial" />',
    )
    result = parse_search_result(xml, "GPT")
    assert result.status == "results"
    assert "ChatGPT tutorial" in result.visible_texts


def test_parse_unknown_page() -> None:
    result = parse_search_result(hierarchy('<node text="加载中" />'), "GPT")
    assert result.status == "unknown"
