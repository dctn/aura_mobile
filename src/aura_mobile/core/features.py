"""The 18 mobile features as data: what the person sees, what they can do, and how the Test lab drives each one.

The copy comes from the Zoho "mobile features" tab ("What the user sees" / "What they can do"); the rule, the
action ``ref`` and the gates come from ``services/health-data/src/service/mobile-alerts/rules/*.ts``. The backend's
alert carries its own title and body. ``shows`` is only the example copy for screens that have no alert yet.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum


class Action(StrEnum):
    """What tapping an alert's button does in the app. The value is the alert's ``action.ref``."""

    BEDTIME = "bedtime"  # DX-01: earlier bedtime → Identity healthGoals.bedtime
    WAKE_ANCHOR = "settings"  # DX-02: one wake time → Identity healthGoals.wakeTime
    WIND_DOWN = "wind-down"  # DX-03: 10-min wind-down with a to-do dump; the sleep check after 3 weeks
    NAP = "nap"  # DX-04: 10-min nap timer
    RECOVERY = "recovery"  # DX-05: move hard training to tomorrow, then a recovery break
    REST = "rest"  # DX-06: cancel hard training, bed 30 min earlier (the backend sends no action)
    BREATHING = "breathing"  # DX-07: 5-min slow breathing, then the stress change
    ADD_WALKS = "add-walks"  # DX-09: two 30-min walks → Calendar
    LIGHT = "light"  # PB-01: 3 mornings of outdoor light + dim screens at 21:00 → Calendar
    SCORES = "scores"  # PB-02: plan a recovery day → Calendar, then a mood check
    MOVE = "move"  # PB-03: 3-min move timer
    ADD_SESSIONS = "add-sessions"  # PB-04: 2 strength + 1 brisk → Calendar
    DOCTOR = "trends"  # PB-05: a task to book a doctor's visit
    MOOD = "checkin/mood"  # NI-02: mood check; help options when low
    CONNECTION = "checkin/connection"  # NI-03: plan a call or a meal, then the connection check-in
    WATER = "water"  # NI-04: log a glass (250 ml)
    RECONNECT = "reconnect"  # BS-02: sync the band again
    ACCOUNT = "account"  # BS-01: the Profile screen


class LabMode(StrEnum):
    """How the Test lab makes a feature fire."""

    BAND = "band"  # post synthetic band days; the day's own computation raises it
    CHECKIN = "checkin"  # post check-ins, then a band day so the rules run
    QUIET_DAY = "quiet"  # compute a day with no data after a quiet day
    TIMER = "timer"  # rides a timer booked after a final day; fires on its own about 2 h later
    ACCOUNT = "account"  # not an alert: exercised from Profile


@dataclass(frozen=True)
class Feature:
    id: str
    name: str
    group: str
    accent: str  # a colour token name in ui/tokens.py
    shows: str
    does: str
    action: Action
    action_label: str
    rule: str
    lab: LabMode
    #: "direct" crosses a near-raw threshold reliably; "derived" pushes a composite score whose value depends on
    #: the deployed pipeline and the account's history, so it may need a second run.
    reliability: str = "direct"

    @property
    def has_alert(self) -> bool:
        return self.lab is not LabMode.ACCOUNT

    @property
    def reason(self) -> str:
        """Two or three words under the pill's title ("Short night")."""
        return SHORT[self.id][0]

    @property
    def verb(self) -> str:
        """The one-word green button ("Nap")."""
        return SHORT[self.id][1]

    @property
    def metric(self) -> str:
        """The daily metric column the feature reads, for the card under its pill ("" when none)."""
        return SHORT[self.id][2]

    @property
    def icon(self) -> str:
        """The glyph name in ``ui/icons.py``."""
        return SHORT[self.id][3]


