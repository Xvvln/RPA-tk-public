from rpa_tt.interactions import favorite_color_ratio, is_favorite_color


class SolidImage:
    def __init__(self, color: tuple[int, int, int]) -> None:
        self.color = color

    def getpixel(self, _position: tuple[int, int]) -> tuple[int, int, int]:
        return self.color


def test_selected_favorite_color_is_yellow() -> None:
    assert is_favorite_color(246, 201, 20)
    assert not is_favorite_color(223, 223, 223)


def test_favorite_color_ratio_uses_icon_center() -> None:
    bounds = {"left": 888, "top": 1659, "right": 1080, "bottom": 1839}
    assert favorite_color_ratio(SolidImage((246, 201, 20)), bounds) == 1.0
    assert favorite_color_ratio(SolidImage((223, 223, 223)), bounds) == 0.0
