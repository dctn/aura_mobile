"""The phone: a 390×844 window with the page stack and a floating pill tab bar (Home, Recovery, Search).

Each page owns its nav bar and large title. Profile and the Test lab are not tabs: they open from round buttons on
Home's nav bar as pushed pages ("‹ Home") and close with Back or Esc. Signed out, only the sign-in page shows. A new
alert arriving while the app is open shows the HUD and flashes the taskbar (push is not set up on dev); the bedtime
reminder and wake alarm ring locally (``ui/alarms.py``). Timers run in a full-phone view (``ui/runview.py``).
"""

from __future__ import annotations

from datetime import datetime

from PyQt6.QtCore import QPointF, QRectF, Qt, QTimer
from PyQt6.QtGui import QPainter
from PyQt6.QtWidgets import (
    QApplication,
    QFrame,
    QGraphicsDropShadowEffect,
    QHBoxLayout,
    QPushButton,
    QStackedWidget,
    QVBoxLayout,
    QWidget,
)

from aura_mobile.ui import actions, icons, kit, layers, prefs, runview, theme
from aura_mobile.ui import context as area
from aura_mobile.ui import tokens as t
from aura_mobile.ui.alarms import Alarms
from aura_mobile.ui.context import AppContext
from aura_mobile.ui.pages.base import Page
from aura_mobile.ui.pages.home import HomePage, open_alerts
from aura_mobile.ui.pages.lab import LabPage
from aura_mobile.ui.pages.profile import ProfilePage
from aura_mobile.ui.pages.recovery import RecoveryPage
from aura_mobile.ui.pages.search import SearchPage
from aura_mobile.ui.pages.signin import SignInPage

#: how often the visible page asks again, so a fired alert shows without a click
POLL_MS = 60_000

#: (name, icon) per tab; stack indices 0-2
TABS = (("Home", "home"), ("Recovery", "recover"), ("Search", "search"))


def apply_theme(dark: bool) -> None:
    """Switch light/dark: painted widgets read the current palette, the rest restyle from the stylesheet."""
    t.set_current(t.palette_for(dark))
    app = QApplication.instance()
    if app is None:
        return
    app.setStyleSheet(theme.stylesheet(t.current()))
    for w in app.topLevelWidgets():
        if isinstance(w, PhoneWindow):
            w.restyle()
    for w in app.allWidgets():
        w.update()


def long_date(day: datetime) -> str:
    """"Thursday 22 October" (no leading zero)."""
    return f"{day:%A} {day.day} {day:%B}"


class TabButton(QPushButton):
    """One tab: the icon above the name; the checked tab is accent on a fill pill (the pill comes from QSS)."""

    ICON = 24

    def __init__(self, name: str, icon: str, index: int) -> None:
        super().__init__(name)
        self.icon_name = icon
        self.setProperty("class", "tab")
        self.setProperty("tab", index)
        self.setCheckable(True)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setFixedHeight(t.TABBAR_H - 12)

    def paintEvent(self, event) -> None:
        super().paintEvent(event)
        p = QPainter(self)
        ink = kit.colour("accent" if self.isChecked() else "label3")
        x = (self.width() - self.ICON) / 2
        p.drawPixmap(QPointF(x, 5), icons.pixmap(self.icon_name, ink, self.ICON, stroke=2.0 if self.isChecked()
                                                 else 1.8))


class TabBar(QFrame):
    """The floating pill over the bottom of the stack."""

    def __init__(self, parent: QWidget) -> None:
        super().__init__(parent)
        self.setObjectName("tabbar")
        lay = QHBoxLayout(self)
        lay.setContentsMargins(6, 6, 6, 6)
        lay.setSpacing(4)
        self.buttons: list[TabButton] = []
        for i, (name, icon) in enumerate(TABS):
            b = TabButton(name, icon, i)
            lay.addWidget(b, 1)
            self.buttons.append(b)
        self.shadow = QGraphicsDropShadowEffect(self)
        self.shadow.setBlurRadius(28)
        self.shadow.setOffset(0, 6)
        self.setGraphicsEffect(self.shadow)
        self.restyle()

    def restyle(self) -> None:
        self.shadow.setColor(t.qcolor(t.current().shadow))

    def paintEvent(self, event) -> None:
        # Qt has no backdrop blur: a solid card under the translucent bar keeps the page from showing through.
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(kit.colour("card"))
        r = self.height() / 2
        p.drawRoundedRect(QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5), r, r)
        p.end()
        super().paintEvent(event)

    def place(self) -> None:
        host = self.parentWidget()
        w = host.width() - 2 * t.TABBAR_INSET
        self.setGeometry(t.TABBAR_INSET, host.height() - t.TABBAR_BOTTOM - t.TABBAR_H, w, t.TABBAR_H)


