from __future__ import annotations

import math
import threading
from collections import OrderedDict
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from PIL import Image, ImageDraw

SUPERSAMPLE = 4
PROGRESS_RING_SUPERSAMPLE = 8
FLUID_WAVE_SIZE = (170, 58)
FLUID_WAVE_FRAME_COUNT = 30
FLUID_WAVE_USAGE_BUCKETS = (0, 10, 20, 30, 40, 50, 60, 70, 79, 80, 89, 90, 100)
SPINNER_FRAME_COUNT = 24

SLATE = "#52647a"
AMBER = "#e58b00"
ORANGE = "#f4511e"


class RetainedAssetCache:
    """Cache rendered objects and retain their references for Tk image use."""

    def __init__(self) -> None:
        self._values: dict[tuple[Any, ...], Any] = {}

    def get(self, key: tuple[Any, ...], factory: Callable[[], Any]) -> Any:
        if key not in self._values:
            self._values[key] = factory()
        return self._values[key]

    def clear(self) -> None:
        self._values.clear()

    def __len__(self) -> int:
        return len(self._values)


@dataclass
class WindowVisibilityState:
    """Track explicit application hiding separately from transient Tk map events."""

    hidden_by_app: bool = False

    def hide(self) -> None:
        self.hidden_by_app = True

    def show(self) -> None:
        self.hidden_by_app = False

    def ui_active(self, root_state: str, viewable: bool, mapped: bool) -> bool:
        return not self.hidden_by_app and root_state == "normal" and bool(viewable) and bool(mapped)

    @staticmethod
    def is_root_event(event_widget: Any, root: Any) -> bool:
        return event_widget is root


class AnimationCallbackController:
    """Maintain exactly one scheduled UI animation callback."""

    def __init__(
        self,
        schedule: Callable[[int, Callable[[], None]], Any],
        cancel: Callable[[Any], None],
        on_frame: Callable[[], None],
        interval_ms: int = 33,
    ) -> None:
        self._schedule = schedule
        self._cancel = cancel
        self._on_frame = on_frame
        self.interval_ms = max(1, int(interval_ms))
        self.after_id: Any = None
        self.visible = False
        self.closed = False

    def _queue(self, delay_ms: int) -> None:
        if self.closed or not self.visible or self.after_id is not None:
            return
        self.after_id = self._schedule(max(0, int(delay_ms)), self._tick)

    def _tick(self) -> None:
        self.after_id = None
        if self.closed or not self.visible:
            return
        self._on_frame()
        self._queue(self.interval_ms)

    def resume(self) -> None:
        if self.closed:
            return
        self.visible = True
        self._queue(0)

    def pause(self) -> None:
        self.visible = False
        if self.after_id is not None:
            try:
                self._cancel(self.after_id)
            except Exception:
                pass
            finally:
                self.after_id = None

    def close(self) -> None:
        if self.closed:
            return
        try:
            self.pause()
        finally:
            self.closed = True


@dataclass
class _FluidWaveBuildJob:
    bucket: int
    next_frame: int = 0
    frames: list[Any] = field(default_factory=list)


