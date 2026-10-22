"""The prototype's line icons (24×24, 1.6 stroke), drawn with QtSvg in any colour.

``pixmap(name, colour, size)`` is cached per (name, colour, size, device ratio).
"""

from __future__ import annotations

from functools import lru_cache

from PyQt6.QtCore import QByteArray, QRectF, Qt
from PyQt6.QtGui import QColor, QIcon, QPainter, QPixmap
from PyQt6.QtSvg import QSvgRenderer

PATHS: dict[str, str] = {
    "home": '<path d="M4 11 12 4.5 20 11v8.5a1 1 0 0 1-1 1h-4.5V15h-5v5.5H5a1 1 0 0 1-1-1Z"/>',
    "heart": '<path d="M12 20s-7.5-4.6-7.5-10A4.3 4.3 0 0 1 12 7.3 4.3 4.3 0 0 1 19.5 10c0 5.4-7.5 10-7.5 10Z"/>',
    "sleep": '<path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5Z"/>',
    "me": '<circle cx="12" cy="12" r="9.2"/><circle cx="12" cy="10" r="3.2"/>'
          '<path d="M6.3 18.6c1.2-2.3 3.2-3.5 5.7-3.5s4.5 1.2 5.7 3.5"/>',
    "sun": '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4'
           'M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4"/>',
    "move": '<circle cx="15" cy="4.5" r="1.8"/><path d="M13.5 8 11.5 13M11.5 13l-3 1.5-2.5-1M11.5 13l2.5 3-1 4.5'
            'M13.3 8.6 9.5 9.5 8 12M13.3 8.6l2.2 2.6 3 .3"/>',
    "recover": '<path d="M4.5 19.5C4 11 9.5 5 20 4.5c.5 10.5-5.5 16-14 15.5Z"/><path d="M4.5 19.5 13 11"/>',
    "evening": '<path d="M3 17h18M7.5 17a4.5 4.5 0 0 1 9 0M12 3.5v5M9.5 6.5 12 9l2.5-2.5M4.6 11.6l1.3 1.3'
               'M19.4 11.6l-1.3 1.3M6 20.5h12"/>',
    "info": '<circle cx="12" cy="12" r="9"/><path d="M12 11v5.5M12 7.7v.1"/>',
    "chevron": '<path d="M9.5 5.5 16 12l-6.5 6.5"/>',
    "back": '<path d="M14.5 5.5 8 12l6.5 6.5"/>',
    "down": '<path d="M5.5 9.5 12 16l6.5-6.5"/>',
    "plus": '<path d="M12 5v14M5 12h14"/>',
    "minus": '<path d="M5 12h14"/>',
    "check": '<path d="M5 12.5 9.5 17 19 7.5"/>',
    "circle": '<circle cx="12" cy="12" r="8.5"/>',
    "clock": '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/>',
    "bell": '<path d="M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15Z"/><path d="M10 21h4"/>',
    "drop": '<path d="M12 3.5s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11Z"/>',
    "bolt": '<path d="M13 2.5 5 13.5h6l-1 8 8-11h-6Z"/>',
    "walk": '<circle cx="13" cy="4.5" r="1.8"/><path d="M12.2 8 11 14M11 14l-2.5 6.5M11 14l2.5 3 .5 3.5'
            'M12 9.2 8.8 11.5M12 9.2l3.4 2"/>',
    "xmark": '<path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/>',
    "people": '<circle cx="9" cy="8.5" r="3.2"/><path d="M3 19.5c.6-3.3 3-5.2 6-5.2s5.4 1.9 6 5.2"/>'
              '<path d="M15.5 5.6a3.2 3.2 0 0 1 0 6M17.5 14.6c2 .6 3.2 2.3 3.5 4.9"/>',
    "settings": '<circle cx="12" cy="12" r="2.8"/><circle cx="12" cy="12" r="6.4"/><path d="M12 2.8v2.8M12 18.4v2.8'
                'M2.8 12h2.8M18.4 12h2.8M5.5 5.5l2 2M16.5 16.5l2 2M5.5 18.5l2-2M16.5 7.5l2-2"/>',
    "chart": '<path d="M3.5 20.5h17M6.5 17v-5M12 17V6.5M17.5 17v-8"/>',
    "search": '<circle cx="10.5" cy="10.5" r="6"/><path d="m15 15 5 5"/>',
    "lock": '<rect x="5" y="10.5" width="14" height="10" rx="2.5"/><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5"/>',
    "wind": '<path d="M3 9h11a3 3 0 1 0-3-3M3 15h15a3 3 0 1 1-3 3M3 12h7"/>',
    "note": '<path d="M6 3.5h9l4 4v13H6Z"/><path d="M15 3.5v4h4M9 12h7M9 15.5h7"/>',
    "pencil": '<path d="M4.5 19.5 5.5 15 15.5 5a2 2 0 0 1 3 3l-10 10Z"/><path d="M13.5 7l3 3"/>',
    "body": '<circle cx="12" cy="4.8" r="2"/><path d="M5 9h14M12 9v5M12 14l-3 7M12 14l3 7"/>',
    "star": '<path d="m12 3.5 2.6 5.4 5.9.8-4.3 4.1 1 5.9L12 17l-5.2 2.7 1-5.9-4.3-4.1 5.9-.8Z"/>',
    "cal": '<rect x="3.5" y="5" width="17" height="15.5" rx="3"/><path d="M3.5 10h17M8 3v4M16 3v4"/>',
    "calcheck": '<rect x="3.5" y="5" width="17" height="15.5" rx="3"/>'
                '<path d="M3.5 10h17M8 3v4M16 3v4M9 15l2 2 4-4"/>',
    "shield": '<path d="M12 3.5 5 6v5.5c0 4.5 3 7.7 7 9 4-1.3 7-4.5 7-9V6Z"/>',
    "band": '<rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M11 18.5h2"/>',
    "signal": '<path d="M4 18v-2M9 18v-5M14 18v-8M19 18V7"/>',
    "sunrise": '<path d="M3 17h18M7.5 17a4.5 4.5 0 0 1 9 0M12 9.5v-6M9.5 6 12 3.5 14.5 6M4.6 11.6l1.3 1.3'
               'M19.4 11.6l-1.3 1.3M6 20.5h12"/>',
    "bubble": '<path d="M4 6.5A2.5 2.5 0 0 1 6.5 4h11A2.5 2.5 0 0 1 20 6.5v7a2.5 2.5 0 0 1-2.5 2.5H11l-4.5 4v-4'
              'A2.5 2.5 0 0 1 4 13.5Z"/><path d="M9 8.5h6M9 11.5h4"/>',
    "alarm": '<circle cx="12" cy="13" r="7.5"/><path d="M12 9v4.5l2.5 1.5M4 5.5 6.5 3M20 5.5 17.5 3"/>',
    "flame": '<path d="M12 21c-3.6 0-6-2.4-6-5.6 0-3.4 2.6-5.4 3.6-8.9 2.9 1.6 3.4 4.4 3.1 6 1-.4 1.9-1.5 2.2-2.8 '
             '1.8 1.6 3.1 3.6 3.1 5.7C18 18.6 15.6 21 12 21Z"/>',
    "bulb": '<path d="M9 17.5h6M10 20.5h4M12 3.5a6 6 0 0 0-3.6 10.8c.6.5 1 1.3 1 2.1V17.5h5.2v-1.1c0-.8.4-1.6 '
            '1-2.1A6 6 0 0 0 12 3.5Z"/>',
    "play": '<circle cx="12" cy="12" r="9"/><path d="M10 8.5v7l5.5-3.5Z" fill="currentColor"/>',
    "pause": '<circle cx="12" cy="12" r="9"/><path d="M10 8.5v7M14 8.5v7"/>',
    "inbox": '<path d="M3.5 13.5 6 5.5h12l2.5 8v5.5a1.5 1.5 0 0 1-1.5 1.5H5a1.5 1.5 0 0 1-1.5-1.5Z"/>'
             '<path d="M3.5 13.5H9l1 2h4l1-2h5.5"/>',
    "cup": '<path d="M4.5 9h12v5.5a5 5 0 0 1-5 5h-2a5 5 0 0 1-5-5Z"/>'
           '<path d="M16.5 10.5h1a2.5 2.5 0 0 1 0 5h-1.3M8.5 3.5v2.5M12.5 3.5v2.5"/>',
    "flask": '<path d="M9.5 3.5h5M10.5 3.5v6L5 19a1.3 1.3 0 0 0 1.1 2h11.8A1.3 1.3 0 0 0 19 19l-5.5-9.5v-6"/>'
             '<path d="M7.5 15h9"/>',
    "dumbbell": '<path d="M6.5 7.5v9M4 9.5v5M17.5 7.5v9M20 9.5v5M6.5 12h11"/>',
    "brain": '<path d="M9 4.5a3 3 0 0 0-3 3 3 3 0 0 0-1.5 5.3A3 3 0 0 0 7 17.5a2.5 2.5 0 0 0 5 1V6.5a2 2 0 0 0-3-2Z"/>'
             '<path d="M15 4.5a3 3 0 0 1 3 3 3 3 0 0 1 1.5 5.3 3 3 0 0 1-2.5 4.7 2.5 2.5 0 0 1-5 1"/>',
    "timer": '<circle cx="12" cy="13.5" r="7.5"/><path d="M12 9.5v4l2.5 1.5M10 2.5h4"/>',
    "flow": '<path d="M3.5 8c3 0 3-3 6-3s3 3 6 3 3-3 5-3M3.5 14c3 0 3-3 6-3s3 3 6 3 3-3 5-3M3.5 20c3 0 3-3 6-3s3 '
            '3 6 3 3-3 5-3"/>',
    "door": '<path d="M5 20.5h14M7 20.5V4.5a1 1 0 0 1 1-1h8a1 1 0 0 1 1 1v16"/><path d="M13.5 12h.1"/>',
    "sync": '<path d="M19.5 8.5A8 8 0 0 0 5 7M4.5 15.5A8 8 0 0 0 19 17"/><path d="M19.5 4v4.5H15M4.5 20v-4.5H9"/>',
    "out": '<path d="M14 4.5h4.5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H14M10 16l4-4-4-4M14 12H4"/>',
    "trash": '<path d="M4.5 6.5h15M9.5 6.5V4h5v2.5M6.5 6.5l1 14h9l1-14"/>',
    "export": '<path d="M12 3.5v11M8 7.5l4-4 4 4M5 13.5v6h14v-6"/>',
}


