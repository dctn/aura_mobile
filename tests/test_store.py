"""The store: parts load in parallel, a local change shows at once, and a slow answer can't undo it."""

from __future__ import annotations

import os
import threading
import time

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PyQt6.QtCore import QEventLoop, QThreadPool, QTimer
from PyQt6.QtWidgets import QApplication

from aura_mobile.ui import store as store_module
from aura_mobile.ui.store import Store, fetch_all
from aura_mobile.ui.tasks import in_flight_count

DELAY_S = 0.3


class SlowApi:
    """Every read takes ``DELAY_S``, like a call to us-west-1; ``gate`` holds ``tasks`` until set."""

    time_zone = "UTC"

    def __init__(self) -> None:
        self.gate = threading.Event()
        self.gate.set()

    def _slow(self, value):
        time.sleep(DELAY_S)
        return value

    def alerts(self, start, end):
        return self._slow([{"id": "a1"}])

    def notifications(self, limit=30):
        return self._slow([{"id": "n1"}])

    def events(self, start, end):
        return self._slow([])

    def tasks(self):
        self.gate.wait(5)
        return [{"id": "server"}]

    def me(self):
        return self._slow({"version": 1})


def settle() -> None:
    for _ in range(40):
        QThreadPool.globalInstance().waitForDone(200)
        loop = QEventLoop()
        QTimer.singleShot(20, loop.quit)
        loop.exec()
        if in_flight_count() == 0:
            return


_APP: list[QApplication] = []


def _app() -> QApplication:
    """One QApplication for the module; kept referenced, or Qt drops it and the thread pool with it."""
    if not _APP:
        _APP.append(QApplication.instance() or QApplication([]))
    return _APP[0]


def test_fetch_all_runs_the_parts_at_once():
    api = SlowApi()
    started = time.monotonic()
    out = fetch_all(api, ["alerts", "inbox", "events", "me"])
    took = time.monotonic() - started
    assert all(ok for ok, _ in out.values())
    assert took < DELAY_S * 2, f"4 reads of {DELAY_S}s took {took:.2f}s; they ran one after another"


def test_one_failed_part_stays_local():
    class Broken(SlowApi):
        def events(self, start, end):
            raise RuntimeError("down")

    out = fetch_all(Broken(), ["alerts", "events"])
    assert out["alerts"][0] is True
    assert out["events"] == (False, "RuntimeError: down")


def test_patch_shows_at_once_and_wins_over_a_load_already_under_way():
    _app()
    api = SlowApi()
    api.gate.clear()
    store = Store(api)
    seen: list[frozenset] = []
    store.updated.connect(seen.append)
    store.load(["tasks"])
    store.patch("tasks", lambda old: [*(old or []), {"id": "mine"}])
    assert store.data["tasks"] == [{"id": "mine"}]  # no waiting for the server
    assert seen == [frozenset({"tasks"})]
    api.gate.set()
    settle()
    assert store.data["tasks"] == [{"id": "mine"}]  # the load that started before the patch was dropped
    store.load(["tasks"])
    settle()
    assert store.data["tasks"] == [{"id": "server"}]  # a load after the patch brings the server's answer


def test_clear_drops_answers_that_land_after_sign_out():
    _app()
    api = SlowApi()
    api.gate.clear()
    store = Store(api)
    store.load(["tasks"])
    store.clear()
    api.gate.set()
    settle()
    assert "tasks" not in store.data


def test_ensure_skips_fresh_parts(monkeypatch):
    _app()
    store = Store(SlowApi())
    store.load(["alerts"])
    settle()
    asked: list[tuple[str, ...]] = []
    monkeypatch.setattr(store_module, "run_async", lambda fn, done, *a, **k: asked.append(fn))
    store.ensure(["alerts"])
    assert asked == []
    store.ensure(["alerts"], max_age=0)
    assert len(asked) == 1


def test_show_span_loads_a_new_range_only_once_and_caps_it():
    from datetime import date

    asked: list[tuple[str, str]] = []

    class Api(SlowApi):
        def events(self, start, end):
            asked.append((str(start)[:10], str(end)[:10]))
            return []

    _app()
    store = Store(Api())
    first = store_module.default_span(date.today())
    store.show_span(first[0], first[0])  # inside the default span: nothing to load
    settle()
    assert asked == []
    store.show_span(date(2030, 1, 1), date(2030, 6, 1))
    settle()
    assert store.span == (date(2030, 1, 1), date(2030, 3, 3))  # 62 days at most
    assert len(asked) == 1 and asked[0][0] == "2030-01-01"
    store.show_span(date(2030, 1, 5), date(2030, 2, 1))
    settle()
    assert len(asked) == 1
