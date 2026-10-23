"""One Test-lab scenario per alert feature: which days to send, which check-ins, and which date the alert lands on.

The day shapes follow ``aws_docs/services/health-data/test/e2e/features.ts`` with the knobs that file could not
express (sleep latency, a stress stretch, skin temperature, SpO2 dips, low night HRV) filled in. Every scenario ends on
a *past* date, so Health Data books the day's final run by itself about 2 minutes after the last packet: the weekly
and fortnightly rules that only run on a final day (DX-02, DX-09, PB-01, PB-02, PB-04, PB-05, NI-03) need nothing
else, and no compute slot is spent.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import date as Date
from datetime import timedelta

from aura_mobile.core.features import BY_ID, Feature
from aura_mobile.services.sim.band import DayShape, healthy_day

Mut = Callable[[DayShape, int], DayShape]


@dataclass(frozen=True)
class CheckIn:
    kind: str  # "mood" | "connection"
    value: int  # 1-5
    days_before: int  # 0 = the target day
    hour: int = 9


@dataclass(frozen=True)
class Scenario:
    feature_id: str
    days: int  # how many band days end at the target (0 = none)
    note: str
    mutate: Mut = field(default=lambda b, _i: b)
    weekday: int | None = None  # Python's weekday(): Monday 0 … Sunday 6
    month_days: tuple[int, ...] = ()
    checkins: tuple[CheckIn, ...] = ()
    #: QUIET_DAY: compute the target with no data, and the day before must be empty too (uses one compute slot)
    compute_empty_target: bool = False
    #: the alert does not come from the target's run but from a timer it books ~2 h later
    timer_wait_minutes: int = 0
    #: a scenario for several features at once (its ``feature_id`` is then not a feature's)
    covers: tuple[str, ...] = ()

    @property
    def feature(self) -> Feature:
        return BY_ID[self.feature_id]

    @property
    def feature_ids(self) -> tuple[str, ...]:
        return self.covers or (self.feature_id,)

    def fits(self, target: Date) -> bool:
        if self.weekday is not None and target.weekday() != self.weekday:
            return False
        return not (self.month_days and target.day not in self.month_days)

    def dates(self, target: Date) -> list[Date]:
        """Every date this scenario writes or needs empty, oldest first."""
        if self.compute_empty_target:
            return [target - timedelta(days=1), target]
        return [target - timedelta(days=self.days - 1 - i) for i in range(self.days)]

    def footprint(self, target: Date) -> list[Date]:
        """Every date whose day file this run's packets land in: :meth:`dates` plus the evening before the first
        night (a night starts the day before its date). The lab keeps footprints apart, because a day that is already
        final takes newer packets only in an assemble 30 minutes later (dev, 22 Oct: DX-03's first evening made
        16 Oct final 3 min before DX-04 sent that night)."""
        dates = self.dates(target)
        if self.days == 0 or self.compute_empty_target:
            return dates
        return [dates[0] - timedelta(days=1), *dates]

    def shapes(self, target: Date, time_zone: str) -> list[DayShape]:
        out: list[DayShape] = []
        for i in range(self.days):
            day = target - timedelta(days=self.days - 1 - i)
            out.append(self.mutate(healthy_day(day.isoformat(), time_zone, seed=7 + i), i))
        return out


def _last(n: int, change: Mut) -> Mut:
    """Apply ``change`` to the last day of an ``n``-day run only."""
    return lambda b, i: change(b, i) if i == n - 1 else b


SCENARIOS: tuple[Scenario, ...] = (
    Scenario("DX-01", 14, "14 short nights (5 h 45 m) build a fortnight's sleep debt past 2 h.",
             lambda b, _i: b.but(sleep_minutes=345)),
    # The rule reads wakeRegularityMin: the spread (std dev) of wake times over the 14 nights *before* the Monday.
    # 7 swinging nights left most of that window to earlier, steadier runs (dev, 22 Oct: 111 min, under 120), so all
    # 14 nights swing: waking 03:45, then 10:45, and so on (about 215 min). The 03:00 nights need a waking afternoon:
    # without one the next 20:00 bedtime filled their 10:00-22:00 waking window and no sleep was found (dev: 0).
    Scenario("DX-02", 14, "Two weeks of bedtimes swinging between 20:00 and 03:00 (waking 03:45 or 10:45); "
                          "lands on a Monday.",
             lambda b, i: b.but(bed_hour=(20, 3)[i % 2], day_minutes=390), weekday=0),
    Scenario("DX-03", 5, "Five nights that each take 45 min to fall asleep.",
             lambda b, _i: b.but(latency_minutes=45, resting_hr=58)),
    Scenario("DX-04", 1, "One 5 h 30 m night, under the 6 h nap line.",
             lambda b, _i: b.but(sleep_minutes=330)),
    Scenario("DX-05", 10, "Nine calm nights, then a short night with a high resting HR and low HRV.",
             _last(10, lambda b, _i: b.but(sleep_minutes=300, resting_hr=70, wake_walk_minutes=0, sleep_rmssd=18))),
    Scenario("DX-06", 11, "Ten calm nights, then resting HR +10, low HRV and skin temperature +0.6 °C.",
             _last(11, lambda b, _i: b.but(resting_hr=62, sleep_rmssd=18, skin_delta_c=0.6))),
    Scenario("DX-07", 1, "A 2-hour still, fast-heart, low-HRV stretch in the afternoon.",
             lambda b, _i: b.but(resting_hr=60, stress_minutes=120, sitting_bout_minutes=20)),
    Scenario("DX-09", 7, "A near-sedentary week (5 active min a day, no walks); lands on a Thursday.",
             lambda b, _i: b.but(wake_walk_minutes=0, active_minutes=5), weekday=3),
    Scenario("PB-01", 5, "The sleep window jumps from 23:00 to 01:00-03:00, so the body clock drifts.",
             lambda b, i: b.but(bed_hour=(23, 2, 3, 1, 2)[i]), ),
    # Burnout scores the last days against the ones before (HRV, resting HR, recovery) plus sleep debt and daily
    # stress, so a fortnight of plain days comes first. Three flat weeks of short sleep scored Low (offline, 23 Oct).
    Scenario("PB-02", 21, "Two ordinary weeks, then a week of short late nights, high resting HR, low HRV and "
                          "long stressed afternoons; lands on a Monday.",
             lambda b, i: b if i < 14 else b.but(sleep_minutes=300, bed_hour=1, resting_hr=58, sleep_rmssd=20,
                                                  stress_minutes=150, sitting_bout_minutes=20), weekday=0),
    Scenario("PB-03", 1, "One unbroken 95-minute desk bout.",
             lambda b, _i: b.but(sitting_bout_minutes=95)),
    Scenario("PB-04", 24, "A fit first week fades into three sedentary weeks with resting HR creeping up.",
             lambda b, i: b.but(active_minutes=70 if i < 4 else 10, resting_hr=50 + i // 3)),
    # breathingDisturbances come only from BioZ breathing bursts (pipeline/respiration.py); without them it is absent.
    Scenario("PB-05", 10, "Ten nights with 15 overnight SpO2 dips each, and breathing pauses on the nights.",
             lambda b, _i: b.but(spo2_dips=15, resting_hr=60, sleep_minutes=420, breath_pauses=6)),
    Scenario("NI-02", 1, "Three low mood check-ins (1/5) over two days, then a computed day.",
             checkins=(CheckIn("mood", 1, 1, 10), CheckIn("mood", 2, 1, 19), CheckIn("mood", 1, 0, 9))),
    Scenario("NI-03", 1, "A day on the 1st or 15th with no connection check-ins this fortnight.",
             month_days=(1, 15)),
    Scenario("NI-04", 1, "A final day books the hydration timer; it fires about 2 h later if today's water is "
             "behind the goal. Log no water until it arrives.", timer_wait_minutes=150),
    Scenario("BS-02", 0, "Computes a day that has no band data, after a day that has none either. "
             "Uses one compute slot.", compute_empty_target=True),
)

BY_FEATURE: dict[str, Scenario] = {s.feature_id: s for s in SCENARIOS}

MONTH_DAYS = 26
#: the swinging-bedtime fortnight, then the steady days
SWING_DAYS = 12
#: the last week: the burnout week
HARD_DAYS = 7


def _month(b: DayShape, i: int) -> DayShape:
    """Day ``i`` (0 = first) of :data:`ALL_AT_ONCE`, a 26-day month ending on a Monday (so day 25, 18, 11 and 4 are
    Mondays and 21, 14, 7 and 0 Thursdays). Each rule needs its own pattern, and they are laid out so none undoes
    another. Proven offline through Health Data's own day pipeline (every day, state carried) with the rules applied
    to each day: all 13 band features fire, on two different months."""
    hard = MONTH_DAYS - HARD_DAYS
    if i < SWING_DAYS:
        # DX-02, PB-01: bedtimes swinging 20:00 / 03:00, with DX-02's waking afternoon (and no extra activity, or a
        # 03:00 day runs into the next 20:00 bedtime). Calm, with a low resting HR: the VO2max (PB-04) and the
        # resting-HR baseline (DX-05, DX-06) to fall from.
        b = b.but(bed_hour=(20, 3)[i % 2], day_minutes=390, resting_hr=50)
    else:
        # DX-09: sedentary from here, so Thursday day 21 ends a week under 150 active minutes. PB-04: resting HR
        # creeps up, so VO2max (Uth: 15.3 × HRmax / resting HR) drops 2+ points by day 20.
        b = b.but(active_minutes=5, wake_walk_minutes=0, resting_hr=52 + (i - SWING_DAYS) // 2)
    if SWING_DAYS <= i < SWING_DAYS + 5:
        b = b.but(latency_minutes=45)  # DX-03: five slow nights
    if i == 13:
        b = b.but(sitting_bout_minutes=95)  # PB-03
    if i == 15:
        b = b.but(stress_minutes=120, sitting_bout_minutes=20)  # DX-07
    if i == 17:
        b = b.but(resting_hr=b.resting_hr + 10, sleep_rmssd=18, skin_delta_c=0.6)  # DX-06 (and DX-05)
    if i >= 17:
        b = b.but(spo2_dips=15)  # PB-05: 7+ nights of dips by day 23
    if i >= hard:
        # The burnout week ending on the Monday (PB-02): short late nights (DX-01, DX-04), resting HR up, low HRV,
        # a long stressed afternoon every day. The breathing pauses give PB-05 its disturbances.
        b = b.but(sleep_minutes=300, bed_hour=1, resting_hr=b.resting_hr + 6, sleep_rmssd=20, stress_minutes=150,
                  sitting_bout_minutes=20, breath_pauses=6)
    return b


#: Every alert the band, the check-ins and the timer can raise, from one month of data on the same dates, as a real
#: month of a real person would. BS-02 needs days with no data, so it stays a run of its own.
ALL_AT_ONCE = Scenario(
    "ALL", MONTH_DAYS,
    "One month for 16 alerts: 12 nights of swinging bedtimes, slow nights, a desk bout, a stressed afternoon, a "
    "fever-like night, then a burnout week with short nights, SpO2 dips and breathing pauses, three low moods, and "
    "the hydration timer. Lands on a Monday. Needs your birth date in Profile (VO2max uses it).",
    _month, weekday=0,
    checkins=(CheckIn("mood", 1, 1, 10), CheckIn("mood", 2, 1, 19), CheckIn("mood", 1, 0, 9)),
    timer_wait_minutes=150,
    covers=("DX-01", "DX-02", "DX-03", "DX-04", "DX-05", "DX-06", "DX-07", "DX-09",
            "PB-01", "PB-02", "PB-03", "PB-04", "PB-05", "NI-02", "NI-03", "NI-04"),
)
