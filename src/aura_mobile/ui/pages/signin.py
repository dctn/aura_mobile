"""Sign in: email, then the emailed code. A new email signs up on the way (a 6-digit code instead of 8).

The screen is the Figma minimum: the brand, one field and one green pill per step; the SES sandbox and the
two-codes case are explained behind the ⓘ.

After the code: keep the tokens, grant the health-data consent the band needs (``PUT /v1/me/consent``), register
this phone with Notification (no push token, so alerts reach the inbox), then open the app.
"""

from __future__ import annotations

import socket
import uuid

from PyQt6.QtCore import Qt
from PyQt6.QtGui import QFont
from PyQt6.QtWidgets import QFrame, QHBoxLayout, QLineEdit, QStackedWidget, QVBoxLayout, QWidget

from aura_mobile.services.auth import AuthStep, Tokens
from aura_mobile.ui import kit, prefs
from aura_mobile.ui import tokens as t
from aura_mobile.ui.context import AppContext, describe_error
from aura_mobile.ui.pages.profile import info_button
from aura_mobile.ui.widgets import label


def device_id() -> str:
    """One id per PC, kept in prefs, so re-signing in updates the same device row."""
    known = prefs.load().get("deviceId")
    if isinstance(known, str) and known:
        return known
    made = f"demo-phone-{uuid.uuid4().hex[:12]}"
    prefs.save({"deviceId": made})
    return made


#: the ⓘ sheet: what the short screen leaves out
ABOUT = (
    ("me", "New here? The same step creates the account."),
    ("bell", "Dev email is in the SES sandbox: the code only reaches verified addresses."),
    ("lock", "An existing account gets an 8-digit code. One that still needs confirming may get a 6-digit one "
             "too: either works."),
)


def _field(field: QLineEdit, icon: str) -> QFrame:
    """A white radius-24 card holding one field, with a grey icon in front."""
    card = QFrame()
    card.setProperty("class", "field")
    card.setMinimumHeight(56)
    lay = QHBoxLayout(card)
    lay.setContentsMargins(18, 6, 18, 6)
    lay.setSpacing(10)
    lay.addWidget(kit.Glyph(icon, 20, "label3"))
    lay.addWidget(field, 1)
    return card


