"""Things that sit on top of the phone: the HUD pill, bottom sheets, alerts and action sheets.

Each layer is a frameless, translucent ``QDialog`` laid exactly over the phone window that paints its own scrim, so
callers keep the synchronous ``dialog.exec()`` flow (and tests can still monkeypatch ``exec``).
"""

from __future__ import annotations

from collections.abc import Sequence

from PyQt6.QtCore import QEasingCurve, QPoint, QPropertyAnimation, Qt, QTimer, QVariantAnimation
from PyQt6.QtGui import QPainter
from PyQt6.QtWidgets import (
    QApplication,
    QDialog,
    QFrame,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QScrollArea,
    QVBoxLayout,
    QWidget,
)

from aura_mobile.ui import tokens as t
from aura_mobile.ui.widgets import label, set_shown

SHEET_TOP = 54  # the sheet stops this far below the top of the phone, like a page sheet


def _host(parent: QWidget | None) -> QWidget | None:
    if parent is not None:
        return parent.window()
    return QApplication.activeWindow()


class Layer(QDialog):
    """A see-through dialog over the whole phone. Subclasses put their panel in ``self`` and lay it out in
    :meth:`place`. Clicking the scrim calls :meth:`scrim_clicked` (``reject`` unless ``modal_scrim``)."""

    modal_scrim = False
    strong = False

    def __init__(self, parent: QWidget | None) -> None:
        host = _host(parent)
        super().__init__(host)
        self.setWindowFlags(Qt.WindowType.Dialog | Qt.WindowType.FramelessWindowHint)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setModal(True)
        self._fade = 0.0
        self._scrim_anim = QVariantAnimation(self)
        self._scrim_anim.setDuration(220)
        self._scrim_anim.valueChanged.connect(self._faded)
        if host is not None:
            self.setGeometry(host.geometry())
        else:
            self.resize(t.PHONE_WIDTH, t.PHONE_HEIGHT)

    def _faded(self, v: float) -> None:
        self._fade = float(v)
        self.update()

    def showEvent(self, event) -> None:
        super().showEvent(event)
        host = self.parentWidget()
        if host is not None:
            self.setGeometry(host.geometry())
        self.place()
        self._scrim_anim.setStartValue(0.0)
        self._scrim_anim.setEndValue(1.0)
        self._scrim_anim.start()
        self.opened()

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        self.place()

    def place(self) -> None:
        """Lay the panel out over ``self.rect()``."""

    def opened(self) -> None:
        """Start an entrance animation."""

    def scrim_clicked(self) -> None:
        if not self.modal_scrim:
            self.reject()

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        c = t.qcolor(t.current().scrim_strong if self.strong else t.current().scrim)
        c.setAlphaF(c.alphaF() * self._fade)
        p.fillRect(self.rect(), c)

    def mousePressEvent(self, event) -> None:
        target = self.childAt(event.position().toPoint())
        if target is None:
            self.scrim_clicked()
            return
        super().mousePressEvent(event)


# -- sheet --------------------------------------------------------------------------------------------------


