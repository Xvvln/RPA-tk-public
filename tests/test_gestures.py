import random

from rpa_tt.gestures import build_gesture_plan


def test_gesture_moves_up_with_nonuniform_segments() -> None:
    plan = build_gesture_plan(
        1080,
        2400,
        area_top=0.1,
        area_bottom=0.84,
        duration_min=0.18,
        duration_max=0.95,
        context="video",
        rng=random.Random(7),
    )

    assert plan.points[-1][1] < plan.points[0][1]
    assert 10 <= len(plan.points) <= 18
    assert len({round(delay, 4) for delay in plan.delays}) > 1
    segment_distances = [
        abs(current[1] - previous[1])
        for previous, current in zip(plan.points, plan.points[1:])
    ]
    assert len(set(segment_distances)) > 2


def test_comment_gesture_stays_inside_comment_area() -> None:
    plan = build_gesture_plan(
        1080,
        2400,
        area_top=0.38,
        area_bottom=0.86,
        duration_min=0.28,
        duration_max=0.9,
        context="comments",
        rng=random.Random(2),
    )

    assert all(0.38 * 2400 <= y <= 0.86 * 2400 for _, y in plan.points)