class DeferredFluidWaveCache:
    """Build only requested wave buckets in small Tk-main-thread batches."""

    def __init__(
        self,
        schedule: Callable[[int, Callable[[], None]], Any],
        cancel: Callable[[Any], None],
        render_frame: Callable[[int, int], Any],
        convert_frame: Callable[[Any], Any],
        *,
        frame_count: int = FLUID_WAVE_FRAME_COUNT,
        batch_size: int = 2,
        max_buckets: int = 6,
        delay_ms: int = 1,
    ) -> None:
        self._schedule = schedule
        self._cancel = cancel
        self._render_frame = render_frame
        self._convert_frame = convert_frame
        self.frame_count = max(1, int(frame_count))
        self.batch_size = max(1, int(batch_size))
        self.max_buckets = max(1, int(max_buckets))
        self.delay_ms = max(0, int(delay_ms))
        self._owner_thread_id = threading.get_ident()
        self._values: OrderedDict[int, list[Any]] = OrderedDict()
        self._jobs: OrderedDict[int, _FluidWaveBuildJob] = OrderedDict()
        self.after_id: Any = None
        self.visible = True
        self.closed = False

    @property
    def cached_buckets(self) -> tuple[int, ...]:
        return tuple(self._values)

    @property
    def in_progress_buckets(self) -> tuple[int, ...]:
        return tuple(self._jobs)

    def get(self, value: float | int | None) -> list[Any] | None:
        bucket = quantize_usage(value)
        frames = self._values.get(bucket)
        if frames is not None:
            self._values.move_to_end(bucket)
        return frames

    def request(self, value: float | int | None) -> bool:
        if self.closed:
            return False
        bucket = quantize_usage(value)
        if bucket in self._values:
            self._values.move_to_end(bucket)
            return False
        if bucket in self._jobs:
            return False
        self._jobs[bucket] = _FluidWaveBuildJob(bucket)
        self._queue()
        return True

    def _queue(self) -> None:
        if self.closed or not self.visible or self.after_id is not None or not self._jobs:
            return
        self.after_id = self._schedule(self.delay_ms, self.process_next_batch)

    def process_next_batch(self) -> None:
        if threading.get_ident() != self._owner_thread_id:
            raise RuntimeError("Fluid wave PhotoImage conversion must run on the Tk main thread")
        self.after_id = None
        if self.closed or not self.visible:
            return

        remaining = self.batch_size
        while remaining > 0 and self._jobs:
            bucket, job = next(iter(self._jobs.items()))
            try:
                image = self._render_frame(bucket, job.next_frame)
                job.frames.append(self._convert_frame(image))
            except Exception:
                self._jobs.pop(bucket, None)
                raise
            job.next_frame += 1
            remaining -= 1
            if job.next_frame >= self.frame_count:
                self._jobs.pop(bucket, None)
                self._values[bucket] = job.frames
                self._values.move_to_end(bucket)
                while len(self._values) > self.max_buckets:
                    self._values.popitem(last=False)
        self._queue()

    def pause(self) -> None:
        self.visible = False
        if self.after_id is not None:
            try:
                self._cancel(self.after_id)
            except Exception:
                pass
            finally:
                self.after_id = None

    def resume(self) -> None:
        if self.closed:
            return
        self.visible = True
        self._queue()

    def close(self) -> None:
        if self.closed:
            return
        self.pause()
        self.closed = True
        self._jobs.clear()
        self._values.clear()


@dataclass
class FluidWaveDisplayState:
    """Keep the last real measurement separate from display interpolation."""

    target_usage: float = 0.0
    display_usage: float = 0.0
    initialized: bool = False

    def set_measurement(self, value: float | int | None) -> None:
        self.target_usage = _clamp_usage(value)
        if not self.initialized:
            self.display_usage = self.target_usage
            self.initialized = True

    def advance(self, factor: float = 0.08) -> float:
        bounded_factor = max(0.0, min(1.0, float(factor)))
        self.display_usage += (self.target_usage - self.display_usage) * bounded_factor
        if abs(self.target_usage - self.display_usage) < 0.05:
            self.display_usage = self.target_usage
        return self.display_usage

    @property
    def bucket(self) -> int:
        return quantize_usage(self.display_usage)


def _scaled(value: float) -> int:
    return round(value * SUPERSAMPLE)


def _canvas(size: tuple[int, int]) -> Image.Image:
    return Image.new("RGBA", (size[0] * SUPERSAMPLE, size[1] * SUPERSAMPLE), (0, 0, 0, 0))


def _downsample(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    resampling = getattr(getattr(Image, "Resampling", Image), "LANCZOS", Image.LANCZOS)
    return image.resize(size, resampling)


def _rgba(color: str, alpha: int = 255) -> tuple[int, int, int, int]:
    value = color.removeprefix("#")
    if len(value) == 3:
        value = "".join(character * 2 for character in value)
    return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), alpha