class Sheet(Layer):
    """A bottom sheet: grabber, header (Cancel · title · Done), a scrolling body, an optional sticky footer button.

    Put widgets in ``self.body``. Done calls :meth:`accept`; override it to read the form (call ``super().accept()``
    when it's valid). ``tall=True`` takes the full page height; otherwise the sheet fits its content.
    """

    def __init__(self, parent: QWidget | None, title: str = "", *, done: str | None = "Done",
                 cancel: str | None = "Cancel", footer: str | None = None, tall: bool = False) -> None:
        super().__init__(parent)
        self.tall = tall
        self.panel = QFrame(self)
        self.panel.setProperty("class", "sheet")
        outer = QVBoxLayout(self.panel)
        outer.setContentsMargins(0, 6, 0, 0)
        outer.setSpacing(0)
        grab_row = QHBoxLayout()
        grab_row.addStretch(1)
        grab_row.addWidget(_Grabber())
        grab_row.addStretch(1)
        outer.addLayout(grab_row)
        head = QHBoxLayout()
        head.setContentsMargins(8, 0, 8, 0)
        self.cancel_button = QPushButton(cancel or "")
        self.cancel_button.setProperty("class", "link")
        self.cancel_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.cancel_button.clicked.connect(self.reject)
        set_shown(self.cancel_button, bool(cancel))
        self.done_button = QPushButton(done or "")
        self.done_button.setProperty("class", "linkbold")
        self.done_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.done_button.clicked.connect(self.accept)
        set_shown(self.done_button, bool(done))
        self.title = label(title, "sheettitle")
        self.title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        side = 84
        self.cancel_button.setFixedWidth(side)
        self.done_button.setFixedWidth(side)
        for b in (self.cancel_button, self.done_button):  # a missing side still holds its room: the title stays centred
            policy = b.sizePolicy()
            policy.setRetainSizeWhenHidden(True)
            b.setSizePolicy(policy)
        head.addWidget(self.cancel_button)
        head.addWidget(self.title, 1)
        head.addWidget(self.done_button)
        header = QWidget()
        header.setFixedHeight(42)
        header.setLayout(head)
        outer.addWidget(header)
        self.scroll = QScrollArea()
        self.scroll.setObjectName("sheetbody")
        self.scroll.setWidgetResizable(True)
        self.scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        inner = QWidget()
        inner.setObjectName("sheetpage")
        self.body = QVBoxLayout(inner)
        self.body.setContentsMargins(t.GUTTER, 8, t.GUTTER, 16)
        self.body.setSpacing(16)
        self.body.setAlignment(Qt.AlignmentFlag.AlignTop)  # short content stays at the top of a taller sheet
        self.scroll.setWidget(inner)
        outer.addWidget(self.scroll, 1)
        self.footer_button: QPushButton | None = None
        if footer:
            self.footer_button = QPushButton(footer)
            self.footer_button.setProperty("class", "primary")
            self.footer_button.setCursor(Qt.CursorShape.PointingHandCursor)
            self.footer_button.clicked.connect(self.accept)
            foot = QVBoxLayout()
            foot.setContentsMargins(t.GUTTER, 8, t.GUTTER, 22)
            foot.addWidget(self.footer_button)
            outer.addLayout(foot)
        else:
            outer.addSpacing(18)
        self._slide = QPropertyAnimation(self.panel, b"pos", self)
        self._slide.setDuration(300)
        self._slide.setEasingCurve(QEasingCurve.Type.OutCubic)

    def set_title(self, text: str) -> None:
        self.title.setText(text)

    def _panel_height(self) -> int:
        most = self.height() - SHEET_TOP
        if self.tall:
            return most
        self.body.activate()
        inner = self.scroll.widget()
        # wrapped labels only know their height at a width, so measure at the sheet's own
        h = inner.heightForWidth(max(1, self.width())) if inner.hasHeightForWidth() else -1
        want = max(h, inner.sizeHint().height()) + 6 + 42 + (90 if self.footer_button else 18) + 6
        return max(220, min(most, want))

    def place(self) -> None:
        h = self._panel_height()
        self.panel.setGeometry(0, self.height() - h, self.width(), h)

    def opened(self) -> None:
        end = self.panel.pos()
        self._slide.setStartValue(QPoint(end.x(), self.height()))
        self._slide.setEndValue(end)
        self._slide.start()


class _Grabber(QWidget):
    def __init__(self) -> None:
        super().__init__()
        self.setFixedSize(36, 5)

    def paintEvent(self, _event) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(t.qcolor(t.current().grabber))
        p.drawRoundedRect(self.rect(), 2.5, 2.5)


# -- alert --------------------------------------------------------------------------------------------------