FEATURES: tuple[Feature, ...] = (
    Feature("DX-01", "Sleep-debt payback", "Sleep", "sleep",
            "You're 4 h short on sleep this fortnight.",
            "Go to bed 30 min earlier tonight. Tap to set the reminder.",
            Action.BEDTIME, "Set an earlier bedtime", "sleepDebtMin14 ≥ 120", LabMode.BAND, "derived"),
    Feature("DX-02", "Anchor wake time", "Sleep", "sleep",
            "Your wake time moved 2 h this week.",
            "Pick one wake time for every day. Tap to set the alarm.",
            Action.WAKE_ANCHOR, "Pick one wake time", "Monday, final run: wakeRegularityMin ≥ 120",
            LabMode.BAND, "derived"),
    Feature("DX-03", "Better-nights plan", "Sleep", "sleep",
            "It took you 40 min to fall asleep on 4 nights.",
            "Start tonight's 10-min wind-down with a to-do list. After 3 weeks, take the sleep check.",
            Action.WIND_DOWN, "Start the wind-down", "sleepLatencyMin > 30 on 4+ nights", LabMode.BAND, "derived"),
    Feature("DX-04", "Short-night nap", "Sleep", "sleep",
            "Short night (5 h 20 m). A 10-min nap before 3 pm will help.",
            "Tap to start a 10-min nap timer with a gentle alarm.",
            Action.NAP, "Start a 10-min nap", "totalSleepMin < 360", LabMode.BAND),
    Feature("DX-05", "Easy-day call", "Recovery & stress", "recovery",
            "Your body is under-recovered today.",
            "Make it an easy day: move hard training and start one recovery break.",
            Action.RECOVERY, "Start a recovery break", "recoveryScore ≤ 40, or HRV low and resting HR +5",
            LabMode.BAND, "derived"),
    Feature("DX-06", "Getting-ill warning", "Recovery & stress", "cardiac",
            "Resting HR up and HRV down 2 days in a row. You may be getting ill.",
            "Take it easy: cancel hard training and go to bed early tonight.",
            Action.REST, "Take it easy", "resting HR +5, HRV low, skin temp +0.3 °C", LabMode.BAND, "derived"),
    Feature("DX-07", "Stress reset", "Recovery & stress", "stress",
            "You've been in high stress for 90 min.",
            "Do 5 min of slow breathing. Tap to start.",
            Action.BREATHING, "Breathe for 5 min", "stressDurationMin ≥ 90 (or the stress timer)",
            LabMode.BAND, "derived"),
    Feature("DX-09", "Weekly movement target", "Movement", "fitness",
            "62 of 150 active minutes so far this week.",
            "Add two 30-min walks this weekend. Tap to put them in your week.",
            Action.ADD_WALKS, "Add two walks", "Thursday, final run: weeklyActiveMinutes < 150", LabMode.BAND),
    Feature("PB-01", "Body-clock reset", "Sleep", "focus",
            "Your rhythm has shifted 1 h later this week.",
            "Get 15 min of outdoor light before 10 am and dim screens after 9 pm.",
            Action.LIGHT, "Plan morning light", "final run: circadianAlignmentPct ≤ 60 or jetLagHours ≥ 1",
            LabMode.BAND, "derived"),
    Feature("PB-02", "Burnout early warning", "Burnout", "workout",
            "Your burnout risk has risen 3 weeks in a row.",
            "Plan one recovery day this week; take a short mood check.",
            Action.SCORES, "Plan a recovery day", "Monday, final run: burnout high and rising",
            LabMode.BAND, "derived"),
    Feature("PB-03", "Sitting breaker", "Movement", "fitness",
            "You've been sitting for 75 min.",
            "Stand up and move for 3 min. Tap to start the timer.",
            Action.MOVE, "Move for 3 min", "longestSittingBoutMin ≥ 75 (or the sitting timer)", LabMode.BAND),
    Feature("PB-04", "Fitness keeper", "Movement", "fitness",
            "Your fitness dropped 2 points this month.",
            "Plan 2 strength sessions and 1 brisk session this week.",
            Action.ADD_SESSIONS, "Plan 3 sessions", "final run, 20+ days: VO2max down ≥ 2", LabMode.BAND, "derived"),
    Feature("PB-05", "Health-check flag", "Health check", "cardiac",
            "These signs are worth checking with a doctor.",
            "Book a visit: tap to add it to your tasks.",
            Action.DOCTOR, "Book a check-up", "final run: SpO2 dips ≥ 10 on 7+ nights and breathing disturbances ≥ 5",
            LabMode.BAND, "derived"),
    Feature("NI-02", "Mood check + help", "Mood", "sleep",
            "Your mood has been low most of this week.",
            "Take a 2-min mood check. It shows help options if needed.",
            Action.MOOD, "Check in on mood", "3+ mood check-ins averaging ≤ 2", LabMode.CHECKIN),
    Feature("NI-03", "Connection nudge", "Connection", "teal",
            "You've had little real contact this fortnight.",
            "Call a friend or plan a meal with someone this week.",
            Action.CONNECTION, "Log connection", "1st/15th, final run: < 2 connection check-ins or average ≤ 2",
            LabMode.BAND),
    Feature("NI-04", "Hydration reminder", "Hydration", "blue",
            "Only 2 glasses so far today. Time for some water.",
            "Drink a glass and tap 'done' to log it.",
            Action.WATER, "Log a glass", "hydration timer (2 h after a final day): water behind the goal",
            LabMode.TIMER),
    Feature("BS-01", "User identity & data", "Account", "blue",
            "Finish your profile so your scores are accurate.",
            "Sign up, sign in, edit profile, export or delete data, all in one place.",
            Action.ACCOUNT, "Open profile", "Identity: profile, consent, export, delete", LabMode.ACCOUNT),
    Feature("BS-02", "Band connection & sync", "Band", "workout",
            "Your band hasn't synced since yesterday.",
            "Tap to reconnect; data saved offline is sent automatically.",
            Action.RECONNECT, "Reconnect the band", "day not computed and no fresh day before it",
            LabMode.QUIET_DAY),
)

