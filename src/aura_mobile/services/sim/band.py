"""A synthetic band day: one packet a minute, shaped so Health Data's detectors and pipeline see a real-looking day.

Ported from ``aws_docs/services/health-data/test/e2e/band.ts`` (the same mulberry32 RNG and segment values), with
three changes for a phone on a real clock:

* the timeline is built in the phone's own zone, so "bed at 23:00" is 23:00 local and not UTC;
* a bed hour after midnight (``bed_hour < 12``) starts the night on the wake day itself, not 24 h early;
* each minute carries RR intervals (``rr``), so HRV and stress have beats to read. ``rmssd`` per segment sets how
  variable they are: a calm night is about 55 ms, a stressed hour about 15 ms.

It also adds the knobs the alert rules need and band.ts lacks: sleep latency, a stress stretch, a skin-temperature
shift and SpO2 dips.
"""

from __future__ import annotations

import math
from collections.abc import Iterator, Sequence
from dataclasses import dataclass, replace
from datetime import date as Date
from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

MAX_PACKETS = 40
MAX_RR = 200
FIRMWARE = "demo-sim-1.0"
#: HR while lying awake before sleep, as a share of the night's resting HR (see segments_of)
LATENCY_HR_RATIO = 1.16
_MASK = 0xFFFFFFFF


def seeded(seed: int):
    """mulberry32, bit for bit the same as band.ts: a seed reproduces the same day."""
    state = seed & _MASK

    def imul(a: int, b: int) -> int:
        return (a * b) & _MASK

    def rng() -> float:
        nonlocal state
        state = (state + 0x6D2B79F5) & _MASK
        t = imul(state ^ (state >> 15), 1 | state)
        t = ((t + imul(t ^ (t >> 7), 61 | t)) & _MASK) ^ t
        return ((t ^ (t >> 14)) & _MASK) / 4_294_967_296

    return rng


def _round(n: float, dp: int = 0) -> float:
    """JavaScript's Math.round (halves go up), not Python's banker's rounding."""
    f = 10**dp
    return math.floor(n * f + 0.5) / f


@dataclass(frozen=True)
class DayShape:
    """The knobs of one day. ``date`` is the day you wake up on; the night before belongs to it."""

    date: str
    time_zone: str
    sleep_minutes: int = 465
    bed_hour: int = 23
    resting_hr: int = 52
    wake_walk_minutes: int = 20
    sitting_bout_minutes: int = 40
    active_minutes: int = 45
    seed: int = 7
    latency_minutes: int = 0  # awake in bed before sleep (DX-03)
    stress_minutes: int = 0  # a still, fast-heart, low-HRV stretch (DX-07)
    skin_delta_c: float = 0.0  # added to the night's skin temperature (DX-06)
    spo2_dips: int = 0  # minutes at 88 % spread over the night (PB-05)
    sleep_rmssd: float = 55.0  # how variable the night's beats are; low means low HRV (DX-05, DX-06)
    day_minutes: int = 0  # quiet waking minutes after the morning, a few steps each (DX-02)

    def but(self, **changes) -> DayShape:
        return replace(self, **changes)


@dataclass(frozen=True)
class Segment:
    minutes: int
    steps_per_min: float
    hr: float
    skin_c: float
    rmssd: float
    spo2: float = 97.0
    kind: str = ""


def healthy_day(day: str, time_zone: str, seed: int = 7) -> DayShape:
    """band.ts's healthy day: enough sleep, low resting HR, a morning walk, a short sitting bout, decent movement."""
    return DayShape(day, time_zone, seed=seed)


def segments_of(shape: DayShape) -> list[Segment]:
    out = [Segment(30, 8, 76, 33.3, 40, kind="wind-down")]
    if shape.latency_minutes > 0:
        # Health Data counts latency from still minutes whose HR sits between 1.12× and 1.25× the night's floor
        # (pipeline/sleep.py); a flat 64 was barely 1.1× a 58 sleep HR and read as sleep (dev, 22 Oct: latency 0–26).
        # 1.16× gives 38–40 min for a 45-min latency in the pipeline itself.
        out.append(Segment(shape.latency_minutes, 0, round(shape.resting_hr * LATENCY_HR_RATIO), 34.0, 40,
                           kind="latency"))
    out.append(
        Segment(shape.sleep_minutes, 0, shape.resting_hr, 34.6 + shape.skin_delta_c, shape.sleep_rmssd, kind="sleep")
    )
    if shape.wake_walk_minutes > 0:
        out.append(Segment(shape.wake_walk_minutes, 112, 98, 32.9, 25, kind="walk"))
    out.append(Segment(shape.sitting_bout_minutes, 0, 71, 33.5, 35, kind="sitting"))
    if shape.active_minutes > 0:
        out.append(Segment(shape.active_minutes, 110, 95, 33.0, 25, kind="active"))
    if shape.stress_minutes > 0:
        # A few steps a minute so it does not read as one long sitting bout with the one before it.
        out.append(Segment(shape.stress_minutes, 4, 96, 33.6, 15, kind="stress"))
    if shape.day_minutes > 0:
        # Health Data's main sleep must sit 10% under the median HR of still waking minutes 10:00-22:00
        # (pipeline/sleep.py); without them an evening bedtime's first hours are most of that window.
        out.append(Segment(shape.day_minutes, 6, 72, 33.4, 35, kind="day"))
    out.append(Segment(20, 3, 70, 33.5, 35, kind="tail"))
    return out