def _clamp_usage(value: float | int | None) -> float:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return 0.0
    return max(0.0, min(100.0, float(value)))


def quantize_usage(value: float | int | None, step: int = 10) -> int:
    usage = _clamp_usage(value)
    if int(step) != 10:
        bounded_step = max(1, min(100, int(step)))
        return max(0, min(100, int(round(usage / bounded_step) * bounded_step)))
    if usage < 79:
        candidates = FLUID_WAVE_USAGE_BUCKETS[:8]
    elif usage < 80:
        candidates = (79,)
    elif usage < 90:
        candidates = FLUID_WAVE_USAGE_BUCKETS[9:11]
    else:
        candidates = FLUID_WAVE_USAGE_BUCKETS[11:]
    return min(candidates, key=lambda candidate: abs(candidate - usage))


def fluid_wave_amplitude(value: float | int | None, maximum: float = 22.0) -> float:
    """Map actual usage to a non-linear display amplitude.

    The anchors preserve the selected visual comparison without treating the
    animation as time-series evidence. Values come only from the caller.
    """

    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return 0.0
    usage = _clamp_usage(value)
    anchors = ((0.0, 0.65), (20.0, 2.5), (50.0, 7.0), (75.0, 14.0), (92.0, 22.0), (100.0, 22.0))
    for (left_usage, left_amplitude), (right_usage, right_amplitude) in zip(anchors, anchors[1:], strict=True):
        if usage <= right_usage:
            ratio = (usage - left_usage) / max(1.0, right_usage - left_usage)
            amplitude = left_amplitude + (right_amplitude - left_amplitude) * ratio
            return amplitude * max(0.0, maximum) / 22.0
    return max(0.0, maximum)


def fluid_wave_color(value: float | int | None) -> str:
    usage = _clamp_usage(value)
    if usage >= 90.0:
        return ORANGE
    if usage >= 80.0:
        return AMBER
    return SLATE


def render_fluid_wave_frame(
    usage: float | int | None,
    frame_index: int,
    size: tuple[int, int] = FLUID_WAVE_SIZE,
    frame_count: int = FLUID_WAVE_FRAME_COUNT,
) -> Image.Image:
    image = _canvas(size)
    draw = ImageDraw.Draw(image, "RGBA")
    width, height = image.size
    display_width, display_height = size
    color = fluid_wave_color(usage)
    amplitude = fluid_wave_amplitude(usage) * SUPERSAMPLE
    phase = math.tau * (frame_index % max(1, frame_count)) / max(1, frame_count)
    center_y = display_height * 0.52 * SUPERSAMPLE

    if _clamp_usage(usage) >= 90.0:
        pulse = (math.sin(phase) + 1.0) * 0.5
        draw.rounded_rectangle(
            (0, _scaled(3), width - 1, height - 1),
            radius=_scaled(8),
            fill=_rgba(color, round(4 + pulse * 5)),
        )

    points: list[tuple[float, float]] = []
    point_count = max(48, display_width)
    for index in range(point_count):
        ratio = index / (point_count - 1)
        x = ratio * (width - 1)
        wave = math.sin(phase + ratio * math.tau * 1.18)
        harmonic = math.sin(phase * 0.72 + ratio * math.tau * 2.36) * 0.12
        y = center_y - (wave + harmonic) * amplitude
        y = max(_scaled(4), min(height - _scaled(5), y))
        points.append((x, y))

    usage_value = _clamp_usage(usage)
    fill_alpha = 18 if usage_value < 80 else 28 if usage_value < 90 else 42
    fill_points = points + [(width - 1, height - _scaled(3)), (0, height - _scaled(3))]
    draw.polygon(fill_points, fill=_rgba(color, fill_alpha))
    draw.line(points, fill=_rgba(color), width=_scaled(1.6), joint="curve")
    return _downsample(image, size)