def _svg(name: str, colour: QColor, stroke: float) -> bytes:
    hex_ = colour.name()
    body = PATHS.get(name, PATHS["circle"]).replace("currentColor", hex_)
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="{hex_}" '
        f'stroke-opacity="{colour.alphaF():.3f}" stroke-width="{stroke}" stroke-linecap="round" '
        f'stroke-linejoin="round">{body}</svg>'
    ).encode()


@lru_cache(maxsize=512)
def _cached(name: str, rgba: int, size: int, ratio: float, stroke: float) -> QPixmap:
    colour = QColor.fromRgba(rgba)
    px = QPixmap(int(size * ratio), int(size * ratio))
    px.fill(Qt.GlobalColor.transparent)
    renderer = QSvgRenderer(QByteArray(_svg(name, colour, stroke)))
    p = QPainter(px)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    renderer.render(p, QRectF(0, 0, size * ratio, size * ratio))
    p.end()
    px.setDevicePixelRatio(ratio)
    return px


def pixmap(name: str, colour: QColor | str, size: int = 20, *, stroke: float = 1.8, ratio: float = 2.0) -> QPixmap:
    c = colour if isinstance(colour, QColor) else QColor(colour)
    return _cached(name, c.rgba(), size, ratio, stroke)


def icon(name: str, colour: QColor | str, size: int = 20, *, stroke: float = 1.8) -> QIcon:
    return QIcon(pixmap(name, colour, size, stroke=stroke))