class Alert(Layer):
    """The 270-wide centred alert. ``buttons`` are ``(text, role)`` with role ``""``, ``"bold"``, ``"danger"`` or
    ``"cancel"``. ``choice`` is the index pressed; ``exec()`` returns 1 unless a cancel button (or Esc) closed it.
    Extra widgets (a field) go in ``self.extra``."""

    modal_scrim = True
    strong = False

    def __init__(self, parent: QWidget | None, title: str, text: str = "",
                 buttons: Sequence[tuple[str, str]] = (("OK", "bold"),)) -> None:
        super().__init__(parent)
        self.choice: int | None = None
        self.box = QFrame(self)
        self.box.setProperty("class", "alertbox")
        self.box.setFixedWidth(270)
        lay = QVBoxLayout(self.box)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(0)
        top = QVBoxLayout()
        top.setContentsMargins(16, 18, 16, 16)
        top.setSpacing(4)
        self.title = label(title, "alerttitle", wrap=True)
        self.title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        top.addWidget(self.title)
        self.text = label(text, "alerttext", wrap=True)
        self.text.setAlignment(Qt.AlignmentFlag.AlignCenter)
        set_shown(self.text, bool(text))
        top.addWidget(self.text)
        self.extra = QVBoxLayout()
        self.extra.setContentsMargins(0, 6, 0, 0)
        top.addLayout(self.extra)
        lay.addLayout(top)
        self.buttons: list[QPushButton] = []
        row = QHBoxLayout() if len(buttons) == 2 else QVBoxLayout()
        row.setSpacing(0)
        for i, (text_, role) in enumerate(buttons):
            b = QPushButton(text_)
            b.setProperty("class", "alertbtn")
            b.setProperty("bold", role == "bold" or len(buttons) == 1)
            b.setProperty("danger", role == "danger")
            b.setProperty("role", role)
            b.setProperty("side", len(buttons) == 2 and i == 1)  # the hairline between two side-by-side buttons
            b.setCursor(Qt.CursorShape.PointingHandCursor)
            b.clicked.connect(lambda _=False, i=i: self._pressed(i))
            row.addWidget(b, 1)
            self.buttons.append(b)
        lay.addLayout(row)

    def _pressed(self, i: int) -> None:
        self.choice = i
        if self.buttons[i].property("role") == "cancel":
            self.reject()
        else:
            self.accept()

    def place(self) -> None:
        self.box.adjustSize()
        self.box.move((self.width() - self.box.width()) // 2, (self.height() - self.box.height()) // 2)

    @staticmethod
    def ask(parent: QWidget | None, title: str, text: str = "", ok: str = "OK", cancel: str = "Cancel", *,
            danger: bool = False) -> bool:
        a = Alert(parent, title, text, ((cancel, "cancel"), (ok, "danger" if danger else "bold")))
        return a.exec() == QDialog.DialogCode.Accepted

    @staticmethod
    def tell(parent: QWidget | None, title: str, text: str = "") -> None:
        Alert(parent, title, text).exec()


# -- action sheet -------------------------------------------------------------------------------------------


class ActionSheet(Layer):
    """Choices at the bottom, then a separate Cancel. ``choice`` is the index picked (``None`` on Cancel)."""

    def __init__(self, parent: QWidget | None, title: str = "", actions: Sequence[str | tuple[str, str]] = (),
                 cancel: str = "Cancel") -> None:
        super().__init__(parent)
        self.choice: int | None = None
        self.panel = QWidget(self)
        lay = QVBoxLayout(self.panel)
        lay.setContentsMargins(8, 0, 8, 22)
        lay.setSpacing(8)
        group = QFrame()
        group.setProperty("class", "actiongroup")
        g = QVBoxLayout(group)
        g.setContentsMargins(0, 0, 0, 0)
        g.setSpacing(0)
        first = True
        if title:
            head = label(title, "actiontitle", wrap=True)
            head.setAlignment(Qt.AlignmentFlag.AlignCenter)
            head.setContentsMargins(16, 12, 16, 12)
            g.addWidget(head)
            first = False
        for i, action in enumerate(actions):
            text, role = (action, "") if isinstance(action, str) else action
            b = QPushButton(text)
            b.setProperty("class", "actionbtn")
            b.setProperty("first", first)
            b.setProperty("danger", role == "danger")
            b.setCursor(Qt.CursorShape.PointingHandCursor)
            b.clicked.connect(lambda _=False, i=i: self._pressed(i))
            g.addWidget(b)
            first = False
        lay.addWidget(group)
        self.cancel_button = QPushButton(cancel)
        self.cancel_button.setProperty("class", "actioncancel")
        self.cancel_button.setCursor(Qt.CursorShape.PointingHandCursor)
        self.cancel_button.clicked.connect(self.reject)
        lay.addWidget(self.cancel_button)
        self._slide = QPropertyAnimation(self.panel, b"pos", self)
        self._slide.setDuration(260)
        self._slide.setEasingCurve(QEasingCurve.Type.OutCubic)

    def _pressed(self, i: int) -> None:
        self.choice = i
        self.accept()

    def place(self) -> None:
        self.panel.setFixedWidth(self.width())
        self.panel.adjustSize()
        self.panel.move(0, self.height() - self.panel.height())

    def opened(self) -> None:
        end = self.panel.pos()
        self._slide.setStartValue(QPoint(0, self.height()))
        self._slide.setEndValue(end)
        self._slide.start()

    @staticmethod
    def pick(parent: QWidget | None, title: str, actions: Sequence[str | tuple[str, str]],
             cancel: str = "Cancel") -> int | None:
        a = ActionSheet(parent, title, actions, cancel)
        return a.choice if a.exec() == QDialog.DialogCode.Accepted else None


# -- HUD ----------------------------------------------------------------------------------------------------


class Hud(QLabel):
    """A pill near the top of the phone for a moment ("Saved", "New alert: …")."""

    def __init__(self, parent: QWidget) -> None:
        super().__init__(parent)
        self.setProperty("class", "hud")
        self.setWordWrap(True)
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents)
        self.hide()
        self._timer = QTimer(self)
        self._timer.setSingleShot(True)
        self._timer.timeout.connect(self.hide)

    def show_message(self, text: str, ms: int = 1800) -> None:
        self.setText(text)
        host = self.parentWidget()
        most = host.width() - 2 * t.GUTTER
        self.setMaximumWidth(most)
        self.setMinimumWidth(0)
        width = min(most, self.fontMetrics().horizontalAdvance(text) + 44)
        self.setFixedWidth(max(140, width))
        self.adjustSize()
        self.move((host.width() - self.width()) // 2, 52)
        self.raise_()
        self.show()
        # longer messages stay a bit longer
        self._timer.start(max(ms, min(4200, 900 + 45 * len(text))))