def night_start(shape: DayShape) -> datetime:
    """30 min before the bed hour, in the phone's zone: the evening before, or the small hours of the day itself."""
    tz = ZoneInfo(shape.time_zone)
    wake_day = Date.fromisoformat(shape.date)
    bed_day = wake_day if shape.bed_hour < 12 else wake_day - timedelta(days=1)
    return datetime.combine(bed_day, time(shape.bed_hour % 24), tzinfo=tz) - timedelta(minutes=30)


def sim_device_id(label: str) -> str:
    raw = f"demo-band-{label}"
    return "".join(c if c.isalnum() or c in ":_-" else "-" for c in raw)[:64]


def _gauss(rng) -> float:
    u1 = max(rng(), 1e-12)
    u2 = rng()
    return math.sqrt(-2 * math.log(u1)) * math.cos(2 * math.pi * u2)


def _beats(rng, t0: int, hr: float, rmssd: float) -> list[dict]:
    """RR intervals for one minute. Independent noise of sd s gives an RMSSD of s·√2."""
    mean = 60_000 / max(hr, 30)
    sd = rmssd / math.sqrt(2)
    out: list[dict] = []
    offset = 0.0
    while offset < 60_000 and len(out) < MAX_RR:
        rr = min(max(mean + _gauss(rng) * sd, 300), 2000)
        offset += rr
        if offset > 60_000:
            break
        out.append({"t": round(t0 + offset / 1000, 3), "rrMs": round(rr, 1)})
    return out


def band_day(shape: DayShape, *, with_rr: bool = True, battery: int = 80) -> list[dict]:
    """One packet a minute across every segment, with a cumulative step counter and light per-minute noise."""
    rng = seeded(shape.seed)
    beat_rng = seeded(shape.seed ^ 0x5EED)
    device_id = sim_device_id(shape.date)
    packets: list[dict] = []
    steps = 4000
    at = int(night_start(shape).timestamp())
    for seg in segments_of(shape):
        dips = _dip_minutes(seg.minutes, shape.spo2_dips) if seg.kind == "sleep" else set()
        for i in range(seg.minutes):
            steps += max(0, int(_round(seg.steps_per_min + (rng() - 0.5) * 6)))
            hr = _round(seg.hr + (rng() - 0.5) * 4)
            spo2 = 88.0 if i in dips else _round(seg.spo2 + (rng() - 0.5) * 0.8)
            packet = {
                "v": 1,
                "deviceId": device_id,
                "t": at,
                "seconds": 60,
                "device": {
                    "steps": steps,
                    "hr": int(hr),
                    "spo2": int(spo2),
                    "skinTempC": _round(seg.skin_c + (rng() - 0.5) * 0.2, 2),
                    "battery": battery,
                },
                "firmwareVersion": FIRMWARE,
            }
            if with_rr:
                packet["rr"] = _beats(beat_rng, at, hr, seg.rmssd)
            packets.append(packet)
            at += 60
    return packets


def _dip_minutes(minutes: int, count: int) -> set[int]:
    if count <= 0 or minutes <= 0:
        return set()
    step = max(1, minutes // (count + 1))
    return {step * (k + 1) for k in range(count) if step * (k + 1) < minutes}


def batches(packets: Sequence[dict], size: int = MAX_PACKETS) -> Iterator[list[dict]]:
    """Requests of at most 40 packets, as the phone sends them."""
    for i in range(0, len(packets), size):
        yield list(packets[i : i + size])


def live_packets(time_zone: str, *, minutes: int = 30, seed: int = 11, end: datetime | None = None) -> list[dict]:
    """The last ``minutes`` of an ordinary waking day ending now: what "Reconnect" sends after a gap (BS-02)."""
    rng = seeded(seed)
    beat_rng = seeded(seed ^ 0x5EED)
    tz = ZoneInfo(time_zone)
    stop = (end or datetime.now(tz)).astimezone(tz).replace(second=0, microsecond=0)
    at = int((stop - timedelta(minutes=minutes)).timestamp())
    device_id = sim_device_id(stop.date().isoformat())
    steps = 4000
    out: list[dict] = []
    for _ in range(minutes):
        steps += max(0, int(_round(20 + (rng() - 0.5) * 6)))
        hr = _round(74 + (rng() - 0.5) * 4)
        out.append(
            {
                "v": 1,
                "deviceId": device_id,
                "t": at,
                "seconds": 60,
                "device": {"steps": steps, "hr": int(hr), "spo2": 97, "skinTempC": 33.4, "battery": 76},
                "rr": _beats(beat_rng, at, hr, 35),
                "firmwareVersion": FIRMWARE,
            }
        )
        at += 60
    return out
