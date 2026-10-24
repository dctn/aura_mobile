"""What each alert's button does, keyed by the alert's ``action.ref`` (see ``core/features.Action``).

Every action ends the same way: ``POST /v1/alerts/{id}/done``, so the alert leaves "What you need to do" and the
backend knows it was acted on. A cancelled dialog leaves the alert open.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from PyQt6.QtCore import QTimer
from PyQt6.QtWidgets import QDialog, QWidget

from aura_mobile.core.features import Action, feature_for_alert
from aura_mobile.services.sim.band import live_packets
from aura_mobile.ui import context as area
from aura_mobile.ui import prefs, runview, sheets
from aura_mobile.ui import tokens as t
from aura_mobile.ui.context import AppContext
from aura_mobile.ui.layers import Alert
from aura_mobile.ui.sheets import RatingDialog

log = logging.getLogger(__name__)

WATER_GLASS_ML = 250
#: BS-02: what Reconnect backfills, in minutes since the last sync
BACKFILL_MIN, BACKFILL_MAX = 30, 720


def now_local(ctx: AppContext) -> datetime:
    return datetime.now(ZoneInfo(ctx.api.time_zone))


def _hhmm_minus(value: str, minutes: int) -> str:
    try:
        hh, mm = (int(x) for x in value.split(":")[:2])
    except (ValueError, AttributeError):
        hh, mm = 23, 0
    total = (hh * 60 + mm - minutes) % (24 * 60)
    return f"{total // 60:02d}:{total % 60:02d}"


def _next_weekday(start: datetime, weekday: int) -> datetime:
    days = (weekday - start.weekday()) % 7 or 7
    return start + timedelta(days=days)


# -- reusable steps ----------------------------------------------------------------------------------
#
# Each step changes ``ctx.store`` first, so the screen shows the result at once, then sends the write with
# ``ctx.write``, which reloads that part from the server to confirm. ``then`` runs once the server has it.
# A step returns False when the person cancelled, so nothing was sent.


def _pending_id(kind: str) -> str:
    return f"pending-{kind}-{datetime.now().timestamp()}"


def _set_active(ctx: AppContext, session: dict | None) -> None:
    ctx.store.patch("active", lambda _old: session)


def _after(then: Callable[[], None] | None) -> Callable[[object], None]:
    def done(_result: object) -> None:
        if then is not None:
            then()

    return done


def start_session(ctx: AppContext, kind: str, mode: str, *, minutes: int | None, title: str,
                  then: Callable[[dict], None] | None = None) -> None:
    """Show the session running now; the server's answer replaces the placeholder (or is the one it joined)."""
    draft = {"id": _pending_id("session"), "kind": kind, "mode": mode, "title": title, "state": "starting",
             "plannedMinutes": minutes, "startedAt": now_local(ctx).isoformat()}
    _set_active(ctx, draft)

    def started(body: dict) -> None:
        body = body if isinstance(body, dict) else {}
        session = body.get("session")
        if isinstance(session, dict):
            _set_active(ctx, session)
        if then is not None:
            then(body)
        else:
            ctx.toast.emit("Joined the session already running" if body.get("joined") else "Session started")

    ctx.write(lambda: ctx.api.start_session(kind, mode, minutes=minutes, title=title), area.SESSIONS, started)


def session_action(ctx: AppContext, session_id: str, verb: str) -> None:
    """Pause, resume, stop or complete: the card changes now; the server confirms after."""
    if not session_id or session_id.startswith("pending-"):
        ctx.toast.emit("Still starting; try again in a moment.")
        return
    active = ctx.store.data.get("active")
    if isinstance(active, dict) and active.get("id") == session_id:
        if verb in ("stop", "complete"):
            ended = {**active, "state": "ended", "outcome": "completed" if verb == "complete" else "stopped"}
            _set_active(ctx, None)
            ctx.store.patch("sessions", lambda old: [ended, *[s for s in (old or []) if s.get("id") != session_id]])
        else:
            _set_active(ctx, {**active, "state": "paused" if verb == "pause" else "running"})
    ctx.write(lambda: ctx.api.session_action(session_id, verb), area.SESSIONS)


