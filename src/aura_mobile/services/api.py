"""The routes the mobile features use, one method each, grouped by the service that owns them.

Each method returns the parsed JSON body (``dict``/``list``) and raises
:class:`~aura_mobile.services.http.ApiError`. Shapes are in ``aws_docs/packages/contracts/src/*.ts``; the
section names below match them.
"""

from __future__ import annotations

import json
import uuid
from collections.abc import Iterable, Mapping, Sequence
from datetime import datetime
from typing import Any
from urllib.parse import quote

from aura_mobile.services.http import ApiError, ServiceClient, if_match

IDENTITY = "identity"
HEALTH = "health-data"
NOTIFICATION = "notification"
CALENDAR = "calendar"
TASK = "task"
SESSION = "session"


def new_id(prefix: str) -> str:
    """An entity id: 1-200 characters, no ``#``, no control characters."""
    return f"{prefix}-{uuid.uuid4().hex[:20]}"


def _p(value: str) -> str:
    return quote(value, safe="")


class AuraApi:
    def __init__(self, client: ServiceClient) -> None:
        self.client = client
        # Health data is on by default in this demo: a write refused for consent turns it on and is sent again.
        client.on_consent_required = lambda: self.grant_consent(client.config.consent_policy_version)

    @property
    def time_zone(self) -> str:
        return self.client.time_zone

    # -- Identity: /v1/config, /v1/me, consent, export, delete --------------------------------------

    def public_config(self) -> dict:
        return self.client.get(IDENTITY, "/v1/config", auth=False)

    def me(self) -> dict:
        return self.client.get(IDENTITY, "/v1/me")

    def consent(self) -> dict:
        return self.client.get(IDENTITY, "/v1/me/consent")

    def grant_consent(self, policy_version: str, *, health_data: bool = True) -> dict:
        """N27: health data must be on before the band's samples are accepted."""
        return self.client.put(
            IDENTITY,
            "/v1/me/consent",
            {
                "policyVersion": policy_version,
                "healthData": health_data,
                "aiAssistant": False,
                "productAnalytics": False,
            },
        )

    def ensure_consent(self, policy_version: str) -> bool:
        """Health data on for the current policy (the demo's default). True when it had to be turned on."""
        view = self.consent()
        view = view.get("consent", view) if isinstance(view, dict) else {}
        if view.get("healthData") and not view.get("required"):
            return False
        self.grant_consent(policy_version)
        return True

    def update_profile(self, changes: Mapping[str, Any], version: int) -> dict:
        """O30: merged field by field, ``healthGoals`` too. A stale version is 409/412; re-read and retry."""
        return self.client.put(
            IDENTITY, "/v1/me/profile", dict(changes), headers={"If-Match": if_match(version)}
        )

    def update_profile_fresh(self, changes: Mapping[str, Any], version: int | None = None) -> dict:
        """Write with ``version`` (the one on screen) or a freshly read one; on a version clash read again and retry."""
        for attempt in range(2):
            if version is None:
                version = int(self.me().get("version") or 0)
            try:
                return self.update_profile(changes, version)
            except ApiError as exc:
                if attempt == 0 and exc.status in (409, 412):
                    version = None
                    continue
                raise
        raise AssertionError("unreachable")

    def update_settings(self, changes: Mapping[str, Any]) -> dict:
        """``PUT /v1/me/settings``: merged key by key, no version; ``None`` removes a key. Answers the profile."""
        return self.client.put(IDENTITY, "/v1/me/settings", dict(changes))

    def request_export(self) -> dict:
        return self.client.post(IDENTITY, "/v1/me/exports", {})

    def export_status(self, export_id: str) -> dict:
        return self.client.get(IDENTITY, f"/v1/me/exports/{_p(export_id)}")

    def delete_account(self) -> dict:
        """202 ``{requestedAt}``. The deletion saga runs on its own; the account is gone a few minutes later."""
        return self.client.delete(IDENTITY, "/v1/me")

    # -- Health Data: band samples, raw upload, compute --------------------------------------------

    def post_samples(self, packets: Sequence[Mapping[str, Any]], *, session_id: str | None = None) -> dict:
        body: dict[str, Any] = {"timeZone": self.time_zone, "packets": list(packets)}
        if session_id:
            body["sessionId"] = session_id
        return self.client.post(HEALTH, "/v1/samples", body)

    def upload_raw_day(self, date: str, envelope: Mapping[str, Any]) -> int:
        """The phone's bulk path: a presigned POST for the day's file, then the file."""
        ticket = self.client.post(HEALTH, "/v1/uploads/raw", {"date": date})
        body = json.dumps(envelope).encode("utf-8")
        return self.client.post_form(ticket["url"], ticket.get("fields") or {}, body, filename=f"{date}.json")

    def compute_day(self, date: str) -> dict:
        """202. At most 5 an hour per person (UTC clock hour); the 6th is 429."""
        return self.client.post(HEALTH, f"/v1/metrics/daily/{_p(date)}/compute", {})

    def metrics_day(self, date: str) -> dict | None:
        """One computed day, or ``None`` when that day has no row yet."""
        try:
            body = self.client.get(HEALTH, f"/v1/metrics/daily/{_p(date)}")
        except ApiError as exc:
            if exc.status == 404:
                return None
            raise
        return body.get("day") if isinstance(body, dict) else None

    def metrics_range(self, start: str, end: str, *, fields: str = "summary") -> list[dict]:
        days: list[dict] = []
        cursor: str | None = None
        while True:
            body = self.client.get(
                HEALTH, "/v1/metrics/daily", params={"from": start, "to": end, "fields": fields, "cursor": cursor}
            )
            days.extend(body.get("days") or [])
            cursor = body.get("cursor")
            if not cursor:
                return days

    # -- Health Data: check-ins and logs -----------------------------------------------------------

    def post_checkin(self, kind: str, *, taken_at: datetime, **values: Any) -> dict:
        """mood / connection: ``mood=1..5``; insomnia (the sleep check): ``insomnia=[7 answers, 0-4 each]``."""
        body: dict[str, Any] = {"id": new_id(kind), "kind": kind, "takenAt": taken_at.isoformat()}
        body.update({k: v for k, v in values.items() if v is not None})
        return self.client.post(HEALTH, "/v1/checkins", body)

    def checkins(self, start: str, end: str, kind: str | None = None) -> list[dict]:
        body = self.client.get(HEALTH, "/v1/checkins", params={"from": start, "to": end, "kind": kind})
        return list(body.get("checkins") or [])

    def post_log(self, log_type: str, data: Mapping[str, Any], *, taken_at: datetime) -> dict:
        """A manual entry: ``water`` ``{ml}`` (NI-04), ``strength`` ``{minutes}`` (PB-04)."""
        return self.client.post(
            HEALTH,
            "/v1/logs",
            {
                "id": new_id(log_type),
                "type": log_type,
                "date": taken_at.date().isoformat(),
                "takenAt": taken_at.isoformat(),
                "data": dict(data),
            },
        )

    def log_water(self, ml: int, *, taken_at: datetime) -> dict:
        """NI-04: a glass logged by hand."""
        return self.post_log("water", {"ml": int(ml)}, taken_at=taken_at)

    def logs(self, start: str, end: str, log_type: str | None = None) -> list[dict]:
        body = self.client.get(HEALTH, "/v1/logs", params={"from": start, "to": end, "type": log_type})
        return list(body.get("logs") or [])

    def session_trace(self, session_id: str) -> dict:
        """``GET /v1/trends/sessions/{id}``: heart rate and stress during a session; ``stressDrop`` for recovery ones.

        ``status`` is ``pending`` until the session's day is computed, ``noData`` without band data.
        """
        return self.client.get(HEALTH, f"/v1/trends/sessions/{_p(session_id)}")

    # -- Health Data: mobile alerts ----------------------------------------------------------------

    def alerts(self, start: str, end: str) -> list[dict]:
        body = self.client.get(HEALTH, "/v1/alerts", params={"from": start, "to": end})
        return list(body.get("alerts") or [])

    def alert_done(self, alert_id: str) -> dict:
        return self.client.post(HEALTH, f"/v1/alerts/{_p(alert_id)}/done", {})

    def alert_settings(self) -> list[str]:
        return list(self.client.get(HEALTH, "/v1/alerts/settings").get("off") or [])

    def set_alert_settings(self, off: Iterable[str]) -> list[str]:
        return list(self.client.put(HEALTH, "/v1/alerts/settings", {"off": sorted(set(off))}).get("off") or [])

    # -- Notification: inbox and device ------------------------------------------------------------

    def notifications(self, *, limit: int = 50) -> list[dict]:
        body = self.client.get(NOTIFICATION, "/v1/notifications", params={"limit": limit})
        return list(body.get("notifications") or [])

    def notification_read(self, notification_id: str) -> dict:
        return self.client.post(NOTIFICATION, f"/v1/notifications/{_p(notification_id)}/read", {})

    def notification_dismiss(self, notification_id: str) -> dict:
        return self.client.post(NOTIFICATION, f"/v1/notifications/{_p(notification_id)}/dismiss", {})

    def notification_snooze(self, notification_id: str, minutes: int) -> dict:
        return self.client.post(
            NOTIFICATION, f"/v1/notifications/{_p(notification_id)}/snooze", {"minutes": int(minutes)}
        )

    def register_device(self, device_id: str, *, name: str) -> dict:
        """No push token: APNs/FCM are not set up for this demo, so alerts reach the inbox only."""
        return self.client.post(
            NOTIFICATION,
            "/v1/devices",
            {
                "deviceId": device_id,
                "platform": self.client.config.platform,
                "pushToken": None,
                "appVersion": self.client.config.app_version,
                "name": name,
            },
        )

    # -- Calendar ----------------------------------------------------------------------------------

    def create_event(self, title: str, start: datetime, end: datetime) -> dict:
        return self.client.post(
            CALENDAR,
            "/v1/calendar/events",
            {"id": new_id("demo"), "title": title, "start": start.isoformat(), "end": end.isoformat()},
        )

    def move_event(self, event_id: str, *, to: str = "tomorrow") -> dict:
        """O10: keeps the length. ``to``: ``tomorrow`` (same time when free), ``nextWorkday`` or ``nextFree``."""
        return self.client.post(CALENDAR, f"/v1/calendar/events/{_p(event_id)}/move", {"to": to})

    def delete_event(self, event_id: str) -> Any:
        return self.client.delete(CALENDAR, f"/v1/calendar/events/{_p(event_id)}")

    def event(self, event_id: str) -> dict:
        body = self.client.get(CALENDAR, f"/v1/calendar/events/{_p(event_id)}")
        return body.get("event", body) if isinstance(body, dict) else {}

    def update_event(self, event_id: str, changes: Mapping[str, Any], version: int | None = None) -> dict:
        """``PATCH /v1/calendar/events/{id}`` (title, start, end, …); a stale version is re-read once."""
        return self._patch_fresh(CALENDAR, f"/v1/calendar/events/{_p(event_id)}", changes, version,
                                 lambda: self.event(event_id))

    def events(self, start: str, end: str) -> list[dict]:
        """``GET /v1/calendar/events?from&to``: at most 62 days, repeating events expanded, paged."""
        out: list[dict] = []
        cursor: str | None = None
        while True:
            body = self.client.get(CALENDAR, "/v1/calendar/events", params={"from": start, "to": end, "cursor": cursor})
            out.extend(body.get("events") or [])
            cursor = body.get("cursor")
            if not cursor:
                return out

    # -- Task --------------------------------------------------------------------------------------

    def tasks(self, *, status: str | None = None, horizon: str | None = None) -> list[dict]:
        out: list[dict] = []
        cursor: str | None = None
        while True:
            body = self.client.get(TASK, "/v1/tasks", params={"status": status, "horizon": horizon, "cursor": cursor})
            out.extend(body.get("tasks") or [])
            cursor = body.get("cursor")
            if not cursor:
                return out

    def create_task(self, title: str, *, horizon: str = "today", task_id: str | None = None, **fields: Any) -> dict:
        """``fields``: ``dueAt`` + ``hasTime`` (a day, or a time on it), ``estimateMinutes``, ``notes``; None is left
        out. ``task_id`` lets the screen show the task under the id the server will keep."""
        body: dict[str, Any] = {"id": task_id or new_id("task"), "title": title, "horizon": horizon}
        body.update({k: v for k, v in fields.items() if v is not None})
        return self.client.post(TASK, "/v1/tasks", body)

    def task(self, task_id: str) -> dict:
        body = self.client.get(TASK, f"/v1/tasks/{_p(task_id)}")
        return body.get("task", body) if isinstance(body, dict) else {}

    def update_task(self, task_id: str, changes: Mapping[str, Any], version: int | None = None) -> dict:
        """``PATCH /v1/tasks/{id}``: fields sent are set, None clears. A stale version is re-read once."""
        return self._patch_fresh(TASK, f"/v1/tasks/{_p(task_id)}", changes, version, lambda: self.task(task_id))

    def delete_task(self, task_id: str) -> Any:
        return self.client.delete(TASK, f"/v1/tasks/{_p(task_id)}")

    def complete_task(self, task_id: str) -> dict:
        return self.client.post(TASK, f"/v1/tasks/{_p(task_id)}/complete", {})

    def _patch_fresh(self, service: str, path: str, changes: Mapping[str, Any], version: int | None,
                     read: Any) -> dict:
        """PATCH with If-Match ``version`` (or a freshly read one); on 409/412 read again and retry once."""
        for attempt in range(2):
            if version is None:
                version = int(read().get("version") or 0)
            try:
                return self.client.patch(service, path, dict(changes), headers={"If-Match": if_match(version)})
            except ApiError as exc:
                if attempt == 0 and exc.status in (409, 412):
                    version = None
                    continue
                raise
        raise AssertionError("unreachable")

    # -- Session -----------------------------------------------------------------------------------

    def start_session(self, kind: str, mode: str, *, minutes: int | None, title: str) -> dict:
        """One running session per person: a second start answers the running one with ``joined: true``."""
        return self.client.post(
            SESSION,
            "/v1/sessions",
            {
                "id": new_id("session"),
                "kind": kind,
                "mode": mode,
                "plannedMinutes": minutes if mode != "flowtime" else None,
                "title": title,
                "device": "phone",
            },
        )

    def session_action(self, session_id: str, action: str) -> dict:
        return self.client.post(SESSION, f"/v1/sessions/{_p(session_id)}/actions", {"action": action})

    def active_session(self) -> dict | None:
        try:
            body = self.client.get(SESSION, "/v1/sessions/active")
        except ApiError as exc:
            if exc.status == 404:
                return None
            raise
        return body.get("session") if isinstance(body, dict) else None

    def sessions(self, start: str, end: str) -> list[dict]:
        out: list[dict] = []
        cursor: str | None = None
        while True:
            body = self.client.get(SESSION, "/v1/sessions", params={"from": start, "to": end, "cursor": cursor})
            out.extend(body.get("sessions") or [])
            cursor = body.get("cursor")
            if not cursor:
                return out