def render_rounded_surface(
    width: int,
    height: int,
    radius: int,
    fill: str,
    outline: str = "",
    stroke_width: int = 1,
) -> Image.Image:
    size = (max(1, int(width)), max(1, int(height)))
    image = _canvas(size)
    draw = ImageDraw.Draw(image, "RGBA")
    inset = max(0, _scaled(stroke_width) / 2)
    bounds = (inset, inset, image.width - 1 - inset, image.height - 1 - inset)
    draw.rounded_rectangle(
        bounds,
        radius=_scaled(max(0, radius)),
        fill=_rgba(fill),
        outline=_rgba(outline) if outline else None,
        width=max(1, _scaled(stroke_width)) if outline else 1,
    )
    return _downsample(image, size)


def render_step_connector(length: int, color: str, thickness: int = 2) -> Image.Image:
    height = max(4, thickness + 2)
    image = _canvas((max(1, length), height))
    draw = ImageDraw.Draw(image, "RGBA")
    y = image.height / 2
    draw.line(
        (_scaled(1), y, image.width - _scaled(1), y),
        fill=_rgba(color),
        width=max(1, _scaled(thickness)),
    )
    return _downsample(image, (max(1, length), height))


def _draw_check(draw: ImageDraw.ImageDraw, center: tuple[float, float], size: float, color: str, width: float) -> None:
    x, y = center
    draw.line(
        (
            x - size * 0.44,
            y,
            x - size * 0.13,
            y + size * 0.30,
            x + size * 0.48,
            y - size * 0.38,
        ),
        fill=_rgba(color),
        width=max(1, round(width)),
        joint="curve",
    )


def render_step_node(style: str, size: int = 34, frame_index: int = 0) -> Image.Image:
    image = _canvas((size, size))
    draw = ImageDraw.Draw(image, "RGBA")
    margin = _scaled(1.5)
    bounds = (margin, margin, image.width - 1 - margin, image.height - 1 - margin)
    if style in {"active", "done-black", "done-check", "moving"}:
        draw.ellipse(bounds, fill=_rgba("#050505"), outline=_rgba("#050505"), width=_scaled(1))
        if style == "done-check":
            _draw_check(
                draw,
                (image.width / 2, image.height / 2),
                _scaled(9),
                "#ffffff",
                _scaled(2),
            )
    else:
        draw.ellipse(bounds, fill=_rgba("#ffffff"), outline=_rgba("#d7dce0"), width=_scaled(1))
        if style == "loading":
            spinner_bounds = (
                _scaled(7),
                _scaled(7),
                image.width - _scaled(7),
                image.height - _scaled(7),
            )
            draw.arc(spinner_bounds, 0, 359, fill=_rgba("#d9e7ff"), width=_scaled(2))
            start = (frame_index % SPINNER_FRAME_COUNT) * 360 / SPINNER_FRAME_COUNT - 90
            draw.arc(spinner_bounds, start, start + 115, fill=_rgba("#1677ff"), width=_scaled(2))
    return _downsample(image, (size, size))


