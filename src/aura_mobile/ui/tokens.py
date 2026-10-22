"""Colours, type and sizes from the "Aura Clarus Mobile" design prototype, as light/dark pairs.

The prototype is an iPhone design (SF Pro); on Windows the same sizes are set in Segoe UI. The feature accents
(``ACCENTS``) colour the small icon tiles; ``core/features.py`` names them with ``Feature.accent``.

Painted widgets read :func:`current` at paint time, so a theme switch only needs a repaint.
"""

from __future__ import annotations

from dataclasses import dataclass

from PyQt6.QtGui import QColor

PHONE_WIDTH = 390
PHONE_HEIGHT = 844
GUTTER = 16
SECTION_GAP = 28
FONT = "Segoe UI"

# type: size in px (the prototype's pt sizes)
LARGE_TITLE = 32
NAV_TITLE = 16
TITLE = 16
ROW = 15
SUB = 13
HEAD = 12
SMALL = 11
BIG = 30
CLOCK = 76

# radii
R_CARD = 14
R_LIST = 12
R_TILE = 7
R_SHEET = 14

ROW_MIN = 44
NAV_H = 44
TABBAR_H = 62
TABBAR_INSET = 16
TABBAR_BOTTOM = 12

FITNESS = "#FF6B35"
CARDIAC = "#FF2D55"
SLEEP = "#5E5CE6"
STRESS = "#FF9500"
WORKOUT = "#FF3B30"
RECOVERY = "#34C759"
BLUE = "#007AFF"
TEAL = "#5AC8FA"
FOCUS = "#FFCC00"

ACCENTS = {
    "fitness": FITNESS,
    "cardiac": CARDIAC,
    "sleep": SLEEP,
    "stress": STRESS,
    "workout": WORKOUT,
    "recovery": RECOVERY,
    "blue": BLUE,
    "teal": TEAL,
    "focus": FOCUS,
}

# the nap / night run view, the same in both themes
NIGHT_BG = "#000000"
NIGHT_FG = "#F2F2F7"
NIGHT_2 = "rgba(235,235,245,0.60)"
NIGHT_FILL = "rgba(120,120,128,0.32)"


@dataclass(frozen=True)
class Palette:
    dark: bool
    bg: str
    card: str
    card2: str
    sheet: str
    fill: str
    fill2: str
    label: str
    label2: str
    label3: str
    sep: str
    bar: str
    alert: str
    accent: str
    accent_soft: str
    switch_on: str
    switch_off: str
    seg_thumb: str
    good: str
    warn: str
    bad: str
    scrim: str
    scrim_strong: str
    grabber: str
    shadow: str

    def tint(self, name: str) -> str:
        """A feature's accent; plain blue features follow the theme's accent."""
        colour = ACCENTS.get(name, BLUE)
        return self.accent if colour == BLUE else colour


LIGHT = Palette(
    dark=False, bg="#F5F5F5", card="#FFFFFF", card2="#FFFFFF", sheet="#F2F2F7",
    fill="rgba(120,120,128,0.12)", fill2="rgba(120,120,128,0.20)",
    label="#1C1C1E", label2="rgba(60,60,67,0.60)", label3="#8E8E93", sep="rgba(60,60,67,0.18)",
    bar="rgba(250,250,250,0.94)", alert="rgba(242,242,247,0.98)",
    accent="#007AFF", accent_soft="rgba(0,122,255,0.14)",
    switch_on="#34C759", switch_off="rgba(120,120,128,0.16)", seg_thumb="#FFFFFF",
    good="#34C759", warn="#FF9500", bad="#FF3B30",
    scrim="rgba(0,0,0,0.28)", scrim_strong="rgba(0,0,0,0.42)", grabber="rgba(60,60,67,0.30)",
    shadow="rgba(0,0,0,0.16)",
)
DARK = Palette(
    dark=True, bg="#000000", card="#1C1C1E", card2="#2C2C2E", sheet="#1C1C1E",
    fill="rgba(120,120,128,0.24)", fill2="rgba(120,120,128,0.36)",
    label="#F2F2F7", label2="rgba(235,235,245,0.60)", label3="#98989D", sep="rgba(235,235,245,0.16)",
    bar="rgba(30,30,30,0.94)", alert="rgba(44,44,46,0.98)",
    accent="#0A84FF", accent_soft="rgba(10,132,255,0.22)",
    switch_on="#30D158", switch_off="rgba(120,120,128,0.32)", seg_thumb="#636366",
    good="#30D158", warn="#FF9F0A", bad="#FF453A",
    scrim="rgba(0,0,0,0.50)", scrim_strong="rgba(0,0,0,0.66)", grabber="rgba(235,235,245,0.30)",
    shadow="rgba(0,0,0,0.50)",
)

_current = LIGHT


def qcolor(value: str) -> QColor:
    """``#RRGGBB`` or ``rgba(r,g,b,0.5)`` (alpha as a fraction, as the prototype writes it)."""
    if value.startswith("rgba("):
        r, g, b, a = (x.strip() for x in value[5:-1].split(","))
        return QColor(int(r), int(g), int(b), round(float(a) * 255))
    return QColor(value)


def css(value: str) -> str:
    """The same colour for a Qt stylesheet, which wants the alpha as 0-255."""
    if value.startswith("rgba("):
        c = qcolor(value)
        return f"rgba({c.red()},{c.green()},{c.blue()},{c.alpha()})"
    return value


def palette_for(dark: bool) -> Palette:
    return DARK if dark else LIGHT


def set_current(p: Palette) -> None:
    global _current
    _current = p


def current() -> Palette:
    return _current
