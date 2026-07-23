from __future__ import annotations

import math
import random
import time
from dataclasses import asdict, dataclass

import uiautomator2 as u2


@dataclass(frozen=True)
class GestureReport:
    profile: str
    duration_seconds: float
    distance_pixels: int
    point_count: int
    start: tuple[int, int]
    end: tuple[int, int]

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


@dataclass(frozen=True)
class GesturePlan:
    report: GestureReport
    points: tuple[tuple[int, int], ...]
    delays: tuple[float, ...]
    contact_pause: float


def build_gesture_plan(
    width: int,
    height: int,
    *,
    area_top: float,
    area_bottom: float,
    duration_min: float,
    duration_max: float,
    context: str,
    rng: random.Random,
    profile_override: str | None = None,
) -> GesturePlan:
    if not 0 <= area_top < area_bottom <= 1:
        raise ValueError("手势区域必须位于屏幕内")
    if duration_min <= 0 or duration_max < duration_min:
        raise ValueError("手势时长区间无效")

    profile = profile_override or _choose_profile(context, rng)
    if profile not in {"flick", "swipe", "drag"}:
        raise ValueError("未知手势类型")
    span = duration_max - duration_min
    duration_bands = {
        "flick": (duration_min, duration_min + span * 0.35),
        "swipe": (duration_min + span * 0.2, duration_min + span * 0.72),
        "drag": (duration_min + span * 0.58, duration_max),
    }
    band_min, band_max = duration_bands[profile]
    duration = rng.uniform(band_min, max(band_min, band_max))

    top = int(height * area_top)
    bottom = int(height * area_bottom)
    area_height = bottom - top
    if context == "video":
        start_y = int(rng.uniform(top + area_height * 0.76, top + area_height * 0.91))
        distance_ratio = {
            "flick": (0.56, 0.72),
            "swipe": (0.62, 0.78),
            "drag": (0.70, 0.86),
        }[profile]
    else:
        start_y = int(rng.uniform(top + area_height * 0.68, top + area_height * 0.88))
        distance_ratio = {
            "flick": (0.40, 0.58),
            "swipe": (0.48, 0.68),
            "drag": (0.58, 0.78),
        }[profile]
    distance = int(area_height * rng.uniform(*distance_ratio))
    end_y = max(top + int(area_height * 0.08), start_y - distance)

    start_x = int(width * rng.uniform(0.43, 0.67))
    horizontal_drift = int(width * rng.uniform(-0.045, 0.045))
    end_x = min(max(start_x + horizontal_drift, int(width * 0.08)), int(width * 0.92))
    curve = width * rng.uniform(-0.025, 0.025)
    point_count = rng.randint(10, 18)

    points: list[tuple[int, int]] = []
    for index in range(point_count):
        raw_t = index / (point_count - 1)
        progress = _progress(profile, raw_t)
        x = start_x + (end_x - start_x) * progress + math.sin(math.pi * raw_t) * curve
        y = start_y + (end_y - start_y) * progress
        points.append((int(round(x)), int(round(y))))

    segment_count = point_count - 1
    raw_delays = [rng.uniform(0.72, 1.28) for _ in range(segment_count)]
    raw_total = sum(raw_delays)
    delays = tuple(duration * weight / raw_total for weight in raw_delays)
    report = GestureReport(
        profile=profile,
        duration_seconds=round(sum(delays), 3),
        distance_pixels=abs(end_y - start_y),
        point_count=point_count,
        start=points[0],
        end=points[-1],
    )
    return GesturePlan(
        report=report,
        points=tuple(points),
        delays=delays,
        contact_pause=rng.uniform(0.015, 0.055),
    )


def execute_gesture(device: u2.Device, plan: GesturePlan) -> GestureReport:
    touch = device.touch
    last_x, last_y = plan.points[0]
    touch_is_down = False
    try:
        touch.down(last_x, last_y)
        touch_is_down = True
        time.sleep(plan.contact_pause)
        for (next_x, next_y), delay in zip(plan.points[1:], plan.delays, strict=True):
            touch.move(next_x, next_y)
            last_x, last_y = next_x, next_y
            time.sleep(delay)
        touch.up(last_x, last_y)
        touch_is_down = False
        return plan.report
    finally:
        if touch_is_down:
            touch.up(last_x, last_y)


def _choose_profile(context: str, rng: random.Random) -> str:
    value = rng.random()
    if context == "comments":
        if value < 0.18:
            return "flick"
        if value < 0.70:
            return "swipe"
        return "drag"
    if value < 0.42:
        return "flick"
    if value < 0.82:
        return "swipe"
    return "drag"


def _progress(profile: str, t: float) -> float:
    if profile == "flick":
        return t**1.55
    if profile == "drag":
        return 3 * t * t - 2 * t * t * t
    return 0.5 - 0.5 * math.cos(math.pi * t)