class SignInPage(QWidget):
    """The brand, one field and one green pill: email → Send code, then the code → Sign in."""

    def __init__(self, ctx: AppContext) -> None:
        super().__init__()
        self.setObjectName("page")
        self.ctx = ctx
        self._step: AuthStep | None = None

        outer = QVBoxLayout(self)
        outer.setContentsMargins(t.GUTTER, 20, t.GUTTER, 28)
        outer.setSpacing(0)
        top = QHBoxLayout()
        top.addStretch(1)
        self.info = info_button(self, "Signing in", ABOUT)
        top.addWidget(self.info)
        outer.addLayout(top)
        outer.addSpacing(64)
        mark = QHBoxLayout()
        mark.addStretch(1)
        mark.addWidget(kit.Disc("heart", "sleep", 72))
        mark.addStretch(1)
        outer.addLayout(mark)
        outer.addSpacing(14)
        title = label("Aura", "brand")
        title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        outer.addWidget(title)
        sub = label("Demo phone", "subtitle")
        sub.setAlignment(Qt.AlignmentFlag.AlignCenter)
        outer.addWidget(sub)
        outer.addSpacing(36)

        self.steps = QStackedWidget()
        outer.addWidget(self.steps)

        email_page = QWidget()
        lay = QVBoxLayout(email_page)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(14)
        self.email = QLineEdit()
        self.email.setProperty("class", "plain")
        self.email.setPlaceholderText("you@example.com")
        self.email.returnPressed.connect(self._send)
        lay.addWidget(_field(self.email, "me"))
        self.send = kit.text_button("Send code", "gobig")
        self.send.clicked.connect(self._send)
        lay.addWidget(self.send)
        lay.addStretch(1)
        self.steps.addWidget(email_page)

        code_page = QWidget()
        lay = QVBoxLayout(code_page)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(14)
        self.code = QLineEdit()
        self.code.setProperty("class", "code")
        self.code.setAlignment(Qt.AlignmentFlag.AlignCenter)
        font = self.code.font()
        font.setLetterSpacing(QFont.SpacingType.AbsoluteSpacing, 6)
        self.code.setFont(font)
        self.code.returnPressed.connect(self._verify)
        lay.addWidget(_field(self.code, "lock"))
        self.sent_to = label("", "hint", wrap=True)
        self.sent_to.setAlignment(Qt.AlignmentFlag.AlignCenter)
        lay.addWidget(self.sent_to)
        self.verify = kit.text_button("Sign in", "gobig")
        self.verify.clicked.connect(self._verify)
        lay.addWidget(self.verify)
        links = QHBoxLayout()
        resend = kit.text_button("Resend", "link")
        resend.clicked.connect(self._resend)
        back = kit.text_button("Change email", "link")
        back.clicked.connect(self._back)
        links.addWidget(resend)
        links.addStretch(1)
        links.addWidget(back)
        lay.addLayout(links)
        lay.addStretch(1)
        self.steps.addWidget(code_page)

        self.error = label("", "bad", wrap=True)
        self.error.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.error.setContentsMargins(16, 10, 16, 0)
        outer.addWidget(self.error)
        outer.addStretch(1)

    def reset(self) -> None:
        self._step = None
        self.code.clear()
        self.error.clear()
        self._busy(False)
        self.steps.setCurrentIndex(0)

    def _busy(self, busy: bool) -> None:
        self.send.setEnabled(not busy)
        self.verify.setEnabled(not busy)

    # -- step 1 ----------------------------------------------------------------

    def _send(self) -> None:
        email = self.email.text().strip()
        if not email:
            return
        self.error.clear()
        self._busy(True)
        self.ctx.call(lambda: self.ctx.cognito.start(email), self._code_sent, self._failed)

    def _resend(self) -> None:
        if self._step is None:
            return
        step = self._step
        self._busy(True)
        self.ctx.call(lambda: self.ctx.cognito.resend(step), self._code_sent, self._failed)

    def _code_sent(self, step: AuthStep) -> None:
        self._step = step
        self._busy(False)
        if step.also_confirm:  # 8 or 6 digits: the ⓘ explains
            self.sent_to.setText(f"We sent a code to {step.destination}.")
        else:
            what = "confirmation" if step.is_new_account else "sign-in"
            article = "an" if step.code_length == 8 else "a"
            self.sent_to.setText(f"We sent {article} {step.code_length}-digit {what} code to {step.destination}.")
        self.code.setMaxLength(step.code_length)
        self.code.setPlaceholderText("•" * step.code_length)
        self.code.clear()
        self.steps.setCurrentIndex(1)
        self.code.setFocus()

    def _back(self) -> None:
        self.reset()

    # -- step 2 ----------------------------------------------------------------

    def _verify(self) -> None:
        if self._step is None:
            return
        step = self._step
        code = self.code.text().strip()
        allowed = {step.code_length, 6} if step.also_confirm else {step.code_length}
        if len(code) not in allowed:
            self.error.setText(" or ".join(str(n) for n in sorted(allowed)) + " digits, please.")
            return
        self.error.clear()
        self._busy(True)
        self.ctx.call(lambda: self._complete(step, code), self._signed_in, self._failed)

    def _complete(self, step: AuthStep, code: str) -> Tokens:
        """On a worker: tokens, then consent and the device. Consent must succeed; the device is best effort."""
        tokens = self.ctx.cognito.finish(step, code)
        self.ctx.session.begin(tokens)
        self.ctx.api.grant_consent(self.ctx.config.consent_policy_version)
        try:
            self.ctx.api.register_device(device_id(), name=f"Aura demo on {socket.gethostname()}")
        except Exception:
            pass
        return tokens

    def _signed_in(self, _tokens: Tokens) -> None:
        self.reset()
        self.ctx.after_sign_in()

    def _failed(self, exc: Exception) -> None:
        self._busy(False)
        self.error.setText(describe_error(exc))