def run_timed_session(ctx: AppContext, parent: QWidget, title: str, minutes: int, *, kind: str = "recovery",
                      note: str = "", accent: str = t.RECOVERY, breathing: bool = False,
                      then: Callable[[], None] | None = None, dialog: QWidget | None = None,
                      after: Callable[[str], None] | None = None, night: bool | None = None,
                      mode: str = "timer") -> None:
    """Start a Session (``mode: timer`` unless Search's modes say otherwise) and open the run view over the phone;
    returns at once. ``minutes`` 0 is open-ended (Flowtime): the clock counts up and no length is sent.

    When the time is up (or the person marks it done) the session is ``complete``d and ``then`` runs; End stops it
    (``stop``) and ``then`` does not run. ``dialog`` is a panel shown inside the run view (the wind-down's to-do
    list); ``after`` gets the session id once a completed session is known to the server (DX-07 then shows the
    stress change). ``night`` (default: a nap) shows the black night view.
    """
    window = runview.phone_of(parent)
    running = runview.current(window)
    if running is not None:
        running.expand()
        ctx.toast.emit(f"{running.title_text} is still running; end it first.")
        return
    # The countdown may end before the start call answers; whichever comes second sends the outcome.
    state: dict[str, str] = {}

    def settle() -> None:
        session_id, verb = state.get("id"), state.get("verb")
        if session_id and verb:
            state.pop("verb")
            session_action(ctx, session_id, verb)
            if verb == "complete" and after is not None:
                after(session_id)

    def started(body: dict) -> None:
        state["id"] = str((body.get("session") or {}).get("id") or "")
        if body.get("joined"):
            ctx.toast.emit("A session was already running; this timer joins it.")
        settle()

    def finished() -> None:
        state["verb"] = "complete"
        settle()
        if then is not None:
            then()

    def cancelled() -> None:
        state["verb"] = "stop"
        settle()

    start_session(ctx, kind, mode, minutes=minutes or None, title=title, then=started)
    if night is None:
        night = "nap" in title.lower()
    runview.start(parent, title, minutes, note=str(getattr(dialog, "note", "") or note), accent=accent, kind=kind,
                  breathing=breathing, night=night, panel=dialog, on_finish=finished, on_cancel=cancelled)


def ask_checkin(ctx: AppContext, parent: QWidget, kind: str, then: Callable[[], None] | None = None) -> bool:
    question = "How is your mood right now?" if kind == "mood" else "How connected to people have you felt lately?"
    dialog = RatingDialog(parent, kind, question)
    if dialog.exec() != QDialog.DialogCode.Accepted or dialog.value is None:
        return False
    return save_checkin(ctx, parent, kind, dialog.value, then)


def save_checkin(ctx: AppContext, parent: QWidget, kind: str, value: int,
                 then: Callable[[], None] | None = None) -> bool:
    """A mood or connection check-in of 1-5: a tapped face on Search → Logging, or the rating sheet."""
    taken_at = now_local(ctx)
    row = {"id": _pending_id(kind), "kind": kind, kind: value, "takenAt": taken_at.isoformat()}
    ctx.store.patch("checkins", lambda old: [*(old or []), row])
    ctx.toast.emit(f"Saved: {kind} {value}/5")
    ctx.write(lambda: ctx.api.post_checkin(kind, taken_at=taken_at, **{kind: value}), area.CHECKINS, _after(then))
    week_low = kind == "mood" and mood_low_this_week(ctx.store.data.get("checkins"), taken_at)
    if kind == "mood" and (value <= 2 or week_low):
        help_sheet = sheets.HelpDialog(parent, week_low=week_low)
        if help_sheet.exec() == QDialog.DialogCode.Accepted and help_sheet.reach_out.isChecked():
            add_tasks(ctx, ["Reach out to someone"])
    return True