#: id -> (reason, verb, metric column, icon): the short copy of the simple screens
SHORT: dict[str, tuple[str, str, str, str]] = {
    "DX-01": ("Sleep debt", "Bedtime", "sleepDebtMin14", "alarm"),
    "DX-02": ("Wake time drifts", "Set alarm", "wakeRegularityMin", "sunrise"),
    "DX-03": ("Slow to sleep", "Wind down", "sleepLatencyMin", "evening"),
    "DX-04": ("Short night", "Nap", "totalSleepMin", "sleep"),
    "DX-05": ("Low recovery", "Recover", "recoveryScore", "recover"),
    "DX-06": ("Getting ill?", "Rest", "restingHrBpm", "heart"),
    "DX-07": ("High stress", "Breathe", "stressDurationMin", "wind"),
    "DX-09": ("Behind on movement", "Add walks", "weeklyActiveMinutes", "walk"),
    "PB-01": ("Body clock late", "Plan light", "circadianAlignmentPct", "sun"),
    "PB-02": ("Burnout rising", "Plan rest", "burnoutIndexScore", "flame"),
    "PB-03": ("Sitting too long", "Move", "longestSittingBoutMin", "move"),
    "PB-04": ("Fitness dropping", "Plan", "vo2max", "dumbbell"),
    "PB-05": ("Worth a check", "Book", "spo2DipEvents", "shield"),
    "NI-02": ("Low mood", "Check in", "", "bubble"),
    "NI-03": ("Little contact", "Log", "", "people"),
    "NI-04": ("Drink water", "Drink", "", "drop"),
    "BS-01": ("Finish profile", "Open", "", "me"),
    "BS-02": ("Not synced", "Reconnect", "", "band"),
}

BY_ID: dict[str, Feature] = {f.id: f for f in FEATURES}
BY_ACTION: dict[str, Feature] = {f.action.value: f for f in FEATURES}
ALERT_FEATURES: tuple[Feature, ...] = tuple(f for f in FEATURES if f.has_alert)


def feature_for_alert(alert: dict) -> Feature | None:
    """The feature behind an alert: by ``featureId``, else by its action's ``ref``."""
    found = BY_ID.get(str(alert.get("featureId") or ""))
    if found is not None:
        return found
    action = alert.get("action")
    if isinstance(action, dict):
        return BY_ACTION.get(str(action.get("ref") or ""))
    return None
