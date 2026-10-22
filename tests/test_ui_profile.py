
"""Profile, the Test lab and Sign-in in the new look: alerts and sheets instead of Windows dialogs. No network."""

from __future__ import annotations

from datetime import date

import pytest
from PyQt6.QtWidgets import QDialog

from aura_mobile.ui import layers
from aura_mobile.ui.pages import lab as lab_page
from aura_mobile.ui.pages.profile import ProfilePage

from .test_ui import phone, settle  # noqa: F401  (the fixture)

#: no widget may flash up as its own window (see conftest.stray_windows)
pytestmark = pytest.mark.usefixtures("stray_windows")


def test_delete_needs_delete_typed(phone, monkeypatch):  # noqa: F811
    window, ctx, _api = phone
    window._open_profile()
    settle()
    alert = window.profile.delete_alert()
    assert not alert.buttons[1].isEnabled()
    alert.field.setText("DELETE")
    assert alert.buttons[1].isEnabled()

    calls = []
    monkeypatch.setattr(ctx.api, "delete_account", lambda: {}, raising=False)
    monkeypatch.setattr(ctx, "call", lambda fn, *a, **k: calls.append(fn))
    monkeypatch.setattr(layers.Alert, "exec", lambda self: QDialog.DialogCode.Accepted)
    window.profile._delete()  # accepted, but the field is empty
    assert calls == []

    def typed(self):
        self.field.setText("DELETE")
        return QDialog.DialogCode.Accepted

    monkeypatch.setattr(layers.Alert, "exec", typed)
    window.profile._delete()
    assert calls == [ctx.api.delete_account]


def test_appearance_drives_dark_mode(phone):  # noqa: F811
    _window, ctx, _api = phone
    seen: list[bool] = []
    page = ProfilePage(ctx, on_appearance=seen.append)
    page.set_dark(True)
    assert page.appearance.index() == 1 and seen == []  # set_dark doesn't call back
    page.appearance.buttons[0].click()
    assert seen == [False]


def test_sex_pill_picks_from_an_action_sheet(phone, monkeypatch):  # noqa: F811
    window, _ctx, _api = phone
    window._open_profile()
    settle()
    assert window.profile.body_changes()["sex"] == "notSet"
    monkeypatch.setattr(layers.ActionSheet, "pick", staticmethod(lambda *a, **k: 1))
    window.profile.sex.click()
    assert window.profile.sex.text() == "Female"
    assert window.profile.body_changes()["sex"] == "female"


def test_profile_shows_missing_count_and_inbox(phone):  # noqa: F811
    window, _ctx, _api = phone
    window._open_profile()
    settle()
    assert window.profile.complete.text().startswith("4 missing")
    assert "Short night" in window.profile.inbox.rows.itemAt(0).widget().text()


def test_inbox_row_opens_actions(phone, monkeypatch):  # noqa: F811
    window, ctx, _api = phone
    window._open_profile()
    settle()
    monkeypatch.setattr(layers.ActionSheet, "pick", staticmethod(lambda *a, **k: 2))  # read, snooze, dismiss
    written = []
    monkeypatch.setattr(ctx, "write", lambda fn, area, on_done=None: written.append(area))
    window.profile.inbox.rows.itemAt(0).widget().clicked.emit()
    assert all(n.get("status") == "dismissed" for n in ctx.store.data["inbox"] if n.get("id") == "n1")
    assert written


def test_lab_reset_asks_first(phone, monkeypatch):  # noqa: F811
    window, ctx, _api = phone
    window._open_lab()
    settle()
    resets = []
    monkeypatch.setattr(ctx.lab.state, "reset", lambda: resets.append(1))
    monkeypatch.setattr(layers.Alert, "ask", staticmethod(lambda *a, **k: False))
    window.lab._reset()
    assert resets == []
    monkeypatch.setattr(layers.Alert, "ask", staticmethod(lambda *a, **k: True))
    window.lab._reset()
    assert resets == [1]


def test_lab_row_status_and_progress(phone):  # noqa: F811
    window, _ctx, _api = phone
    window._open_lab()
    settle()
    row = window.lab.rows["DX-04"]
    row.set_progress(0.5)
    assert not row.bar.isHidden() and row.bar.fraction == 0.5
    row.set_progress(None)
    assert row.bar.isHidden()
    row.set_status("Failed", "bad")
    assert row.status.property("tone") == "bad"
    assert not window.lab.stop.isEnabled()


def test_lab_spans_read_short():
    assert lab_page._span([date(2026, 10, 21)]) == "1 day, 21 Oct"
    assert lab_page._span([date(2026, 10, 8), date(2026, 10, 21)]) == "2 days, 8–21 Oct"
    assert lab_page._span([date(2026, 9, 28), date(2026, 10, 5)]) == "2 days, 28 Sep – 5 Oct"


def test_sign_in_code_step(phone):  # noqa: F811
    window, ctx, _api = phone
    from aura_mobile.services.auth import AuthStep

    ctx.sign_out()
    page = window.signin
    page._code_sent(AuthStep(email="a@b.c", kind="sign_in", session="s", code_length=8, destination="a***"))
    assert page.steps.currentIndex() == 1
    assert "an 8-digit sign-in code" in page.sent_to.text()
    page.code.setText("123")
    page._verify()
    assert page.error.text() == "8 digits, please."
    page.reset()
    assert page.steps.currentIndex() == 0 and not page.error.text()
