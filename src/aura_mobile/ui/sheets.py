"""The sheets behind the richer alert actions: what the person picks before ``actions`` sends anything.

Each one only collects choices; :mod:`aura_mobile.ui.actions` patches the store and sends the writes. They are
bottom sheets and alerts on the phone (:mod:`aura_mobile.ui.layers`), and ``exec()`` still answers synchronously.
- :class:`WindDownDialog` (DX-03): the to-do list for tomorrow, shown inside the wind-down's run view.
- :class:`SleepCheckDialog` (DX-03): the 7-question Insomnia Severity Index, one question a step.
- :class:`EasyDayDialog` (DX-05, DX-06): the hard training in the next two days, to move or cancel.
- :class:`HelpDialog` (NI-02): help options after a low mood.
- :class:`ConnectionDialog` (NI-03): call someone today or plan a meal this week.
- :class:`RatingDialog` (NI-02, NI-03): a 1-5 check-in.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta
from typing import ClassVar

from PyQt6.QtCore import QTime
from PyQt6.QtWidgets import QComboBox, QDialog, QLineEdit, QPlainTextEdit, QVBoxLayout, QWidget

from aura_mobile.ui import kit, layers
from aura_mobile.ui import tokens as t
from aura_mobile.ui.widgets import label, set_shown

#: words that make a calendar event "hard training" (DX-05 moves it, DX-06 cancels it)
TRAINING_WORDS = ("gym", "training", "workout", "run", "strength", "hiit", "interval", "brisk", "spin", "crossfit",
                  "tempo", "lift", "match")


def hard_training(events: list[dict], today: date, days: int = 2) -> list[dict]:
    """Saved events from ``today`` on, for ``days`` days, whose title sounds like hard training."""
    last = today + timedelta(days=days - 1)
    out = []
    for event in events:
        title = str(event.get("title") or "").lower()
        event_id = str(event.get("id") or "")
        day = str(event.get("date") or str(event.get("start") or "")[:10])
        if not event_id or event_id.startswith("pending-") or not day:
            continue
        if today.isoformat() <= day <= last.isoformat() and any(w in title for w in TRAINING_WORDS):
            out.append(event)
    return sorted(out, key=lambda e: str(e.get("start")))


def _intro(text: str) -> QWidget:
    w = label(text, "muted", wrap=True)
    w.setContentsMargins(4, 0, 4, 0)
    return w


def _when(event: dict) -> str:
    try:
        start = datetime.fromisoformat(str(event.get("start")))
    except ValueError:
        return str(event.get("start") or "")[:16].replace("T", " ")
    return start.strftime("%a %H:%M")


class WindDownDialog(QWidget):
    """DX-03: what is on your mind goes on the list, so it leaves your head. Shown inside the wind-down run view
    (``actions.wind_down``); :meth:`items` is read when the ten minutes are done."""

    def __init__(self, parent: QWidget | None, accent: str = t.SLEEP) -> None:
        super().__init__(parent)
        self.accent = accent
        self.note = "Dim the lights. Write down what is on your mind for tomorrow."
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        group = kit.Group("For tomorrow", "One thing a line; they go on your task list for the week.")
        self.todo = QPlainTextEdit()
        self.todo.setProperty("class", "plain")
        self.todo.setPlaceholderText("Reply to Ana\nBook the dentist")
        self.todo.setFixedHeight(112)
        group.add(self.todo)
        lay.addWidget(group)

    def items(self) -> list[str]:
        return [line.strip() for line in self.todo.toPlainText().splitlines() if line.strip()][:10]


class SleepCheckDialog(layers.Sheet):
    """The Insomnia Severity Index (Bastien 2001): 7 questions, 0-4 each, about the last two weeks. One question a
    step, "1 of 7" in the header; Next moves on, Back goes back, the last step shows "See result"."""

    SEVERITY = ("None", "Mild", "Moderate", "Severe", "Very severe")
    QUESTIONS: ClassVar[tuple[tuple[str, tuple[str, ...]], ...]] = (
        ("Trouble falling asleep", SEVERITY),
        ("Trouble staying asleep", SEVERITY),
        ("Waking up too early", SEVERITY),
        ("How satisfied are you with your sleep now?",
         ("Very satisfied", "Satisfied", "Neutral", "Dissatisfied", "Very dissatisfied")),
        ("How much do others notice your sleep problem?",
         ("Not at all", "A little", "Somewhat", "Much", "Very much")),
        ("How worried are you about your sleep?", ("Not at all", "A little", "Somewhat", "Much", "Very much")),
        ("How much does it get in the way of your day?", ("Not at all", "A little", "Somewhat", "Much", "Very much")),
    )

    def __init__(self, parent: QWidget | None) -> None:
        super().__init__(parent, "", done="Next", cancel="Cancel")
        self.step = 0
        #: the answer index per question (None until picked)
        self.picked: list[int | None] = [None] * len(self.QUESTIONS)
        self.progress = kit.Bar("accent", 4)
        self.body.addWidget(self.progress)
        self.kicker = label("", "kicker")
        self.question = label("", "h2", wrap=True)
        self.hint = label("Over the last two weeks. About 2 minutes in all.", "muted", wrap=True)
        head = QVBoxLayout()
        head.setSpacing(4)
        head.setContentsMargins(4, 8, 4, 0)
        for w in (self.kicker, self.question, self.hint):
            head.addWidget(w)
        self.body.addLayout(head)
        self.choices = kit.Group(sheet=True)
        self.rows: list[kit.Row] = []
        for i in range(5):
            mark = kit.Glyph("check", 18, "accent", stroke=2.4)
            row = kit.Row("", trailing=mark, clickable=True)
            row.mark = mark
            row.clicked.connect(lambda i=i: self.choose(i))
            self.choices.add(row)
            self.rows.append(row)
        self.body.addWidget(self.choices)
        self.body.addStretch(1)
        self._show_step()

    def choose(self, i: int) -> None:
        self.picked[self.step] = i
        self._show_step()

    def _show_step(self) -> None:
        n = len(self.QUESTIONS)
        question, words = self.QUESTIONS[self.step]
        self.set_title(f"{self.step + 1} of {n}")
        self.progress.set_fraction((self.step + 1) / n)
        self.kicker.setText("SLEEP CHECK" if self.step < 3 else "HOW IT AFFECTS YOU")
        self.question.setText(question)
        set_shown(self.hint, self.step == 0)
        chosen = self.picked[self.step]
        for i, (row, word) in enumerate(zip(self.rows, words, strict=True)):
            row.set_title(word)
            set_shown(row.mark, chosen == i)
        self.done_button.setText("See result" if self.step == n - 1 else "Next")
        self.done_button.setEnabled(chosen is not None)
        self.cancel_button.setText("Cancel" if self.step == 0 else "Back")
        if self.isVisible():
            self.place()  # a longer question makes the sheet taller

    def accept(self) -> None:
        if self.picked[self.step] is None:
            return
        if self.step < len(self.QUESTIONS) - 1:
            self.step += 1
            self._show_step()
            return
        super().accept()

    def reject(self) -> None:
        """Back a step; Cancel (or Esc) on the first step closes the sheet."""
        if self.step > 0:
            self.step -= 1
            self._show_step()
            return
        super().reject()

    def scrim_clicked(self) -> None:
        QDialog.reject(self)

    def values(self) -> list[int]:
        return [v or 0 for v in self.picked]

    @staticmethod
    def meaning(score: int) -> str:
        if score <= 7:
            return "No sign of insomnia."
        if score <= 14:
            return "Some trouble sleeping. Keep the wind-down going and check again in two weeks."
        if score <= 21:
            return ("Moderate insomnia. Talk to a doctor; CBT for insomnia (CBT-I) works better than sleeping pills "
                    "over time.")
        return "Severe insomnia. Please see a doctor soon; CBT for insomnia (CBT-I) helps most people."


class EasyDayDialog(layers.Sheet):
    """DX-05 moves the hard training to tomorrow; DX-06 cancels it and can set an earlier bedtime."""

    def __init__(self, parent: QWidget | None, events: list[dict], *, ill: bool, bedtime: str | None = None) -> None:
        super().__init__(parent, "Take it easy" if ill else "Make it an easy day", done=None, cancel="Not now",
                         footer="Rest today" if ill else "Easy day")
        what = "cancel" if ill else "move to tomorrow"
        group = kit.Group("Hard training", f"Switched on = {what}." if events else
                          "No hard training in your calendar today or tomorrow.", sheet=True)
        self.boxes: list[kit.Switch] = []
        for event in events:
            box = kit.Switch(True)
            box.setProperty("event_id", event.get("id"))
            group.add(kit.Row(str(event.get("title") or "Training"), _when(event), icon="dumbbell",
                              accent=t.WORKOUT, trailing=box))
            self.boxes.append(box)
        if not events:
            group.add(kit.Row("Nothing to change", kind="quiet"))
        self.body.addWidget(group)
        self.early_night: kit.Switch | None = None
        if ill and bedtime:
            self.early_night = kit.Switch(True)
            night = kit.Group("Tonight", sheet=True)
            night.add(kit.Row("Bed 30 min earlier", f"Bedtime {bedtime}", icon="sleep", accent=t.SLEEP,
                              trailing=self.early_night))
            self.body.addWidget(night)
        if not ill:
            later = kit.Group("Then", "Ten quiet minutes now: sit or lie down, breathe slowly.", sheet=True)
            later.add(kit.Row("Recovery break", value="10 min", icon="recover", accent=t.RECOVERY))
            self.body.addWidget(later)

    def chosen(self) -> list[str]:
        return [str(b.property("event_id")) for b in self.boxes if b.isChecked()]

    def wants_early_night(self) -> bool:
        return self.early_night is not None and self.early_night.isChecked()


class HelpDialog(layers.Sheet):
    """NI-02: shown after a low mood, or when mood has been low most of the week."""

    OPTIONS: ClassVar[tuple[tuple[str, str], ...]] = (
        ("Talk to someone you trust", "A friend, family member or colleague. Saying it out loud helps."),
        ("Tele-MANAS · 14416", "Free, 24×7, in many Indian languages (Government of India)."),
        ("iCall · +91 9152987821", "Counsellors, Monday to Saturday, 10 am to 8 pm."),
        ("Emergency · 112", "If you feel unsafe or might hurt yourself, call now."),
    )
    ICONS: ClassVar[tuple[str, ...]] = ("people", "bubble", "bubble", "shield")

    def __init__(self, parent: QWidget | None, *, week_low: bool) -> None:
        super().__init__(parent, "Help is close", done="OK", cancel="Not now")
        intro = ("Your mood has been low most of this week. That's worth taking seriously."
                 if week_low else "Thanks for telling us. You don't have to handle a low day alone.")
        self.body.addWidget(_intro(intro))
        group = kit.Group("Reach out", sheet=True)
        last = len(self.OPTIONS) - 1
        for i, ((title, detail), icon) in enumerate(zip(self.OPTIONS, self.ICONS, strict=True)):
            crisis = i == last
            group.add(kit.Row(title, detail, icon=icon, accent=t.WORKOUT if crisis else t.RECOVERY,
                              kind="danger" if crisis else "", wrap=True, clickable=False))
        self.body.addWidget(group)
        self.reach_out = kit.Switch(True)
        todo = kit.Group(footer="A small step for today.", sheet=True)
        todo.add(kit.Row("Add 'Reach out to someone'", "to today's tasks", trailing=self.reach_out))
        self.body.addWidget(todo)


class ConnectionDialog(layers.Sheet):
    """NI-03: who comes to mind; call them today, or plan a meal together this week."""

    TIMES: ClassVar[tuple[str, ...]] = ("13:00", "19:30")

    def __init__(self, parent: QWidget | None, today: date) -> None:
        super().__init__(parent, "Reach out", done=None, cancel="Not now", footer="Plan it")
        self.body.addWidget(_intro("A short hello lifts the whole fortnight. Who comes to mind?"))
        who = kit.Group(sheet=True)
        self.name = QLineEdit()
        self.name.setProperty("class", "plain")
        self.name.setPlaceholderText("A friend's name")
        row = kit.Row(icon="people", accent=t.CARDIAC)
        row.title.hide()
        row.layout().setStretch(1, 0)
        row.layout().insertWidget(2, self.name, 1)
        who.add(row)
        self.body.addWidget(who)
        plan = kit.Group("Plan", "Afterwards, log how connected you feel.", sheet=True)
        self.call = kit.Switch(True)
        plan.add(kit.Row("Call them today", icon="bubble", accent=t.RECOVERY, trailing=self.call))
        self.meal = kit.Switch(False)
        plan.add(kit.Row("A meal together this week", icon="cup", accent=t.STRESS, trailing=self.meal))
        self.day = QComboBox()
        self.day.setProperty("class", "pill")
        for i in range(1, 8):
            d = today + timedelta(days=i)
            self.day.addItem(d.strftime("%a %d %b"), d.isoformat())
        saturday = (5 - today.weekday()) % 7 or 7  # the coming Saturday, 1-7 days away
        self.day.setCurrentIndex(saturday - 1)
        plan.add(kit.Row("Day", trailing=self.day))
        self.time = kit.Segmented(self.TIMES, 0)
        self.time.setFixedWidth(150)
        plan.add(kit.Row("Time", trailing=self.time))
        self.body.addWidget(plan)
        self.meal.toggled.connect(self._meal_toggled)
        self._meal_toggled(self.meal.isChecked())

    def _meal_toggled(self, on: bool) -> None:
        self.day.setEnabled(on)
        self.time.setEnabled(on)

    def who(self) -> str:
        return self.name.text().strip() or "a friend"

    def meal_start(self, tz) -> datetime:
        hh, mm = (int(x) for x in (self.time.text() or self.TIMES[0]).split(":"))
        day = date.fromisoformat(str(self.day.currentData()))
        return datetime(day.year, day.month, day.day, hh, mm, tzinfo=tz)


class RatingDialog(layers.Sheet):
    """A 1-5 check-in: mood (NI-02) or connection (NI-03). A tap on a row answers; ``value`` is 1-5 or None."""

    WORDS: ClassVar[dict[str, tuple[str, ...]]] = {
        "mood": ("Very low", "Low", "Okay", "Good", "Great"),
        "connection": ("Alone", "A little", "Some", "Good", "Very connected"),
    }
    #: tile colours from low to high
    TINTS: ClassVar[tuple[str, ...]] = (t.WORKOUT, t.STRESS, t.FOCUS, t.TEAL, t.RECOVERY)

    def __init__(self, parent: QWidget | None, kind: str, question: str) -> None:
        super().__init__(parent, "Check-in", done=None, cancel="Not now")
        self.value: int | None = None
        q = label(question, "h2", wrap=True)
        q.setContentsMargins(4, 4, 4, 0)
        self.body.addWidget(q)
        group = kit.Group(footer="Saved as a check-in; it shows in your week.", sheet=True)
        self.rows: list[kit.Row] = []
        for i, word in enumerate(self.WORDS.get(kind, self.WORDS["mood"]), start=1):
            row = kit.Row(word, value=f"{i} of 5", icon="circle" if i == 3 else "heart",
                          accent=self.TINTS[i - 1], chevron=True)
            row.setProperty("value", i)
            row.clicked.connect(lambda i=i: self.pick(i))
            group.add(row)
            self.rows.append(row)
        self.body.addWidget(group)

    def pick(self, value: int) -> None:
        self.value = value
        self.accept()


def ask_minutes(parent: QWidget | None, title: str, default: int = 30) -> int | None:
    """A minutes picker (PB-04's strength session): a stepper in a sheet."""
    sheet = layers.Sheet(parent, title, done="Log it", cancel="Cancel")
    stepper = kit.Stepper(default, 5, 240, 5, "{} min")
    group = kit.Group(footer="Five-minute steps.", sheet=True)
    group.add(kit.Row("Minutes", icon="dumbbell", accent=t.FITNESS, trailing=stepper))
    sheet.body.addWidget(group)
    return stepper.value() if sheet.exec() == QDialog.DialogCode.Accepted else None


def confirm(parent: QWidget | None, title: str, text: str, ok: str) -> bool:
    """A yes/no alert; ``ok`` is the bold button, "Not now" the other."""
    return layers.Alert.ask(parent, title, text, ok, "Not now")


def pick_time(parent: QWidget | None, title: str, initial: str) -> str | None:
    """One time of day (HH:MM) in a sheet, or None."""
    sheet = layers.Sheet(parent, "Wake time", done="Save", cancel="Cancel")
    pill = kit.time_pill(QTime.fromString(initial or "07:00", "HH:mm"))
    group = kit.Group(footer=title, sheet=True)
    group.add(kit.Row("Wake up at", icon="alarm", accent=t.FOCUS, trailing=pill))
    sheet.body.addWidget(group)
    if sheet.exec() != QDialog.DialogCode.Accepted:
        return None
    return pill.time().toString("HH:mm")