def mood_low_this_week(checkins: object, now: datetime) -> bool:
    """NI-02: three or more mood check-ins in the last 7 days, averaging 2 or less."""
    since = (now - timedelta(days=7)).isoformat()
    moods = [int(c["mood"]) for c in (checkins if isinstance(checkins, list) else [])
             if c.get("kind") == "mood" and isinstance(c.get("mood"), int | float)
             and str(c.get("takenAt") or "") >= since[:10]]
    return len(moods) >= 3 and sum(moods) / len(moods) <= 2


def log_water(ctx: AppContext, ml: int = WATER_GLASS_ML, then: Callable[[], None] | None = None) -> bool:
    taken_at = now_local(ctx)
    row = {"id": _pending_id("water"), "type": "water", "date": taken_at.date().isoformat(),
           "takenAt": taken_at.isoformat(), "data": {"ml": ml}}
    ctx.store.patch("logs", lambda old: [*(old or []), row])
    ctx.toast.emit(f"Logged {ml} ml of water")
    ctx.write(lambda: ctx.api.log_water(ml, taken_at=taken_at), area.CHECKINS, _after(then))
    return True


def add_events(ctx: AppContext, events: list[tuple[str, datetime, int]],
               then: Callable[[], None] | None = None) -> bool:
    drafts = [{"id": _pending_id("event"), "title": title, "start": start.isoformat(),
               "end": (start + timedelta(minutes=minutes)).isoformat()} for title, start, minutes in events]
    ctx.store.patch("events", lambda old: [*(old or []), *drafts])
    ctx.toast.emit(f"Added {len(events)} to your calendar")

    def send() -> int:
        for title, start, minutes in events:
            ctx.api.create_event(title, start, start + timedelta(minutes=minutes))
        return len(events)

    ctx.write(send, area.CALENDAR, _after(then))
    return True


def last_sync(ctx: AppContext) -> datetime | None:
    """BS-02: when this phone last sent band data for the signed-in person (kept in prefs, not synced)."""
    try:
        return datetime.fromisoformat(str(prefs.load().get(f"lastSync:{ctx.sub}")))
    except ValueError:
        return None


def mark_synced(ctx: AppContext, at: datetime | None = None) -> None:
    prefs.save({f"lastSync:{ctx.sub}": (at or now_local(ctx)).isoformat()})


