"""
Tray Icon
=========

Static brand icon loader plus a compact dynamic usage renderer for the
Windows notification area.

The dynamic icon uses two horizontal gauges:
- top: rolling five-hour quota
- bottom: weekly quota

It deliberately contains no text because Windows commonly renders tray icons
at only 16-24 physical pixels. Reset countdowns remain available in the
existing hover tooltip.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

from PIL import Image, ImageColor, ImageDraw

from .formatting import elapsed_pct, field_period
from .settings import BAR_BG, BAR_FG, BAR_FG_START, BAR_FG_WARN, BAR_MARKER

__all__ = ['load_tray_icon', 'render_usage_tray_icon']

_ICON_PATH = Path(__file__).parent / 'tray-icon.png'
_ICON_SIZE = 64


def load_tray_icon() -> Image.Image:
    """Return the bundled brand tray icon as an RGBA image."""
    return Image.open(_ICON_PATH).convert('RGBA')


def _rgba(color: str) -> tuple[int, int, int, int]:
    """Parse a configured CSS-style hex color into RGBA."""
    try:
        return ImageColor.getcolor(color, 'RGBA')
    except ValueError:
        return (255, 255, 255, 255)


def _lerp_rgba(
    start: tuple[int, int, int, int],
    end: tuple[int, int, int, int],
    t: float,
) -> tuple[int, int, int, int]:
    return tuple(round(a + (b - a) * t) for a, b in zip(start, end))  # type: ignore[return-value]


def _entry_geometry(entry: dict[str, Any] | None, key: str) -> tuple[float, float | None, bool] | None:
    """Return fill ratio, elapsed-time marker ratio, and warning state."""
    if not isinstance(entry, dict) or entry.get('utilization') is None:
        return None

    try:
        pct = float(entry.get('utilization', 0) or 0)
    except (TypeError, ValueError):
        return None

    fill = max(0.0, min(1.0, pct / 100.0))
    period = field_period(key)
    marker: float | None = None
    if period:
        time_pct = elapsed_pct(entry.get('resets_at', ''), period)
        if time_pct is not None:
            marker = max(0.0, min(1.0, time_pct / 100.0))

    warn = pct >= 100 or (marker is not None and fill > marker)
    return fill, marker, warn


def _draw_bar(
    image: Image.Image,
    *,
    y0: int,
    y1: int,
    geometry: tuple[float, float | None, bool] | None,
) -> None:
    """Draw one compact usage bar."""
    draw = ImageDraw.Draw(image)
    x0, x1 = 5, _ICON_SIZE - 6
    radius = 5

    track = _rgba(BAR_BG)
    draw.rounded_rectangle((x0, y0, x1, y1), radius=radius, fill=track)

    if geometry is None:
        return

    fill, marker, warn = geometry
    inner_x0, inner_x1 = x0 + 2, x1 - 2
    inner_y0, inner_y1 = y0 + 2, y1 - 2
    width = inner_x1 - inner_x0 + 1
    fill_width = round(width * fill)

    if fill_width > 0:
        if warn:
            fill_image = Image.new('RGBA', (fill_width, inner_y1 - inner_y0 + 1), _rgba(BAR_FG_WARN))
        else:
            start = _rgba(BAR_FG_START)
            end = _rgba(BAR_FG)
            fill_image = Image.new('RGBA', (fill_width, inner_y1 - inner_y0 + 1))
            pixels = fill_image.load()
            denom = max(1, width - 1)
            for x in range(fill_width):
                color = _lerp_rgba(start, end, (x + round(width * 0.0)) / denom)
                for y in range(fill_image.height):
                    pixels[x, y] = color

        mask = Image.new('L', (width, inner_y1 - inner_y0 + 1), 0)
        mask_draw = ImageDraw.Draw(mask)
        mask_draw.rounded_rectangle(
            (0, 0, width - 1, mask.height - 1),
            radius=max(1, radius - 2),
            fill=255,
        )
        crop = mask.crop((0, 0, fill_width, mask.height))
        layer = Image.new('RGBA', image.size, (0, 0, 0, 0))
        layer.paste(fill_image, (inner_x0, inner_y0), crop)
        image.alpha_composite(layer)

    if marker is not None:
        marker_x = inner_x0 + round((width - 1) * marker)
        marker_color = _rgba(BAR_MARKER)
        draw = ImageDraw.Draw(image)
        draw.line(
            (marker_x, y0 + 1, marker_x, y1 - 1),
            fill=marker_color,
            width=2,
        )


def render_usage_tray_icon(data: dict[str, Any]) -> Image.Image:
    """Render five-hour and weekly usage into a tray-sized RGBA image.

    Falls back to the bundled brand icon when neither quota window is present
    (startup, auth errors, or a response without usage windows).
    """
    five = _entry_geometry(data.get('five_hour'), 'five_hour')
    weekly = _entry_geometry(data.get('seven_day'), 'seven_day')

    if five is None and weekly is None:
        return load_tray_icon()

    image = Image.new('RGBA', (_ICON_SIZE, _ICON_SIZE), (0, 0, 0, 0))
    _draw_bar(image, y0=7, y1=27, geometry=five)
    _draw_bar(image, y0=36, y1=56, geometry=weekly)
    return image