def render_hardware_icon(component: str, size: int = 30, color: str = "#666666") -> Image.Image:
    image = _canvas((size, size))
    draw = ImageDraw.Draw(image, "RGBA")
    center = image.width / 2
    line_width = _scaled(1.7)
    thin_width = _scaled(1.15)

    if component == "cpu":
        outer = (_scaled(8), _scaled(8), image.width - _scaled(8), image.height - _scaled(8))
        inner = (_scaled(12), _scaled(12), image.width - _scaled(12), image.height - _scaled(12))
        draw.rounded_rectangle(outer, radius=_scaled(1.5), outline=_rgba(color), width=line_width)
        draw.rectangle(inner, outline=_rgba(color), width=thin_width)
        for offset in (9, 15, 21):
            draw.line((_scaled(offset), _scaled(4), _scaled(offset), _scaled(8)), fill=_rgba(color), width=thin_width)
            draw.line((_scaled(offset), image.height - _scaled(8), _scaled(offset), image.height - _scaled(4)), fill=_rgba(color), width=thin_width)
            draw.line((_scaled(4), _scaled(offset), _scaled(8), _scaled(offset)), fill=_rgba(color), width=thin_width)
            draw.line((image.width - _scaled(8), _scaled(offset), image.width - _scaled(4), _scaled(offset)), fill=_rgba(color), width=thin_width)
    elif component == "gpu":
        body = (_scaled(3), _scaled(7), image.width - _scaled(4), image.height - _scaled(7))
        draw.rounded_rectangle(body, radius=_scaled(1.5), outline=_rgba(color), width=line_width)
        fan = (_scaled(9), _scaled(9), image.width - _scaled(9), image.height - _scaled(9))
        draw.ellipse(fan, outline=_rgba(color), width=thin_width)
        draw.ellipse((center - _scaled(1.5), center - _scaled(1.5), center + _scaled(1.5), center + _scaled(1.5)), fill=_rgba(color))
        draw.line((image.width - _scaled(4), _scaled(11), image.width - _scaled(1), _scaled(11)), fill=_rgba(color), width=line_width)
        draw.line((image.width - _scaled(4), _scaled(19), image.width - _scaled(1), _scaled(19)), fill=_rgba(color), width=line_width)
    elif component == "ram":
        body = (_scaled(3), _scaled(8), image.width - _scaled(3), image.height - _scaled(8))
        draw.rounded_rectangle(body, radius=_scaled(1.5), outline=_rgba(color), width=line_width)
        for offset in (7, 12, 17, 22):
            draw.rounded_rectangle(
                (_scaled(offset), _scaled(12), _scaled(offset + 3), _scaled(18)),
                radius=_scaled(0.6),
                fill=_rgba(color),
            )
        for offset in (8, 14, 20):
            draw.line((_scaled(offset), image.height - _scaled(8), _scaled(offset), image.height - _scaled(4)), fill=_rgba(color), width=thin_width)
    else:
        top = (_scaled(5), _scaled(5), image.width - _scaled(5), _scaled(13))
        draw.ellipse(top, outline=_rgba(color), width=line_width)
        draw.line((_scaled(5), _scaled(9), _scaled(5), image.height - _scaled(7)), fill=_rgba(color), width=line_width)
        draw.line((image.width - _scaled(5), _scaled(9), image.width - _scaled(5), image.height - _scaled(7)), fill=_rgba(color), width=line_width)
        bottom = (_scaled(5), image.height - _scaled(12), image.width - _scaled(5), image.height - _scaled(4))
        draw.arc(bottom, 0, 180, fill=_rgba(color), width=line_width)
    return _downsample(image, (size, size))


