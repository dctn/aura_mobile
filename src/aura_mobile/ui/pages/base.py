"""A scrolling page column.

Two kinds of page:
- **Store pages** list their ``parts``. They draw from ``ctx.store`` at once when shown, ask again only for parts
  older than ``STALE_AFTER_S``, and redraw just the parts that changed (:meth:`Page.render`).
- **Area pages** (the Test lab) list their ``areas`` and :meth:`Page.refresh` themselves when one changes, only
  while on screen.
"""

from __future__ import annotations

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import QScrollArea, QVBoxLayout, QWidget

from aura_mobile.ui import tokens as t
from aura_mobile.ui.context import AppContext
from aura_mobile.ui.kit import NavBar
from aura_mobile.ui.widgets import label, set_shown

#: the large title compacts into the nav bar after this much scroll
COMPACT_AFTER = 34
#: room under the last section for the floating tab bar
TABBAR_ROOM = t.TABBAR_H + t.TABBAR_BOTTOM + 24


class Page(QScrollArea):
    """Every page has the prototype's nav bar (``self.nav``: back on the left, round buttons on the right) and a
    large title (``self.large``, with ``self.subtitle``) at the top of ``self.column``. Scrolled past 34 px, the
    title moves into the nav bar."""

    #: the store parts this page draws
    parts: tuple[str, ...] = ()
    #: the ``AppContext.changed`` areas this page reloads on (pages without ``parts``)
    areas: tuple[str, ...] = ()
    #: the large title
    heading: str = ""

    def __init__(self, ctx: AppContext) -> None:
        super().__init__()
        self.ctx = ctx
        self.setWidgetResizable(True)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setViewportMargins(0, t.NAV_H, 0, 0)
        self.nav = NavBar(self)
        inner = QWidget()
        inner.setObjectName("page")
        self.column = QVBoxLayout(inner)
        self.column.setContentsMargins(t.GUTTER, 0, t.GUTTER, TABBAR_ROOM)
        self.column.setSpacing(t.SECTION_GAP - 6)
        self.large = label(self.heading, "largetitle")
        self.subtitle = label("", "subtitle")
        self.subtitle.hide()
        top = QVBoxLayout()
        top.setContentsMargins(0, 0, 0, 0)
        top.setSpacing(0)
        top.addWidget(self.large)
        top.addWidget(self.subtitle)
        self.column.addLayout(top)
        self.setWidget(inner)
        self.set_heading(self.heading)
        self.verticalScrollBar().valueChanged.connect(self._scrolled)
        self._stale = True
        self._dirty: set[str] = set(self.parts)
        if self.parts:
            ctx.store.updated.connect(self._on_store)
        else:
            ctx.changed.connect(self._on_changed)

    # -- nav bar -----------------------------------------------------------------

    def set_heading(self, title: str, subtitle: str = "") -> None:
        self.large.setText(title)
        set_shown(self.large, bool(title))
        self.nav.title.setText(title)
        self.subtitle.setText(subtitle)
        set_shown(self.subtitle, bool(subtitle))

    def set_bottom_room(self, room: int) -> None:
        """Pages pushed over the tabs (no tab bar) need less room at the bottom."""
        m = self.column.contentsMargins()
        self.column.setContentsMargins(m.left(), m.top(), m.right(), room)

    def _scrolled(self, value: int) -> None:
        self.nav.set_compact(value > COMPACT_AFTER)

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        self.nav.setGeometry(0, 0, self.width(), t.NAV_H)
        self.nav.raise_()

    # -- store pages -------------------------------------------------------------

    def _on_store(self, names: frozenset) -> None:
        mine = set(names) & set(self.parts)
        if not mine:
            return
        if self.isVisible():
            self.render(mine)
        else:
            self._dirty |= mine

    def render(self, names: set[str]) -> None:
        """Draw the cards that show these parts from ``ctx.store``. Store pages override this."""

    # -- area pages --------------------------------------------------------------

    def _on_changed(self, name: str) -> None:
        if name in self.areas:
            self.mark_stale()

    def mark_stale(self) -> None:
        if self.parts:
            self._dirty |= set(self.parts)
            if self.isVisible():
                self._show_store()
            return
        self._stale = True
        if self.isVisible():
            self._reload()

    def showEvent(self, event) -> None:
        super().showEvent(event)
        if self.parts:
            self._show_store()
        elif self._stale:
            self._reload()

    def _show_store(self) -> None:
        if self._dirty:
            names, self._dirty = self._dirty, set()
            self.render(names)
        if self.ctx.session.is_signed_in:
            self.ctx.store.ensure(self.parts)

    def _reload(self) -> None:
        self._stale = False
        if self.ctx.session.is_signed_in:
            self.refresh()

    def refresh(self) -> None:
        """Ask again: a store page reloads its parts; an area page overrides this."""
        if self.parts:
            self.ctx.store.load(self.parts)
