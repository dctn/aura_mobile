from __future__ import annotations

import json
from collections.abc import Callable

import httpx
import pytest

from aura_mobile.services.api import AuraApi
from aura_mobile.services.config import AppConfig, load_config
from aura_mobile.services.http import ServiceClient


class FakeSession:
    """A TokenSource that counts refreshes and rejections."""

    def __init__(self) -> None:
        self.token = "tok-1"
        self.refreshes = 0
        self.rejections = 0

    def access_token(self) -> str:
        return self.token

    def force_refresh(self) -> str:
        self.refreshes += 1
        self.token = f"tok-{self.refreshes + 1}"
        return self.token

    def rejected(self) -> None:
        self.rejections += 1


Handler = Callable[[httpx.Request], httpx.Response]


def reply(status: int = 200, body: object = None) -> httpx.Response:
    return httpx.Response(status, json=body if body is not None else {})


def body_of(request: httpx.Request) -> dict:
    return json.loads(request.content or b"{}")


@pytest.fixture
def config() -> AppConfig:
    return load_config()


@pytest.fixture
def session() -> FakeSession:
    return FakeSession()


@pytest.fixture
def make_api(config: AppConfig, session: FakeSession) -> Callable[[Handler], tuple[AuraApi, list[httpx.Request]]]:
    def build(handler: Handler) -> tuple[AuraApi, list[httpx.Request]]:
        seen: list[httpx.Request] = []

        def record(request: httpx.Request) -> httpx.Response:
            seen.append(request)
            return handler(request)

        client = ServiceClient(config, session, transport=httpx.MockTransport(record), sleep=lambda _s: None,
                               time_zone="Asia/Kolkata")
        return AuraApi(client), seen

    return build


@pytest.fixture
def stray_windows():
    """Fail a UI test when a plain widget flashes up as its own little window: a child shown (``setVisible(True)``)
    before it had a parent. On Windows that is a blank frame that blinks for a few ms (and a ``setGeometry`` warning
    on the console)."""
    import os

    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
    from PyQt6.QtCore import QEvent, QObject, Qt
    from PyQt6.QtWidgets import QApplication, QDialog, QScrollArea, QWidget

    app = QApplication.instance() or QApplication([])
    seen: list[str] = []

    class Watch(QObject):
        def eventFilter(self, obj, event):
            if (event.type() == QEvent.Type.Show and isinstance(obj, QWidget) and obj.isWindow()
                    and obj.windowType() == Qt.WindowType.Window
                    and not isinstance(obj, QDialog | QScrollArea) and obj.objectName() != "phone"
                    and not obj.property("test_host")):
                text = obj.text() if hasattr(obj, "text") else ""
                seen.append(f"{type(obj).__name__}({text!r})")
            return False

    watch = Watch()
    app.installEventFilter(watch)
    yield seen
    app.removeEventFilter(watch)
    assert not seen, f"widgets flashed up as their own windows: {seen}"
