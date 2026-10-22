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

    @property
    def feature(self) -> Feature:
        return BY_ID[self.feature_id]

    def fits(self, target: Date) -> bool:
        if self.weekday is not None and target.weekday() != self.weekday:
            return False
        return not (self.month_days and target.day not in self.month_days)

    def dates(self, target: Date) -> list[Date]:
        """Every date this scenario writes or needs empty, oldest first."""
        if self.compute_empty_target:
            return [target - timedelta(days=1), target]
        return [target - timedelta(days=self.days - 1 - i) for i in range(self.days)]

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
    Scenario("DX-02", 7, "Bedtimes swing from 20:00 to 03:00 across the week; lands on a Monday.",
             lambda b, i: b.but(bed_hour=(23, 20, 2, 21, 0, 23, 3)[i]), weekday=0),
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
    Scenario("PB-02", 21, "Three weeks of short sleep, high HR and heavy load; lands on a Monday.",
             lambda b, _i: b.but(sleep_minutes=345, resting_hr=66, active_minutes=120), weekday=0),
    Scenario("PB-03", 1, "One unbroken 95-minute desk bout.",
             lambda b, _i: b.but(sitting_bout_minutes=95)),
    Scenario("PB-04", 24, "A fit first week fades into three sedentary weeks with resting HR creeping up.",
             lambda b, i: b.but(active_minutes=70 if i < 4 else 10, resting_hr=50 + i // 3)),
    Scenario("PB-05", 10, "Ten nights with 15 overnight SpO2 dips each.",
             lambda b, _i: b.but(spo2_dips=15, resting_hr=60, sleep_minutes=420)),
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
