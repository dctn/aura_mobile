"""The whole-app stylesheet, built from a :class:`~aura_mobile.ui.tokens.Palette` (the "Aura Clarus Mobile" look).

Widgets get their look from ``objectName`` and the ``class`` property, never from their own ``setStyleSheet``.
Colours that are data (a feature's accent) go through a ``tint`` property (``[tint="sleep"]``) or are painted.
"""

from __future__ import annotations

from aura_mobile.ui import tokens as t
from aura_mobile.ui.tokens import Palette


def _tints(p: Palette) -> str:
    """``[tint=name]`` rules for text and buttons filled with a feature's accent."""
    out = []
    for name in t.ACCENTS:
        c = p.tint(name)
        out.append(f'QLabel[tint="{name}"] {{ color: {c}; }}')
        out.append(f'QPushButton[class="primary"][tint="{name}"] {{ background: {c}; color: white; }}')
        out.append(f'QPushButton[class="link"][tint="{name}"] {{ color: {c}; }}')
    return "\n".join(out)


def stylesheet(p: Palette) -> str:
    c = {k: t.css(v) for k, v in vars(p).items() if isinstance(v, str)}
    return f"""
* {{ font-family: "{t.FONT}"; font-size: {t.ROW}px; color: {c['label']}; }}
QWidget#phone, QWidget#page, QStackedWidget, QScrollArea, QScrollArea > QWidget > QWidget {{ background: {c['bg']}; }}
QWidget#sheetbody, QWidget#sheetbody > QWidget > QWidget, QWidget#sheetpage {{ background: {c['sheet']}; }}
QScrollArea {{ border: none; }}
QToolTip {{ background: {c['card']}; color: {c['label']}; border: 1px solid {c['sep']}; padding: 4px; }}
QDialog {{ background: {c['bg']}; }}

/* type */
QLabel[class="largetitle"] {{ font-size: {t.LARGE_TITLE}px; font-weight: 700; }}
QLabel[class="subtitle"] {{ font-size: {t.SUB}px; color: {c['label2']}; }}
QLabel[class="navtitle"] {{ font-size: {t.NAV_TITLE}px; font-weight: 600; }}
QLabel[class="head"] {{ font-size: {t.HEAD}px; color: {c['label2']}; }}
QLabel[class="foot"] {{ font-size: {t.HEAD}px; color: {c['label2']}; }}
QLabel[class="rowtitle"] {{ font-size: {t.ROW}px; }}
QLabel[class="rowtitle-link"] {{ font-size: {t.ROW}px; color: {c['accent']}; }}
QLabel[class="rowtitle-danger"] {{ font-size: {t.ROW}px; color: {c['bad']}; }}
QLabel[class="rowtitle-quiet"] {{ font-size: {t.ROW}px; color: {c['label2']}; }}
QLabel[class="rowtitle-strong"] {{ font-size: {t.ROW}px; font-weight: 600; }}
QLabel[class="rowsub"] {{ font-size: {t.SUB}px; color: {c['label2']}; }}
QLabel[class="rowvalue"] {{ font-size: {t.ROW}px; color: {c['label2']}; }}
QLabel[class="big"] {{ font-size: {t.BIG}px; font-weight: 700; }}
QLabel[class="bigunit"] {{ font-size: {t.SUB}px; font-weight: 600; color: {c['label2']}; padding-bottom: 5px; }}
QLabel[class="ccname"] {{ font-size: {t.SUB}px; font-weight: 600; color: {c['accent']}; }}
QLabel[class="ccwhen"], QLabel[class="ccnote"] {{ font-size: {t.SUB}px; color: {c['label2']}; }}
QLabel[class="coachwhen"] {{ font-size: {t.SUB}px; font-weight: 600; color: {c['label2']}; }}
QLabel[class="coachsay"] {{ font-size: 19px; font-weight: 600; }}
QLabel[class="coachquiet"] {{ font-size: {t.TITLE}px; color: {c['label2']}; }}
QLabel[class="coachwhy"] {{ font-size: {t.SUB}px; color: {c['label2']}; }}
QLabel[class="modename"] {{ font-size: {t.ROW}px; font-weight: 600; }}
QLabel[class="modestatus"] {{ font-size: {t.SUB}px; color: {c['label2']}; }}
QLabel[class="stepval"] {{ font-size: {t.ROW}px; font-weight: 600; }}
QLabel[class="title"] {{ font-size: {t.TITLE}px; font-weight: 600; }}
QLabel[class="h1"] {{ font-size: {t.LARGE_TITLE}px; font-weight: 700; }}
QLabel[class="h2"] {{ font-size: 20px; font-weight: 600; }}
QLabel[class="muted"] {{ color: {c['label2']}; font-size: {t.SUB}px; }}
QLabel[class="small"] {{ color: {c['label3']}; font-size: {t.SMALL}px; }}
QLabel[class="good"] {{ color: {c['good']}; }}
QLabel[class="warn"] {{ color: {c['warn']}; }}
QLabel[class="bad"] {{ color: {c['bad']}; }}
QLabel[class="chip"] {{ background: {c['fill']}; border-radius: 9px; padding: 2px 8px; font-size: {t.SMALL}px;
    color: {c['label2']}; }}
QLabel[class="rowsub"][tone="good"] {{ color: {c['good']}; }}
QLabel[class="rowsub"][tone="warn"] {{ color: {c['warn']}; }}
QLabel[class="rowsub"][tone="bad"] {{ color: {c['bad']}; }}
QLabel[class="hud"] {{ background: {c['card2'] if p.dark else '#FFFFFF'}; border-radius: 22px; padding: 10px 18px;
    font-weight: 600; border: 1px solid {c['sep']}; }}
QLabel[class="clock"] {{ font-size: {t.CLOCK}px; font-weight: 300; }}
QLabel[class="kicker"] {{ font-size: {t.SUB}px; font-weight: 600; color: {c['label2']}; }}
QLabel[class="sheettitle"] {{ font-size: {t.NAV_TITLE}px; font-weight: 600; }}
QLabel[class="alerttitle"] {{ font-size: {t.ROW + 1}px; font-weight: 600; }}
QLabel[class="alerttext"] {{ font-size: {t.SUB}px; }}
QLabel[class="actiontitle"] {{ font-size: {t.SUB}px; color: {c['label2']}; }}

/* surfaces */
QFrame[class="card"] {{ background: {c['card']}; border-radius: {t.R_CARD}px; border: none; }}
QFrame[class="list"] {{ background: {c['card']}; border-radius: {t.R_LIST}px; border: none; }}
QFrame[class="list2"] {{ background: {c['card2'] if p.dark else c['card']}; border-radius: {t.R_LIST}px; }}
QFrame[class="row"] {{ background: transparent; border: none; }}
QFrame[class="row"][tap="true"]:hover {{ background: {c['fill']}; }}
QFrame[class="modetile"] {{ background: {c['card']}; border-radius: {t.R_CARD}px; }}
QFrame[class="modetile"]:hover, QFrame[class="card"]:hover {{ background: {c['card2']}; }}
QFrame[class="navbar"] {{ background: {c['bg']}; border: none; }}
QFrame[class="navbar"][compact="true"] {{ background: {c['bar']}; border-bottom: 1px solid {c['sep']}; }}
QFrame[class="navbar"] > QWidget {{ background: transparent; }}
QFrame#tabbar {{ background: {c['bar']}; border-radius: {t.TABBAR_H // 2}px; border: 1px solid {c['sep']}; }}
QFrame#minibar {{ background: {c['bar']}; border-radius: 16px; border: 1px solid {c['sep']}; }}
QFrame[class="sheet"] {{ background: {c['sheet']}; border-top-left-radius: {t.R_SHEET}px;
    border-top-right-radius: {t.R_SHEET}px; }}
QFrame[class="alertbox"] {{ background: {c['alert']}; border-radius: 14px; }}
QFrame[class="actiongroup"] {{ background: {c['alert']}; border-radius: 14px; }}
QFrame[class="segbar"] {{ background: {c['fill']}; border-radius: 9px; }}
QFrame[class="stepper"] {{ background: {c['fill']}; border-radius: 9px; }}
QFrame[class="search"] {{ background: {c['fill']}; border-radius: 10px; }}
QFrame#runview {{ background: {c['bg']}; }}
QFrame#runview[night="true"] {{ background: {t.NIGHT_BG}; }}
QFrame#runview[night="true"] QLabel {{ color: {t.NIGHT_FG}; }}
QFrame#runview[night="true"] QLabel[class="kicker"] {{ color: {t.css(t.NIGHT_2)}; }}
QLabel[class="runtitle"] {{ font-size: 26px; font-weight: 700; }}
QLabel[class="runnote"] {{ font-size: {t.ROW}px; color: {c['label2']}; }}
QLabel[class="runphase"] {{ font-size: 20px; font-weight: 600; }}
QFrame#runview[night="true"] QLabel[class="runnote"] {{ color: {t.css(t.NIGHT_2)}; }}
QFrame#runview[night="true"] QPushButton[class="grey"] {{ background: {t.css(t.NIGHT_FILL)}; color: {t.NIGHT_FG}; }}
QPushButton[class="runhide"] {{ background: transparent; color: {c['accent']}; font-weight: 400;
    font-size: {t.ROW + 1}px; padding: 4px 26px 4px 6px; text-align: left; min-height: 28px; }}
QPushButton[class="runhide"]:hover {{ background: transparent; }}
QFrame#runview[night="true"] QPushButton[class="runhide"] {{ color: {t.css(t.NIGHT_2)}; }}
QPlainTextEdit[class="plain"] {{ background: transparent; border-radius: 0; padding: 6px 10px; }}

/* buttons */
QPushButton {{ background: {c['fill']}; border: none; border-radius: 17px; padding: 7px 16px; font-weight: 600;
    color: {c['accent']}; min-height: 20px; }}
QPushButton:hover {{ background: {c['fill2']}; }}
QPushButton:disabled {{ color: {c['label3']}; }}
QPushButton[class="primary"] {{ background: {c['accent']}; color: white; border-radius: 25px; min-height: 36px;
    font-size: {t.TITLE}px; }}
QPushButton[class="primary"]:disabled {{ background: {c['fill2']}; color: {c['label3']}; }}
QPushButton[class="grey"] {{ background: {c['fill']}; color: {c['accent']}; border-radius: 25px; min-height: 36px;
    font-size: {t.TITLE}px; }}
QPushButton[class="sm"] {{ border-radius: 15px; padding: 5px 14px; min-height: 20px; font-size: {t.SUB}px; }}
QPushButton[class="smprimary"] {{ background: {c['accent']}; color: white; border-radius: 15px; padding: 5px 14px;
    min-height: 20px; font-size: {t.SUB}px; }}
QPushButton[class="smprimary"]:disabled {{ background: {c['fill']}; color: {c['label3']}; }}
QPushButton[class="pill"] {{ background: {c['fill']}; color: {c['label']}; border-radius: 8px; padding: 5px 8px;
    font-weight: 400; min-height: 20px; }}
QPushButton[class="danger"] {{ background: {c['bad']}; color: white; border-radius: 25px; min-height: 36px; }}
QPushButton[class="link"] {{ background: transparent; color: {c['accent']}; padding: 4px 6px; font-weight: 400; }}
QPushButton[class="link"]:hover {{ background: transparent; color: {c['label2']}; }}
QPushButton[class="linkbold"] {{ background: transparent; color: {c['accent']}; padding: 4px 6px; }}
QPushButton[class="linkbold"]:disabled {{ color: {c['label3']}; }}
QPushButton[class="linkdanger"] {{ background: transparent; color: {c['bad']}; padding: 4px 6px; font-weight: 400; }}
QPushButton[class="round"] {{ background: {c['fill']}; border-radius: 16px; min-width: 32px; max-width: 32px;
    min-height: 32px; max-height: 32px; padding: 0; }}
QPushButton[class="avatar"] {{ background: {c['accent']}; color: white; border-radius: 16px; min-width: 32px;
    max-width: 32px; min-height: 32px; max-height: 32px; padding: 0; font-size: {t.SUB}px; }}
QPushButton[class="iconbtn"] {{ background: transparent; min-width: 28px; max-width: 28px; min-height: 28px;
    max-height: 28px; padding: 0; border-radius: 14px; }}
QPushButton[class="iconbtn"]:hover {{ background: {c['fill']}; }}
QPushButton[class="navback"] {{ background: transparent; color: {c['accent']}; font-weight: 400;
    font-size: {t.ROW + 1}px; padding: 4px 4px 4px 22px; text-align: left; min-height: 28px; }}
QPushButton[class="navback"]:hover {{ background: transparent; color: {c['label2']}; }}
QPushButton[class="tab"] {{ background: transparent; border-radius: 22px; color: {c['label3']}; font-size: {t.SMALL}px;
    font-weight: 600; padding: 28px 0 4px 0; min-height: 14px; }}
QPushButton[class="tab"]:checked {{ background: {c['fill']}; color: {c['accent']}; }}
QPushButton[class="seg"] {{ background: transparent; color: {c['label']}; border-radius: 7px; padding: 4px 6px;
    font-weight: 500; font-size: {t.SUB}px; min-height: 18px; }}
QPushButton[class="seg"]:checked {{ background: {c['seg_thumb']}; font-weight: 600; }}
QPushButton[class="stepbtn"] {{ background: transparent; border-radius: 7px; min-width: 40px; max-width: 40px;
    min-height: 28px; max-height: 28px; padding: 0; }}
QPushButton[class="stepbtn"]:hover {{ background: {c['fill2']}; }}
QPushButton[class="chip"] {{ background: {c['fill']}; color: {c['label']}; border-radius: 15px; padding: 6px 12px;
    font-weight: 500; font-size: {t.SUB}px; min-height: 20px; }}
QPushButton[class="chip"]:checked {{ background: {c['accent']}; color: white; }}
QPushButton[class="alertbtn"] {{ background: transparent; border-radius: 0; color: {c['accent']}; font-weight: 400;
    font-size: {t.ROW + 1}px; min-height: 30px; border-top: 1px solid {c['sep']}; }}
QPushButton[class="alertbtn"][bold="true"] {{ font-weight: 600; }}
QPushButton[class="alertbtn"][side="true"] {{ border-left: 1px solid {c['sep']}; }}
QPushButton[class="alertbtn"][danger="true"] {{ color: {c['bad']}; }}
QPushButton[class="alertbtn"]:disabled, QPushButton[class="alertbtn"][danger="true"]:disabled {{
    color: {c['label3']}; }}
QPushButton[class="alertbtn"]:hover {{ background: {c['fill']}; }}
QPushButton[class="actionbtn"] {{ background: transparent; border-radius: 0; color: {c['accent']}; font-weight: 400;
    font-size: 18px; min-height: 42px; border-top: 1px solid {c['sep']}; }}
QPushButton[class="actionbtn"][first="true"] {{ border-top: none; }}
QPushButton[class="actionbtn"][danger="true"] {{ color: {c['bad']}; }}
QPushButton[class="actionbtn"]:hover {{ background: {c['fill']}; }}
QPushButton[class="actioncancel"] {{ background: {c['card'] if not p.dark else c['card2']}; border-radius: 14px;
    color: {c['accent']}; font-size: 18px; min-height: 42px; }}
QPushButton[class="preset"] {{ background: {c['card']}; color: {c['label']}; border-radius: {t.R_CARD}px;
    text-align: left; padding: 10px 12px; font-weight: 500; }}
QPushButton[class="preset"]:hover {{ background: {c['card2']}; }}
QPushButton[class="icon"] {{ background: {c['fill']}; border-radius: 16px; min-width: 32px; max-width: 32px;
    min-height: 32px; max-height: 32px; padding: 0; }}
QPushButton[class="mode"] {{ background: {c['fill']}; color: {c['label']}; border-radius: 16px; padding: 7px 12px; }}
QPushButton[class="mode"]:checked {{ background: {c['accent']}; color: white; }}

/* the Figma look: alert pills, metric cards, the day strip, start pill */
QFrame[class="pill"] {{ background: {c['pill']}; border-radius: {t.R_PILL}px; border: none; }}
QFrame[class="pill"]:hover {{ background: {c['pill']}; }}
QFrame[class="pill"] QLabel {{ background: transparent; color: {c['pill_ink']}; }}
QFrame[class="pill"] QLabel[class="pillreason"] {{ color: {c['pill_ink2']}; font-size: {t.SUB}px; }}
QLabel[class="pilltitle"] {{ font-size: {t.ROW}px; font-weight: 600; }}
QPushButton[class="go"] {{ background: {c['go']}; color: {c['go_ink']}; border-radius: 17px; padding: 7px 16px;
    font-weight: 700; font-size: {t.SUB + 1}px; min-height: 20px; }}
QPushButton[class="go"]:hover {{ background: {c['go']}; color: #000000; }}
QPushButton[class="go"]:disabled {{ background: {c['fill2']}; color: {c['label3']}; }}
QFrame[class="mcard"], QFrame[class="bigcard"] {{ background: {c['card']}; border-radius: {t.R_BIG}px; border: none; }}
QFrame[class="mcard"]:hover, QFrame[class="bigcard"]:hover {{ background: {c['card2']}; }}
QFrame[class="softpill"] {{ background: {c['card']}; border-radius: 22px; border: none; }}
QLabel[class="mlabel"] {{ font-size: {t.ROW}px; font-weight: 600; }}
QLabel[class="mwhen"] {{ font-size: {t.SUB}px; color: {c['label2']}; }}
QLabel[class="mbig"] {{ font-size: 34px; font-weight: 700; }}
QLabel[class="munit"] {{ font-size: {t.ROW}px; font-weight: 600; color: {c['label2']}; padding-bottom: 6px; }}
QLabel[class="section"] {{ font-size: 22px; font-weight: 700; }}
QLabel[class="tilename"] {{ font-size: {t.ROW}px; font-weight: 600; }}
QLabel[class="tilesub"] {{ font-size: {t.SUB}px; color: {c['label2']}; }}
QPushButton[class="start"] {{ background: {c['pill']}; color: {c['pill_ink']}; border-radius: 30px;
    min-height: 60px; max-height: 60px; padding: 0 24px 0 70px; font-size: 18px; font-weight: 600;
    text-align: left; }}
QPushButton[class="start"]:hover {{ background: {c['pill']}; color: {c['go']}; }}
QPushButton[class="start"]:disabled {{ background: {c['fill2']}; color: {c['label3']}; }}

/* Search's drill pages: the metric hero number, the trend card, the countdown's cancel */
QLabel[class="hero"] {{ font-size: 52px; font-weight: 700; }}
QLabel[class="heroavg"] {{ font-size: {t.ROW}px; font-weight: 600; }}
QLabel[class="trendtitle"] {{ font-size: 19px; font-weight: 700; }}
QLabel[class="trendbig"] {{ font-size: 30px; font-weight: 700; }}
QLabel[class="facepick"] {{ font-size: {t.SUB}px; color: {c['label2']}; }}
QPushButton[class="logbtn"] {{ background: {c['fill']}; color: {c['accent']}; border-radius: 17px; padding: 7px 14px;
    font-weight: 600; font-size: {t.SUB + 1}px; min-height: 20px; }}
QPushButton[class="logbtn"]:hover {{ background: {c['fill2']}; }}

/* inputs */
QLineEdit, QSpinBox, QDoubleSpinBox, QTimeEdit, QDateEdit, QComboBox, QPlainTextEdit {{ background: {c['fill']};
    border: none; border-radius: 9px; padding: 6px 10px; min-height: 20px; selection-background-color: {c['accent']}; }}
QLineEdit[class="plain"] {{ background: transparent; padding: 4px 0; }}
QLineEdit[class="code"] {{ background: transparent; padding: 4px 0; font-size: 24px; font-weight: 600; }}
QTimeEdit[class="pill"], QDateEdit[class="pill"], QSpinBox[class="pill"], QDoubleSpinBox[class="pill"],
QComboBox[class="pill"] {{ border-radius: 8px; padding: 5px 8px; }}
QComboBox::drop-down {{ border: none; width: 18px; }}
QComboBox QAbstractItemView {{ background: {c['card']}; border: 1px solid {c['sep']}; selection-background-color:
    {c['accent']}; }}
QCheckBox {{ spacing: 8px; }}
QCheckBox::indicator {{ width: 20px; height: 20px; border-radius: 10px; border: 2px solid {c['label3']};
    background: transparent; }}
QCheckBox::indicator:checked {{ background: {c['accent']}; border-color: {c['accent']}; }}
QAbstractSpinBox::up-button, QAbstractSpinBox::down-button {{ width: 0; border: none; }}
QScrollBar:vertical {{ background: transparent; width: 6px; margin: 2px; }}
QScrollBar::handle:vertical {{ background: {c['fill2']}; border-radius: 3px; min-height: 30px; }}
QScrollBar::add-line, QScrollBar::sub-line, QScrollBar::add-page, QScrollBar::sub-page {{
    height: 0; background: none; }}
QProgressBar {{ background: {c['fill']}; border: none; border-radius: 3px; max-height: 6px; text-align: center; }}
QProgressBar::chunk {{ background: {c['accent']}; border-radius: 3px; }}

/* the Figma look: settings lists, sign-in, the Test lab */
QLabel[class="grouptitle"] {{ font-size: 17px; font-weight: 700; }}
QFrame[class="biglist"] {{ background: {c['card']}; border-radius: 20px; border: none; }}
QLabel[class="brand"] {{ font-size: 40px; font-weight: 800; }}
QLabel[class="hint"] {{ font-size: {t.SUB}px; color: {c['label2']}; }}
QLabel[class="codetag"] {{ font-size: {t.SMALL}px; font-weight: 600; color: {c['label3']}; }}
QFrame[class="field"] {{ background: {c['card']}; border-radius: {t.R_BIG}px; border: none; }}
QPushButton[class="gobig"] {{ background: {c['go']}; color: {c['go_ink']}; border-radius: 26px; min-height: 40px;
    font-size: {t.TITLE + 1}px; font-weight: 700; }}
QPushButton[class="gobig"]:hover {{ background: {c['go']}; color: #000000; }}
QPushButton[class="gobig"]:disabled {{ background: {c['fill2']}; color: {c['label3']}; }}
QPushButton[class="run"] {{ background: {c['fill']}; color: {c['label']}; border-radius: 14px; padding: 4px 14px;
    min-height: 20px; font-size: {t.SUB}px; font-weight: 600; }}
QPushButton[class="run"]:disabled {{ color: {c['label3']}; }}
QPushButton[class="valuepick"] {{ background: transparent; color: {c['label2']}; font-weight: 400; padding: 0;
    font-size: {t.ROW}px; text-align: right; }}
QPushButton[class="valuepick"]:hover {{ background: transparent; color: {c['label']}; }}
{_tints(p)}
"""