def backfill_minutes(last: datetime | None, now: datetime) -> int:
    """Minutes the band kept offline since ``last``, clamped to what a band holds (30 min to 12 h)."""
    if last is None:
        return BACKFILL_MIN
    gap = int((now - last).total_seconds() // 60)
    return max(BACKFILL_MIN, min(BACKFILL_MAX, gap))


def reconnect_band(ctx: AppContext, then: Callable[[], None] | None = None) -> bool:
    """BS-02: the band sends what it kept offline since the last sync (30 min to 12 h), then today is fresh again."""
    minutes = backfill_minutes(last_sync(ctx), now_local(ctx))
    ctx.toast.emit(f"Band reconnecting… sending {minutes} minutes")

    def send() -> int:
        packets = live_packets(ctx.api.time_zone, minutes=minutes)
        for i in range(0, len(packets), 40):
            ctx.api.post_samples(packets[i : i + 40])
        return len(packets)

    def done(count: int) -> None:
        mark_synced(ctx)
        ctx.toast.emit(f"Band reconnected · {count} minutes synced")
        if then is not None:
            then()

    ctx.write(send, area.METRICS, done)
    return True


def update_goal(ctx: AppContext, key: str, value: str, then: Callable[[], None] | None = None) -> bool:
    """Change one health goal. The version on screen goes with the write, so it needs no read first."""
    me = ctx.store.data.get("me")
    me = me if isinstance(me, dict) else ctx.me
    version = int(me["version"]) if me.get("version") is not None else None

    def merged(old: dict | None) -> dict:
        old = old if isinstance(old, dict) else {}
        return {**old, "healthGoals": {**(old.get("healthGoals") or {}), key: value}}

    ctx.store.patch("me", merged)
    ctx.toast.emit(f"Saved: {key} {value}")

    def saved(body: dict) -> None:
        if isinstance(body, dict) and body:
            ctx.store.patch("me", lambda _old: body)
        if then is not None:
            then()

    ctx.write(lambda: ctx.api.update_profile_fresh({"healthGoals": {key: value}}, version), area.PROFILE, saved)
    return True


def add_tasks(ctx: AppContext, titles: list[str], *, horizon: str = "today",
              then: Callable[[], None] | None = None) -> bool:
    if not titles:
        return False
    drafts = [{"id": _pending_id("task"), "title": title, "status": "open", "horizon": horizon} for title in titles]
    ctx.store.patch("tasks", lambda old: [*(old or []), *drafts])
    ctx.toast.emit(f"Added to your tasks: {titles[0]}" if len(titles) == 1 else f"Added {len(titles)} tasks")

    def send() -> int:
        for title in titles:
            ctx.api.create_task(title, horizon=horizon)
        return len(titles)

    ctx.write(send, area.TASKS, _after(then))
    return True


def _shift_day(value: object, days: int) -> object:
    try:
        return (datetime.fromisoformat(str(value)) + timedelta(days=days)).isoformat()
    except ValueError:
        return value


def move_events(ctx: AppContext, event_ids: list[str], then: Callable[[], None] | None = None) -> bool:
    """DX-05: hard training to tomorrow, same time when free (the server picks the slot; the reload shows it)."""
    if not event_ids:
        return False
    ids = set(event_ids)
    ctx.store.patch("events", lambda old: [
        {**e, "start": _shift_day(e.get("start"), 1), "end": _shift_day(e.get("end"), 1)} if e.get("id") in ids else e
        for e in (old or [])])
    ctx.toast.emit(f"Moved {len(ids)} to tomorrow")

    def send() -> int:
        for event_id in event_ids:
            ctx.api.move_event(event_id, to="tomorrow")
        return len(event_ids)

    ctx.write(send, area.CALENDAR, _after(then))
    return True


def cancel_events(ctx: AppContext, event_ids: list[str], then: Callable[[], None] | None = None) -> bool:
    """DX-06: hard training comes off the calendar."""
    if not event_ids:
        return False
    ids = set(event_ids)
    ctx.store.patch("events", lambda old: [e for e in (old or []) if e.get("id") not in ids])
    ctx.toast.emit(f"Cancelled {len(ids)} training session{'s' if len(ids) > 1 else ''}")

    def send() -> int:
        for event_id in event_ids:
            ctx.api.delete_event(event_id)
        return len(event_ids)

    ctx.write(send, area.CALENDAR, _after(then))
    return True


def sleep_check(ctx: AppContext, parent: QWidget, then: Callable[[], None] | None = None) -> bool:
    """DX-03: the Insomnia Severity Index, sent as an ``insomnia`` check-in; the score shows at once."""
    dialog = sheets.SleepCheckDialog(parent)
    if dialog.exec() != QDialog.DialogCode.Accepted:
        return False
    answers = dialog.values()
    score = sum(answers)
    taken_at = now_local(ctx)
    row = {"id": _pending_id("insomnia"), "kind": "insomnia", "insomnia": answers, "insomniaScore": score,
           "takenAt": taken_at.isoformat()}
    ctx.store.patch("checkins", lambda old: [*(old or []), row])
    ctx.write(lambda: ctx.api.post_checkin("insomnia", taken_at=taken_at, insomnia=answers), area.CHECKINS,
              _after(then))
    meaning = sheets.SleepCheckDialog.meaning(score)
    Alert.tell(parent, "Your sleep check", f"Score {score} of 28.\n\n{meaning}")
    return True


def alert_weeks(alerts: object, feature_id: str) -> int:
    """How many ISO weeks ``feature_id`` was raised in, over the alerts on hand (the last 40 days)."""
    weeks = set()
    for a in alerts if isinstance(alerts, list) else []:
        if a.get("featureId") == feature_id:
            try:
                weeks.add(date.fromisoformat(str(a.get("date"))[:10]).isocalendar()[:2])
            except ValueError:
                continue
    return len(weeks)


def wind_down(ctx: AppContext, parent: QWidget, accent: str, then: Callable[[], None] | None = None) -> None:
    """DX-03: ten quiet minutes; what is on your mind goes on the task list. After 3 weeks, offer the sleep check.

    Non-blocking: the list is saved, and the sleep check offered, once the ten minutes are done (End saves nothing).
    """
    panel = sheets.WindDownDialog(None, accent)

    def finished() -> None:
        if then is not None:
            then()
        add_tasks(ctx, panel.items(), horizon="week")
        if alert_weeks(ctx.store.data.get("alerts"), "DX-03") >= 3 and sheets.confirm(
            parent, "Sleep check",
            "Trouble falling asleep has come up for 3 weeks now. A 2-minute sleep check shows how much it affects "
            "you.",
            "Take the sleep check",
        ):
            sleep_check(ctx, parent)

    run_timed_session(ctx, parent, "Wind-down", 10, accent=accent, dialog=panel, then=finished)


def light_times(now: datetime, wake: str, days: int = 3) -> list[tuple[str, datetime, int]]:
    """PB-01: morning light 30 min after waking (07:30 at the earliest, 09:30 at the latest) and screens dimmed at
    21:00, for the next ``days`` days."""
    try:
        hh, mm = (int(x) for x in wake.split(":")[:2])
    except (ValueError, AttributeError):
        hh, mm = 7, 0
    minutes = min(max(hh * 60 + mm + 30, 7 * 60 + 30), 9 * 60 + 30)
    out: list[tuple[str, datetime, int]] = []
    for i in range(days):
        day = now.date() + timedelta(days=i)
        if i > 0 or now.hour * 60 + now.minute < minutes:
            morning = datetime.combine(day, time(minutes // 60, minutes % 60), tzinfo=now.tzinfo)
            out.append(("Outdoor light · 15 min", morning, 15))
        out.append(("Dim screens", datetime.combine(day, time(21), tzinfo=now.tzinfo), 30))
    return out


def log_strength(ctx: AppContext, parent: QWidget, then: Callable[[], None] | None = None, *,
                 minutes: int | None = None) -> bool:
    """PB-04: a strength session logged by hand (the band can't tell lifting from sitting). Without ``minutes``, a
    sheet asks."""
    if minutes is None:
        minutes = sheets.ask_minutes(parent, "Log a strength session", 30)
    if minutes is None:
        return False
    taken_at = now_local(ctx)
    row = {"id": _pending_id("strength"), "type": "strength", "date": taken_at.date().isoformat(),
           "takenAt": taken_at.isoformat(), "data": {"minutes": minutes}}
    ctx.store.patch("logs", lambda old: [*(old or []), row])
    ctx.toast.emit(f"Logged {minutes} min of strength")
    ctx.write(lambda: ctx.api.post_log("strength", {"minutes": minutes}, taken_at=taken_at), area.CHECKINS,
              _after(then))
    return True


def stress_change_text(trace: dict) -> str:
    """DX-07: the stress before a recovery session against its last 10 minutes, from the session trace."""
    drop = trace.get("stressDrop") if isinstance(trace, dict) else None
    status = trace.get("status") if isinstance(trace, dict) else None
    if isinstance(drop, int | float):
        if drop > 0:
            return f"Stress fell {drop:.0f} points: the 15 min before against the last 10 min of the session."
        return "Stress didn't drop this time. Slow breathing works better with practice; try it again tomorrow."
    if status == "noData":
        return "No band data for this session, so there is no stress change to show."
    return "The stress change shows once today's data is computed (overnight). Find it under Search → sessions."


def show_stress_change(ctx: AppContext, session_id: str) -> None:
    """After a breathing session: ask for its trace a moment later (the complete call goes first)."""
    def ask() -> None:
        ctx.call(lambda: ctx.api.session_trace(session_id), lambda trace: ctx.toast.emit(stress_change_text(trace)),
                 lambda _exc: None)

    QTimer.singleShot(2000, ask)


def connection_plan(ctx: AppContext, parent: QWidget, then: Callable[[], None] | None = None) -> bool:
    """NI-03: call someone today and/or plan a meal this week, then log how connected you feel."""
    now = now_local(ctx)
    dialog = sheets.ConnectionDialog(parent, now.date())
    if dialog.exec() != QDialog.DialogCode.Accepted:
        return False
    who = dialog.who()
    planned = False
    if dialog.call.isChecked():
        planned = add_tasks(ctx, [f"Call {who}"])
    if dialog.meal.isChecked():
        planned = add_events(ctx, [(f"Meal with {who}", dialog.meal_start(now.tzinfo), 90)]) or planned
    if not planned:
        ctx.toast.emit("Nothing planned; tick a call or a meal next time.")
    ask_checkin(ctx, parent, "connection")
    if then is not None:
        then()
    return True


def health_check(ctx: AppContext, parent: QWidget, alert: dict, then: Callable[[], None] | None = None) -> bool:
    """PB-05: the signs, and a task to book a doctor's visit."""
    body = str(alert.get("body") or "These signs are worth checking with a doctor.")
    text = f"{body}\n\nBook a visit and show the doctor your Trends screen."
    if not sheets.confirm(parent, str(alert.get("title") or "Worth a check-up"), text, "Add 'Book a doctor visit'"):
        return False
    return add_tasks(ctx, ["Book a doctor visit"], horizon="week", then=then)


def easy_day(ctx: AppContext, parent: QWidget, accent: str, *, ill: bool,
             then: Callable[[], None] | None = None) -> bool:
    """DX-05 moves today's and tomorrow's hard training to the next day, then a recovery break.
    DX-06 cancels it and can set bedtime 30 min earlier."""
    now = now_local(ctx)
    events = sheets.hard_training(ctx.store.data.get("events") or [], now.date())
    goals = health_goals(ctx)
    bedtime = _hhmm_minus(str(goals.get("bedtime") or "23:00"), 30) if ill else None
    dialog = sheets.EasyDayDialog(parent, events, ill=ill, bedtime=bedtime)
    if dialog.exec() != QDialog.DialogCode.Accepted:
        return False
    chosen = dialog.chosen()
    if ill:
        cancel_events(ctx, chosen)
        if bedtime and dialog.wants_early_night():
            update_goal(ctx, "bedtime", bedtime)
        if then is not None:
            then()
        return True
    move_events(ctx, chosen)
    if then is not None:
        then()  # the easy day is planned; the break itself is optional
    run_timed_session(ctx, parent, "Recovery break", 10, accent=accent,
                      note="Ten quiet minutes: sit or lie down, breathe slowly.")
    return True


def health_goals(ctx: AppContext) -> dict:
    me = ctx.store.data.get("me")
    me = me if isinstance(me, dict) else ctx.me
    return (me.get("healthGoals") or {}) if isinstance(me, dict) else {}


def pick_time(parent: QWidget, title: str, initial: str) -> str | None:
    return sheets.pick_time(parent, title, initial)


def hide_alert(ctx: AppContext, alert: dict) -> None:
    """Take the alert off "What you need to do" now; :func:`mark_done` tells the server."""
    alert_id = alert.get("id")
    ctx.store.patch("alerts", lambda old: [{**a, "done": True} if a.get("id") == alert_id else a
                                           for a in (old or [])])


def mark_done(ctx: AppContext, alert: dict) -> None:
    alert_id = str(alert.get("id") or "")
    if not alert_id:
        return
    hide_alert(ctx, alert)
    ctx.write(lambda: ctx.api.alert_done(alert_id), area.ALERTS)


# -- the dispatcher ------------------------------------------------------------------------------------


def perform(ctx: AppContext, parent: QWidget, alert: dict) -> None:
    """Do what the alert asks, then mark it done. The alert leaves the list as soon as the action is sent."""
    feature = feature_for_alert(alert)
    action = alert.get("action") if isinstance(alert.get("action"), dict) else None
    ref = str((action or {}).get("ref") or (feature.action.value if feature else ""))
    accent = t.ACCENTS.get(feature.accent, t.BLUE) if feature else t.BLUE
    goals = health_goals(ctx)
    now = now_local(ctx)

    def done() -> None:
        mark_done(ctx, alert)

    sent = False

    if ref == Action.BEDTIME:
        current = str(goals.get("bedtime") or "23:00")
        sent = update_goal(ctx, "bedtime", _hhmm_minus(current, 30), done)
    elif ref == Action.WAKE_ANCHOR:
        chosen = pick_time(parent, "One wake time for every day", str(goals.get("wakeTime") or "07:00"))
        if chosen:
            sent = update_goal(ctx, "wakeTime", chosen, done)
    elif ref == Action.WIND_DOWN:
        # timed actions don't block: ``done`` marks the alert done (and hides it) once the time is up
        wind_down(ctx, parent, accent, done)
    elif ref == Action.NAP:
        run_timed_session(ctx, parent, "Nap", 10, accent=accent, then=done, night=True,
                          note="Lie down, eyes closed. A gentle alarm ends it.")
    elif ref == Action.RECOVERY:
        sent = easy_day(ctx, parent, accent, ill=False, then=done)
    elif ref == Action.REST or (feature is not None and feature.id == "DX-06"):
        sent = easy_day(ctx, parent, accent, ill=True, then=done)
    elif ref == Action.BREATHING:
        run_timed_session(ctx, parent, "Slow breathing", 5, accent=accent, breathing=True, then=done,
                          after=lambda session_id: show_stress_change(ctx, session_id))
    elif ref == Action.MOVE:
        run_timed_session(ctx, parent, "Move break", 3, kind="walk", accent=accent, then=done,
                          note="Stand up, walk around, stretch.")
    elif ref == Action.ADD_WALKS:
        sat = _next_weekday(now, 5).replace(hour=9, minute=0, second=0, microsecond=0)
        sent = add_events(ctx, [("Walk · 30 min", sat, 30), ("Walk · 30 min", sat + timedelta(days=1), 30)], done)
    elif ref == Action.LIGHT:
        sent = add_events(ctx, light_times(now, str(goals.get("wakeTime") or "07:00")), done)
    elif ref == Action.SCORES:
        start = datetime.combine(now.date() + timedelta(days=1), time(9), tzinfo=now.tzinfo)

        def then_mood() -> None:
            done()
            ask_checkin(ctx, parent, "mood")

        sent = add_events(ctx, [("Recovery day: keep it light", start, 60)], then_mood)
    elif ref == Action.ADD_SESSIONS:
        base = datetime.combine(now.date(), time(18), tzinfo=now.tzinfo)
        sent = add_events(ctx, [("Strength · 40 min", base + timedelta(days=1), 40),
                                ("Brisk session · 30 min", base + timedelta(days=3), 30),
                                ("Strength · 40 min", base + timedelta(days=5), 40)], done)
    elif ref == Action.MOOD:
        sent = ask_checkin(ctx, parent, "mood", done)
    elif ref == Action.CONNECTION:
        sent = connection_plan(ctx, parent, done)
    elif ref == Action.DOCTOR:
        sent = health_check(ctx, parent, alert, done)
    elif ref == Action.WATER:
        sent = log_water(ctx, WATER_GLASS_ML, done)
    elif ref == Action.RECONNECT:
        sent = reconnect_band(ctx, done)
    else:
        # anything new: an acknowledgement.
        body = str(alert.get("body") or (feature.does if feature else ""))
        Alert.tell(parent, str(alert.get("title") or "Aura"), body)
        done()
    if sent:
        hide_alert(ctx, alert)  # at once; done() tells the server when the action has gone through