def render_home_hardware_icon(component: str, size: int = 30, color: str = "#666666") -> Image.Image:
    """Render a simple, consistently weighted icon family for first-page cards."""
    image = _canvas((size, size))
    draw = ImageDraw.Draw(image, "RGBA")
    stroke = _scaled(1.65)
    detail = _scaled(1.2)
    ink = _rgba(color)

    if component == "cpu":
        body = (_scaled(8.5), _scaled(8.5), image.width - _scaled(8.5), image.height - _scaled(8.5))
        draw.rounded_rectangle(body, radius=_scaled(2), outline=ink, width=stroke)
        core = (_scaled(12), _scaled(12), image.width - _scaled(12), image.height - _scaled(12))
        draw.rounded_rectangle(core, radius=_scaled(1), outline=ink, width=detail)
        for offset in (11, 15, 19):
            point = _scaled(offset)
            draw.line((point, _scaled(5), point, _scaled(8.5)), fill=ink, width=detail)
            draw.line((point, image.height - _scaled(8.5), point, image.height - _scaled(5)), fill=ink, width=detail)
            draw.line((_scaled(5), point, _scaled(8.5), point), fill=ink, width=detail)
            draw.line((image.width - _scaled(8.5), point, image.width - _scaled(5), point), fill=ink, width=detail)
    elif component == "gpu":
        body = (_scaled(5), _scaled(7.5), image.width - _scaled(5.5), image.height - _scaled(7.5))
        draw.rounded_rectangle(body, radius=_scaled(2), outline=ink, width=stroke)
        for center_x in (10.5, 18.5):
            radius = _scaled(3.2)
            center = _scaled(center_x)
            draw.ellipse((center - radius, _scaled(11.3), center + radius, _scaled(17.7)), outline=ink, width=detail)
            draw.ellipse((center - _scaled(0.8), _scaled(13.7), center + _scaled(0.8), _scaled(15.3)), fill=ink)
        draw.line((_scaled(7), image.height - _scaled(9), _scaled(17), image.height - _scaled(9)), fill=ink, width=detail)
        draw.line((image.width - _scaled(5.5), _scaled(10), image.width - _scaled(4), _scaled(10)), fill=ink, width=detail)
        draw.line((image.width - _scaled(5.5), _scaled(19), image.width - _scaled(4), _scaled(19)), fill=ink, width=detail)
    elif component == "ram":
        body = (_scaled(4.5), _scaled(8), image.width - _scaled(4.5), image.height - _scaled(9))
        draw.rounded_rectangle(body, radius=_scaled(2), outline=ink, width=stroke)
        for offset in (7, 12, 17, 22):
            draw.rounded_rectangle(
                (_scaled(offset), _scaled(11), _scaled(offset + 3), _scaled(17)),
                radius=_scaled(0.8),
                fill=ink,
            )
        for offset in (8, 13, 17, 22):
            point = _scaled(offset)
            draw.line((point, image.height - _scaled(9), point, image.height - _scaled(5.5)), fill=ink, width=detail)
        draw.line((_scaled(14), image.height - _scaled(9), _scaled(14), image.height - _scaled(7)), fill=_rgba("#ffffff"), width=_scaled(1.4))
    else:
        left, right = _scaled(6), image.width - _scaled(6)
        top, bottom = _scaled(5), image.height - _scaled(5)
        ellipse_height = _scaled(6)
        draw.ellipse((left, top, right, top + ellipse_height), outline=ink, width=stroke)
        draw.line((left, top + ellipse_height / 2, left, bottom - ellipse_height / 2), fill=ink, width=stroke)
        draw.line((right, top + ellipse_height / 2, right, bottom - ellipse_height / 2), fill=ink, width=stroke)
        draw.arc((left, bottom - ellipse_height, right, bottom), 0, 180, fill=ink, width=stroke)
        draw.arc((left, _scaled(10), right, _scaled(16)), 0, 180, fill=ink, width=detail)
        draw.arc((left, _scaled(15), right, _scaled(21)), 0, 180, fill=ink, width=detail)
    return _downsample(image, (size, size))


def home_hardware_icon_cache_key(component: str, color: str, size: int = 30) -> tuple[str, str, str, int]:
    return "home-hardware", component, color, max(1, int(size))


