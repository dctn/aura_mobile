"""What every page shares: the config, the session, the API, the lab, and the signals that say what changed.

Pages never call the API from a slot directly; :meth:`AppContext.call` runs it on a worker and answers on the UI
thread, and a failure becomes a toast unless the page handles it.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from typing import Any

from PyQt6.QtCore import QObject, pyqtSignal

from aura_mobile.services.api import AuraApi
from aura_mobile.services.auth import AuthSession, CognitoClient, CognitoError, TokenStore
from aura_mobile.services.config import AppConfig
from aura_mobile.services.http import ApiError, ServiceClient
from aura_mobile.services.sim.runner import LabRunner, LabState
from aura_mobile.ui.store import ALL_PARTS, Store
from aura_mobile.ui.tasks import run_async

log = logging.getLogger(__name__)

# Areas for ``changed``: a page refreshes when an area it shows changes.
ALERTS = "alerts"
CHECKINS = "checkins"
CALENDAR = "calendar"
TASKS = "tasks"
SESSIONS = "sessions"
PROFILE = "profile"
METRICS = "metrics"
INBOX = "inbox"

#: which store parts an area's change reloads
AREA_PARTS: dict[str, tuple[str, ...]] = {
    ALERTS: ("alerts",),
    INBOX: ("inbox",),
    CALENDAR: ("events",),
    TASKS: ("tasks",),
    METRICS: ("metrics",),
    CHECKINS: ("checkins", "logs"),
    SESSIONS: ("active", "sessions"),
    PROFILE: ("me", "alert_off"),
}


def describe_error(exc: BaseException) -> str:
    if isinstance(exc, ApiError | CognitoError):
        return exc.message
    return f"{exc.__class__.__name__}: {exc}"


class AppContext(QObject):
    signed_in = pyqtSignal()
    signed_out = pyqtSignal()
    #: an area name; the pages showing it reload
    changed = pyqtSignal(str)
    toast = pyqtSignal(str)
    #: the server ended the session (from a worker thread; queued to the UI)
    _ended = pyqtSignal()

    def __init__(self, config: AppConfig, *, session: AuthSession | None = None,
                 api: AuraApi | None = None) -> None:
        super().__init__()
        self.config = config
        self.cognito = CognitoClient(config)
        self.session = session or AuthSession(self.cognito, TokenStore(config.stage))
        self.session.on_ended = self._ended.emit
        self._ended.connect(self._on_ended)
        self.api = api or AuraApi(ServiceClient(config, self.session))
        self.me: dict = {}
        self.store = Store(self.api)
        self.store.enabled = self._signed_in
        self.store.updated.connect(self._store_updated)
        self.lab_state: LabState | None = None
        self.lab: LabRunner | None = None

    # -- session ---------------------------------------------------------------

    @property
    def sub(self) -> str:
        tokens = self.session.tokens
        return tokens.sub if tokens else ""

    @property
    def email(self) -> str:
        tokens = self.session.tokens
        return tokens.email if tokens else ""

    def _signed_in(self) -> bool:
        return self.session.is_signed_in

    def _store_updated(self, names: frozenset) -> None:
        if "me" in names and isinstance(self.store.data.get("me"), dict):
            self.me = self.store.data["me"]

    def after_sign_in(self) -> None:
        """Open the lab for this account, load every page's data at once, tell the shell.

        No ``warm()`` pings here: on dev each one takes one of the account's 10 Lambda slots, and the load itself
        opens a connection to every service the pages use.
        """
        self.lab_state = LabState.load(self.sub)
        self.lab = LabRunner(self.api, self.lab_state)
        # Health data on by default, also for a restored session (sign-in grants it too, but dev lost the route
        # for a day, so older accounts never got it).
        if hasattr(self.api, "ensure_consent"):
            policy = self.config.consent_policy_version
            run_async(lambda: self.api.ensure_consent(policy), self._ignore, self._consent_failed)
        self.store.load(ALL_PARTS)
        self.signed_in.emit()

    def sign_out(self) -> None:
        held = self.session.clear()
        if held is not None:
            refresh = held.refresh_token
            run_async(lambda: self.cognito.revoke(refresh), self._ignore)
        self.me = {}
        self.store.clear()
        self.lab = None
        self.lab_state = None
        self.signed_out.emit()

    def _on_ended(self) -> None:
        self.toast.emit("Your session has ended. Sign in again.")
        self.me = {}
        self.store.clear()
        self.signed_out.emit()

    def _consent_failed(self, exc: Exception) -> None:
        log.warning("Could not turn health data on: %s", exc)

    def _ignore(self, _result: Any) -> None:
        pass

    # -- calls -----------------------------------------------------------------

    def call(
        self,
        fn: Callable[..., Any],
        on_done: Callable[[Any], None],
        on_error: Callable[[Exception], None] | None = None,
        *,
        on_progress: Callable[[str, float], None] | None = None,
    ) -> None:
        run_async(fn, on_done, on_error or self.report_error, on_progress=on_progress)

    def report_error(self, exc: Exception) -> None:
        self.toast.emit(describe_error(exc))

    def notify(self, area: str) -> None:
        """Something in ``area`` changed on the server: reload its store parts and tell the other pages."""
        self.store.load(AREA_PARTS.get(area, ()))
        self.changed.emit(area)

    def write(self, fn: Callable[[], Any], area: str, on_done: Callable[[Any], None] | None = None) -> None:
        """Send a write whose result the screen already shows (patched); then reload ``area`` to confirm.

        ``on_done`` runs before the reload, so a patch it makes from the answer is not dropped by the reload.
        On failure the toast says why, and ``area`` and the alerts reload to put back what the server has (an action
        hides its alert as soon as it is sent).
        """
        def done(result: Any) -> None:
            if on_done is not None:
                on_done(result)
            self.notify(area)

        def failed(exc: Exception) -> None:
            self.report_error(exc)
            self.notify(area)
            if area != ALERTS:
                self.store.load(AREA_PARTS[ALERTS])

        run_async(fn, done, failed)
