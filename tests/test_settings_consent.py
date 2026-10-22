"""Health data on by default, Settings reachable from every tab, and body details set in sheets. No network."""

from __future__ import annotations

import pytest
from PyQt6.QtCore import QDate
from PyQt6.QtWidgets import QDialog

from aura_mobile.services import http
from aura_mobile.services.http import ApiError
from aura_mobile.ui.pages import profile as profile_page
from tests.conftest import body_of, reply
from tests.test_ui import phone, settle  # noqa: F401  (the fixture)

#: no widget may flash up as its own window (see conftest.stray_windows)
pytestmark = pytest.mark.usefixtures("stray_windows")

REFUSED = {"error": {"code": "consent_required", "message": "Turn on health data in your privacy settings."}}


# -- consent ----------------------------------------------------------------------------------------------


def test_refused_write_turns_health_data_on_and_is_sent_again(make_api):
    answers = iter([reply(403, REFUSED), reply(200, {}), reply(201, {"id": "w1"})])
    api, seen = make_api(lambda r: next(answers))
    api.client.post("health-data", "/v1/logs", {})
    assert [(r.method, r.url.path) for r in seen] == [
        ("POST", "/v1/logs"), ("PUT", "/v1/me/consent"), ("POST", "/v1/logs")]
    grant = body_of(seen[1])
    assert grant["healthData"] is True and grant["policyVersion"] == "2026-10"


def test_consent_is_granted_once_for_calls_refused_together(make_api):
    def handler(r):
        if r.url.path == "/v1/me/consent":
            return reply(200, {})
        handler.posts += 1
        return reply(403, REFUSED) if handler.posts in (1, 3) else reply(201, {})

    handler.posts = 0
    api, seen = make_api(handler)
    api.client.post("health-data", "/v1/checkins", {})
    api.client.post("health-data", "/v1/logs", {})
    assert sum(r.url.path == "/v1/me/consent" for r in seen) == 1


def test_waits_for_health_data_to_hear_of_the_grant(make_api):
    """Dev, 22 Oct: Health Data keeps its own consent copy, updated by an event a few seconds after the grant, so
    the first resend was still refused. The client waits and tries again."""
    waits: list[float] = []
    refusals = iter([True, True, True, False])  # the first call, then two resends before the copy catches up

    def handler(r):
        if r.url.path == "/v1/me/consent":
            return reply(200, {})
        return reply(403, REFUSED) if next(refusals) else reply(201, {"id": "w1"})

    api, seen = make_api(handler)
    api.client._sleep = waits.append
    assert api.client.post("health-data", "/v1/logs", {}) == {"id": "w1"}
    assert [r.url.path for r in seen] == ["/v1/logs", "/v1/me/consent", "/v1/logs", "/v1/logs", "/v1/logs"]
    assert waits == list(http.CONSENT_WAITS[:3])


def test_a_refusal_after_every_wait_is_given_up(make_api):
    api, seen = make_api(lambda r: reply(200, {}) if r.url.path == "/v1/me/consent" else reply(403, REFUSED))
    with pytest.raises(ApiError) as caught:
        api.client.post("health-data", "/v1/samples", {})
    assert caught.value.code == "consent_required"
    paths = [r.url.path for r in seen]
    assert paths.count("/v1/me/consent") == 1
    assert paths.count("/v1/samples") == 1 + len(http.CONSENT_WAITS)


def test_failed_grant_gives_the_original_refusal(make_api):
    api, _ = make_api(lambda r: reply(500, {}) if r.url.path == "/v1/me/consent" else reply(403, REFUSED))
    with pytest.raises(ApiError) as caught:
        api.client.post("health-data", "/v1/logs", {})
    assert caught.value.code == "consent_required"


@pytest.mark.parametrize(("view", "granted"), [
    ({"healthData": False, "required": True, "policyVersion": None}, True),
    ({"healthData": True, "required": True, "policyVersion": "2026-09"}, True),
    ({"healthData": True, "required": False, "policyVersion": "2026-10"}, False),
])
def test_ensure_consent(make_api, view, granted):
    api, seen = make_api(lambda r: reply(200, view if r.method == "GET" else {}))
    assert api.ensure_consent("2026-10") is granted
    assert [r.method for r in seen] == (["GET", "PUT"] if granted else ["GET"])


def test_sign_in_checks_consent(phone):  # noqa: F811
    _window, ctx, api = phone
    assert api.consent_checks == [ctx.config.consent_policy_version]


# -- settings ---------------------------------------------------------------------------------------------


def test_settings_gear_on_every_tab(phone):  # noqa: F811
    window, _ctx, _api = phone
    assert len(window.settings_buttons) == 3
    for i, gear in enumerate(window.settings_buttons):
        window.show_tab(i)
        settle()
        assert gear.isVisible()
        gear.click()
        settle()
        assert window.stack.currentWidget() is window.profile
        assert window.profile.large.text() == "Settings"
        window._close_overlay()


def _accept(monkeypatch, cls, **values):
    def fake_exec(self):
        for name, value in values.items():
            getattr(self, name).setValue(value) if hasattr(getattr(self, name), "setValue") else None
        return QDialog.DialogCode.Accepted

    monkeypatch.setattr(cls, "exec", fake_exec)


def test_height_and_weight_sheets_save_at_once(phone, monkeypatch):  # noqa: F811
    window, _ctx, api = phone
    window._open_profile()
    settle()
    page = window.profile
    assert page.height_row.value.text() == "Not set"
    _accept(monkeypatch, profile_page.NumberSheet, stepper=176)
    page.height_row.clicked.emit()
    settle()
    assert page.height_row.value.text() == "176 cm"
    assert any(w.get("heightCm") == 176 for w in api.profile_writes)
    page.weight_row.clicked.emit()
    settle()
    assert page.weight_row.value.text() == "176 kg"  # the same fake sheet answer
    assert any(w.get("weightKg") == 176.0 for w in api.profile_writes)


def test_birth_date_sheet_saves_and_shows_age(phone, monkeypatch):  # noqa: F811
    window, _ctx, api = phone
    window._open_profile()
    settle()
    page = window.profile
    monkeypatch.setattr(profile_page.BirthSheet, "exec", lambda self: QDialog.DialogCode.Accepted)
    monkeypatch.setattr(profile_page.BirthSheet, "date", lambda self: QDate(1995, 3, 12))
    page.birth_row.clicked.emit()
    settle()
    assert page.birth_row.value.text().startswith("12 Mar 1995 · age ")
    assert any(w.get("birthDate") == "1995-03-12" for w in api.profile_writes)


def test_birth_sheet_clamps_the_day_to_the_month(phone):  # noqa: F811
    window, _ctx, _api = phone
    sheet = profile_page.BirthSheet(window.profile, QDate(1990, 1, 31), QDate.currentDate().addYears(-5))
    sheet.month.setCurrentIndex(1)  # February
    assert sheet.date() == QDate(1990, 2, 28)
    assert "age" in sheet.age.text()
    sheet.deleteLater()


def test_sex_pick_saves(phone, monkeypatch):  # noqa: F811
    window, _ctx, api = phone
    window._open_profile()
    settle()
    from aura_mobile.ui import layers

    monkeypatch.setattr(layers.ActionSheet, "pick", staticmethod(lambda *a, **k: 1))
    window.profile.sex.click()
    settle()
    assert any(w.get("sex") == "female" for w in api.profile_writes)