class PhoneWindow(QWidget):
    def __init__(self, ctx: AppContext) -> None:
        super().__init__()
        self.ctx = ctx
        self.setObjectName("phone")
        self.setWindowTitle("Aura · demo phone (dev)")
        self.setFixedSize(t.PHONE_WIDTH, t.PHONE_HEIGHT)
        self._back_to = 0

        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.setSpacing(0)

        self.stack = QStackedWidget()
        self.home = HomePage(ctx)
        self.home.on_profile = self._open_profile
        self.recovery = RecoveryPage(ctx)
        self.search = SearchPage(ctx)
        self.profile = ProfilePage(ctx, on_appearance=self._set_dark)
        self.lab = LabPage(ctx)
        self.signin = SignInPage(ctx)
        for page in (self.home, self.recovery, self.search, self.profile, self.lab, self.signin):
            self.stack.addWidget(page)
        outer.addWidget(self.stack, 1)
        self.tab_pages: tuple[Page, ...] = (self.home, self.recovery, self.search)
        for page, (name, _icon) in zip(self.tab_pages[1:], TABS[1:], strict=True):
            if not page.large.text():
                page.set_heading(name)
        self._date_heading()

        # Settings (profile, goals, alerts, appearance) from a gear on every tab; the Test lab from Home
        self.lab_button = kit.IconButton("flask", "round", size=19, tip="Test lab: send band days and watch alerts "
                                                                          "fire")
        self.lab_button.clicked.connect(self._open_lab)
        self.home.nav.add_right(self.lab_button)
        self.settings_buttons: list[QPushButton] = []
        for page in self.tab_pages:
            gear = kit.IconButton("settings", "round", size=20, tip="Settings: profile, goals, alerts, appearance")
            gear.clicked.connect(self._open_profile)
            page.nav.add_right(gear)
            self.settings_buttons.append(gear)
        self.avatar = self.settings_buttons[0]

        self.tabbar = TabBar(self)
        self.tab_buttons: list[QPushButton] = list(self.tabbar.buttons)
        for b in self.tab_buttons:
            b.clicked.connect(self._tab_clicked)
        self.tabbar.place()
        self.tabbar.raise_()

        self.toast = layers.Hud(self)
        ctx.toast.connect(self.toast.show_message)
        ctx.signed_in.connect(self._on_signed_in)
        ctx.signed_out.connect(self._on_signed_out)
        ctx.store.updated.connect(self._on_store)

        #: open alert ids already shown; None until the first load after sign-in, which raises no banner
        self._seen_alerts: set[str] | None = None
        self.alarms = Alarms(lambda: actions.now_local(ctx), lambda: actions.health_goals(ctx), self._ring, self)

        self._poll = QTimer(self)
        self._poll.setInterval(POLL_MS)
        self._poll.timeout.connect(self._tick)

        dark = bool(prefs.load().get("dark"))
        self.profile.set_dark(dark)
        apply_theme(dark)
        runview.install(self)
        self.show_signed_out()

    # -- layout ------------------------------------------------------------------

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        self.tabbar.place()
        self.tabbar.raise_()

    def restyle(self) -> None:
        """Theme switch: the parts not covered by the stylesheet."""
        self.tabbar.restyle()

    def _date_heading(self) -> None:
        try:
            today = actions.now_local(self.ctx)
        except (KeyError, ValueError, AttributeError):  # no time zone yet: this computer's
            today = datetime.now()
        self.home.set_heading("Home", long_date(today))

    # -- navigation --------------------------------------------------------------

    def _overlay_open(self) -> bool:
        return self.stack.currentWidget() in (self.profile, self.lab)

    def show_signed_out(self) -> None:
        self.tabbar.hide()
        self.signin.reset()
        self.stack.setCurrentWidget(self.signin)
        runview.sync(self)

    def show_tab(self, index: int) -> None:
        if index == 0:
            self._date_heading()
        self.stack.setCurrentIndex(index)
        for i, b in enumerate(self.tab_buttons):
            b.setChecked(i == index)
        self.tabbar.show()
        self.tabbar.raise_()
        runview.sync(self)

    def _tab_clicked(self) -> None:
        self.show_tab(int(self.sender().property("tab")))

    def _open_overlay(self, page: Page) -> None:
        current = self.stack.currentIndex()
        if current < len(TABS):
            self._back_to = current
        page.nav.set_back(TABS[self._back_to][0], self._close_overlay)
        page.set_bottom_room(24)
        self.stack.setCurrentWidget(page)
        self.tabbar.hide()
        runview.sync(self)

    def _open_profile(self) -> None:
        self._open_overlay(self.profile)

    def _open_lab(self) -> None:
        self._open_overlay(self.lab)

    def _close_overlay(self) -> None:
        self.show_tab(self._back_to)

    # -- session -----------------------------------------------------------------

    def _on_signed_in(self) -> None:
        for page in (self.home, self.recovery, self.search, self.profile, self.lab):
            page.mark_stale()
        self.show_tab(0)
        self._seen_alerts = None
        self._poll.start()
        self.alarms.start()

    def _on_signed_out(self) -> None:
        self._poll.stop()
        self.alarms.stop()
        self.lab.stop_watching()
        self.show_signed_out()

    def _on_store(self, names: frozenset) -> None:
        if "alerts" in names and "alerts" in self.ctx.store.data:
            self._banner_new_alerts()

    def _banner_new_alerts(self) -> None:
        alerts = open_alerts(self.ctx.store.data.get("alerts") or [])
        ids = {str(a.get("id")) for a in alerts}
        if self._seen_alerts is None:
            self._seen_alerts = ids
            return
        new = [a for a in alerts if str(a.get("id")) not in self._seen_alerts]
        self._seen_alerts |= ids
        if new:
            more = f" (+{len(new) - 1} more)" if len(new) > 1 else ""
            self._ring(f"New alert: {new[0].get('title')}{more}")

    def _ring(self, message: str) -> None:
        self.toast.show_message(message)
        QApplication.beep()
        QApplication.alert(self, 0)

    def _tick(self) -> None:
        if self.ctx.session.is_signed_in and not self.isMinimized():
            self.ctx.notify(area.ALERTS)
            self.ctx.notify(area.INBOX)

    def _set_dark(self, dark: bool) -> None:
        prefs.save({"dark": dark})
        apply_theme(dark)

    def keyPressEvent(self, event) -> None:
        if event.key() == Qt.Key.Key_Escape and self._overlay_open():
            self._close_overlay()
            return
        super().keyPressEvent(event)