def render_status_icon(tone: str, size: int = 16, color: str = "#92989d", frame_index: int = 0) -> Image.Image:
    image = _canvas((size, size))
    draw = ImageDraw.Draw(image, "RGBA")
    margin = _scaled(1.5)
    bounds = (margin, margin, image.width - 1 - margin, image.height - 1 - margin)
    line_width = _scaled(1.6)

    if tone == "running":
        draw.arc(bounds, 0, 359, fill=_rgba("#d9e7ff"), width=line_width)
        start = (frame_index % SPINNER_FRAME_COUNT) * 360 / SPINNER_FRAME_COUNT - 90
        draw.arc(bounds, start, start + 115, fill=_rgba(color), width=line_width)
    elif tone == "success":
        draw.ellipse(bounds, fill=_rgba(color), outline=_rgba(color), width=_scaled(1))
        _draw_check(draw, (image.width / 2, image.height / 2), _scaled(size * 0.28), "#ffffff", _scaled(1.5))
    elif tone == "warning":
        draw.polygon(
            ((image.width / 2, margin), (image.width - margin, image.height - margin), (margin, image.height - margin)),
            fill=_rgba("#ffffff"),
            outline=_rgba(color),
        )
        draw.line((image.width / 2, _scaled(5), image.width / 2, _scaled(size - 6)), fill=_rgba(color), width=line_width)
        draw.ellipse((image.width / 2 - _scaled(0.9), image.height - _scaled(4), image.width / 2 + _scaled(0.9), image.height - _scaled(2.2)), fill=_rgba(color))
    elif tone == "error":
        draw.ellipse(bounds, fill=_rgba("#ffffff"), outline=_rgba(color), width=line_width)
        draw.line((_scaled(5), _scaled(5), image.width - _scaled(5), image.height - _scaled(5)), fill=_rgba(color), width=line_width)
        draw.line((image.width - _scaled(5), _scaled(5), _scaled(5), image.height - _scaled(5)), fill=_rgba(color), width=line_width)
    else:
        draw.ellipse(bounds, fill=_rgba("#ffffff"), outline=_rgba(color), width=_scaled(1.2))
        draw.line((image.width / 2, _scaled(4.5), image.width / 2, image.height / 2), fill=_rgba(color), width=_scaled(1.2))
        draw.line((image.width / 2, image.height / 2, image.width - _scaled(4.5), image.height / 2 + _scaled(2)), fill=_rgba(color), width=_scaled(1.2))
    return _downsample(image, (size, size))


def render_status_dot(size: int, color: str) -> Image.Image:
    image = _canvas((size, size))
    draw = ImageDraw.Draw(image, "RGBA")
    draw.ellipse((0, 0, image.width - 1, image.height - 1), fill=_rgba(color))
    return _downsample(image, (size, size))


def render_summary_icon(size: int = 42, color: str = "#333333") -> Image.Image:
    image = _canvas((size, size))
    draw = ImageDraw.Draw(image, "RGBA")
    draw.ellipse((0, 0, image.width - 1, image.height - 1), fill=_rgba("#f4f4f4"))
    left, top, right, bottom = _scaled(11), _scaled(12), image.width - _scaled(11), image.height - _scaled(13)
    draw.rounded_rectangle((left, top, right, bottom), radius=_scaled(2), outline=_rgba(color), width=_scaled(1.5))
    draw.line((left + _scaled(4), bottom, left + _scaled(1), bottom + _scaled(5), left + _scaled(8), bottom), fill=_rgba(color), width=_scaled(1.5), joint="curve")
    for offset in (-4, 0, 4):
        x = image.width / 2 + _scaled(offset)
        draw.ellipse((x - _scaled(0.8), image.height / 2 - _scaled(0.8), x + _scaled(0.8), image.height / 2 + _scaled(0.8)), fill=_rgba(color))
    return _downsample(image, (size, size))


def render_result_icon(size: int = 42, color: str = "#333333") -> Image.Image:
    """Render the result-page marker with the same supersampled line treatment."""

    image = _canvas((size, size))
    draw = ImageDraw.Draw(image, "RGBA")
    draw.ellipse((0, 0, image.width - 1, image.height - 1), fill=_rgba("#f4f4f4"))
    stroke = _scaled(1.6)
    left = _scaled(size * 0.34)
    top = _scaled(size * 0.26)
    right = _scaled(size * 0.67)
    bottom = _scaled(size * 0.70)
    draw.rounded_rectangle((left, top, right, bottom), radius=_scaled(1.8), outline=_rgba(color), width=stroke)
    draw.line((left + _scaled(3), top + _scaled(6), right - _scaled(3), top + _scaled(6)), fill=_rgba(color), width=stroke)
    draw.line((left + _scaled(3), top + _scaled(11), right - _scaled(6), top + _scaled(11)), fill=_rgba(color), width=stroke)
    draw.line((left + _scaled(3), top + _scaled(16), right - _scaled(8), top + _scaled(16)), fill=_rgba(color), width=stroke)
    return _downsample(image, (size, size))


def render_finding_icon(kind: str, size: int = 24, color: str = "#ef5350") -> Image.Image:
    """Render compact result-finding icons without Canvas pixel stair-stepping."""

    image = _canvas((size, size))
    draw = ImageDraw.Draw(image, "RGBA")
    ink = _rgba(color)
    stroke = _scaled(1.6)
    if kind == "temp":
        center_x = image.width / 2
        draw.rounded_rectangle(
            (center_x - _scaled(2.6), _scaled(4), center_x + _scaled(2.6), image.height - _scaled(7)),
            radius=_scaled(2.6),
            outline=ink,
            width=stroke,
        )
        draw.ellipse(
            (center_x - _scaled(5), image.height - _scaled(10), center_x + _scaled(5), image.height - _scaled(1.5)),
            fill=_rgba("#ffffff"),
            outline=ink,
            width=stroke,
        )
        draw.line((center_x, _scaled(8), center_x, image.height - _scaled(6)), fill=ink, width=stroke)
    elif kind == "fan":
        center = image.width / 2
        for angle in (0, 120, 240):
            radians = math.radians(angle)
            blade_x = center + math.cos(radians) * _scaled(4.5)
            blade_y = center + math.sin(radians) * _scaled(4.5)
            draw.ellipse(
                (
                    blade_x - _scaled(3.2),
                    blade_y - _scaled(5.2),
                    blade_x + _scaled(3.2),
                    blade_y + _scaled(1.8),
                ),
                outline=ink,
                width=stroke,
            )
        draw.ellipse((center - _scaled(1.8), center - _scaled(1.8), center + _scaled(1.8), center + _scaled(1.8)), fill=ink)
    else:
        margin = _scaled(2)
        points = (
            (image.width / 2, margin),
            (image.width - margin, image.height - margin),
            (margin, image.height - margin),
        )
        draw.polygon(points, fill=_rgba("#ffffff"))
        draw.line((*points, points[0]), fill=ink, width=stroke, joint="curve")
        draw.line((image.width / 2, _scaled(7), image.width / 2, image.height - _scaled(7)), fill=ink, width=stroke)
        draw.ellipse(
            (image.width / 2 - _scaled(1), image.height - _scaled(5), image.width / 2 + _scaled(1), image.height - _scaled(3)),
            fill=ink,
        )
    return _downsample(image, (size, size))


def render_progress_ring(progress: int, size: int = 88, ring_width: int = 6) -> Image.Image:
    value = max(0, min(100, int(progress)))
    scale = PROGRESS_RING_SUPERSAMPLE
    image = Image.new("RGBA", (size * scale, size * scale), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image, "RGBA")
    width = max(1, int(round(ring_width * scale)))
    margin = width / 2 + 2.5 * scale
    bounds = (margin, margin, image.width - margin, image.height - margin)
    draw.ellipse(bounds, outline=_rgba("#e8e8e8"), width=width)
    if value == 100:
        draw.ellipse(bounds, outline=_rgba("#111111"), width=width)
    elif value:
        end = -90 + 360 * value / 100
        draw.arc(bounds, -90, end, fill=_rgba("#111111"), width=width)
    return _downsample(image, (size, size))


def render_number_badge(size: int = 26, fill: str = "#fff0ef") -> Image.Image:
    target_size = max(1, int(size))
    image = _canvas((target_size, target_size))
    draw = ImageDraw.Draw(image, "RGBA")
    inset = _scaled(0.5)
    draw.ellipse(
        (inset, inset, image.width - 1 - inset, image.height - 1 - inset),
        fill=_rgba(fill),
    )
    return _downsample(image, (target_size, target_size))


def build_fluid_wave_cache(
    factory: Callable[[Image.Image], object],
    usage_buckets: tuple[int, ...] = FLUID_WAVE_USAGE_BUCKETS,
) -> dict[int, list[object]]:
    return {
        usage: [factory(render_fluid_wave_frame(usage, frame)) for frame in range(FLUID_WAVE_FRAME_COUNT)]
        for usage in usage_buckets
    }
