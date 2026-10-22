"""The band simulator, the scenarios and the lab runner's date bookkeeping. No network."""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

import pytest

from aura_mobile.core.features import ALERT_FEATURES, BY_ID, FEATURES, LabMode
from aura_mobile.services.sim.band import band_day, batches, healthy_day, live_packets, seeded
from aura_mobile.services.sim.runner import (
    COMPUTES_PER_HOUR,
    WINDOW_DAYS,
    LabRun,
    LabRunner,
    LabState,
    choose_target,
    night_count,
)
from aura_mobile.services.sim.scenarios import BY_FEATURE, SCENARIOS

TZ = "Asia/Kolkata"


def test_mulberry32_matches_band_ts():
    # band.ts: seeded(7) → first three draws
    rng = seeded(7)
    first = [rng() for _ in range(3)]
    again = seeded(7)
    assert first == [again() for _ in range(3)]
    assert all(0 <= x < 1 for x in first)


def test_healthy_day_shape():
    packets = band_day(healthy_day("2026-10-20", TZ))
    assert len(packets) == 620
    times = [p["t"] for p in packets]
    assert times == sorted(times) and times[1] - times[0] == 60
    steps = [p["device"]["steps"] for p in packets]
    assert steps == sorted(steps)
    assert all(len(p["rr"]) <= 200 for p in packets)
    assert packets[0]["deviceId"] == "demo-band-2026-10-20"


def test_same_seed_same_day():
    a = band_day(healthy_day("2026-10-20", TZ), with_rr=False)
    b = band_day(healthy_day("2026-10-20", TZ), with_rr=False)
    assert a == b


def test_short_night_has_fewer_sleep_minutes():
    normal = band_day(healthy_day("2026-10-20", TZ), with_rr=False)
    short = band_day(healthy_day("2026-10-20", TZ).but(sleep_minutes=330), with_rr=False)
    assert len(normal) - len(short) == 465 - 330


def test_batches_are_at_most_40():
    sizes = [len(b) for b in batches(list(range(95)))]
    assert sizes == [40, 40, 15]


def test_live_packets_end_now():
    end = datetime(2026, 10, 21, 12, 0, tzinfo=UTC)
    packets = live_packets(TZ, end=end)
    assert len(packets) == 30
    assert packets[-1]["t"] <= int(end.timestamp())


def test_every_alert_feature_has_a_scenario():
    assert {f.id for f in ALERT_FEATURES} == set(BY_FEATURE)
    assert len(FEATURES) == 18
    assert BY_ID["BS-01"].lab is LabMode.ACCOUNT


@pytest.mark.parametrize("scenario", SCENARIOS, ids=lambda s: s.feature_id)
def test_scenarios_find_a_past_target(scenario):
    today = date(2026, 10, 21)
    target = choose_target(scenario, set(), today)
    assert target is not None and target < today
    assert scenario.fits(target)
    dates = scenario.dates(target)
    assert dates[-1] == target
    assert len(scenario.shapes(target, TZ)) == scenario.days


def test_used_dates_push_the_next_window_back():
    scenario = BY_FEATURE["DX-04"]
    today = date(2026, 10, 21)
    first = choose_target(scenario, set(), today)
    used = {d.isoformat() for d in scenario.dates(first)}
    second = choose_target(scenario, used, today)
    assert second < first
    assert not ({d.isoformat() for d in scenario.dates(second)} & used)


class FakeApi:
    time_zone = TZ

    def __init__(self, alerts=()):
        self.samples = 0
        self.computed: list[str] = []
        self.checkins: list[tuple] = []
        self._alerts = list(alerts)

    def post_samples(self, packets):
        self.samples += len(packets)
        return {}

    def compute_day(self, day):
        self.computed.append(day)
        return {}

    def post_checkin(self, kind, *, taken_at, **values):
        self.checkins.append((kind, values, taken_at))
        return {}

    def alerts(self, start, end):
        return self._alerts


def test_run_marks_dates_and_posts_every_minute(tmp_path):
    api = FakeApi()
    state = LabState.load("sub-1", tmp_path)
    run = LabRunner(api, state).run(BY_FEATURE["DX-04"])
    assert api.samples == run.posted_packets > 0
    assert set(run.dates) <= state.touched
    assert not run.computed and api.computed == []
    reloaded = LabState.load("sub-1", tmp_path)
    assert reloaded.touched == state.touched
    assert reloaded.latest_run("DX-04").target == run.target


def test_checkin_scenario_posts_checkins(tmp_path):
    api = FakeApi()
    LabRunner(api, LabState.load("s", tmp_path)).run(BY_FEATURE["NI-02"])
    assert [c[0] for c in api.checkins] == ["mood"] * len(BY_FEATURE["NI-02"].checkins)


def test_quiet_day_computes_and_respects_the_cap(tmp_path):
    api = FakeApi()
    state = LabState.load("s", tmp_path)
    runner = LabRunner(api, state)
    run = runner.run(BY_FEATURE["BS-02"])
    assert run.computed and api.computed == [run.target]
    state.computes = [datetime.now(UTC).isoformat()] * COMPUTES_PER_HOUR
    with pytest.raises(RuntimeError, match="5 computes"):
        runner.run(BY_FEATURE["BS-02"])


def test_find_alert_ignores_an_older_runs_alert(tmp_path):
    state = LabState.load("s", tmp_path)
    runner = LabRunner(FakeApi(), state)
    run = runner.run(BY_FEATURE["DX-04"])
    old_day = (date.fromisoformat(run.target) - timedelta(days=20)).isoformat()
    runner.api = FakeApi([{"id": f"alert-{old_day}-DX-04", "featureId": "DX-04", "date": old_day}])
    assert runner.find_alert(run) is None
    runner.api = FakeApi([{"id": f"alert-{run.target}-DX-04", "featureId": "DX-04", "date": run.target}])
    assert runner.find_alert(run)["date"] == run.target
    assert state.latest_run("DX-04").found_alert == f"alert-{run.target}-DX-04"


class NoConsentApi(FakeApi):
    """Dev today: Health Data refuses every write for an account without consent."""

    def __init__(self):
        super().__init__()
        self.calls = 0

    def _refuse(self):
        from aura_mobile.services.http import ApiError

        self.calls += 1
        raise ApiError("not_found", "Health data consent isn't available for this account yet.", status=404)

    def post_samples(self, packets):
        self._refuse()

    def post_checkin(self, kind, *, taken_at, **values):
        self._refuse()

    def compute_day(self, day):
        self._refuse()


class ConsentOffApi(NoConsentApi):
    """Dev since 22 Oct: a write with health data off is refused with 403 consent_required."""

    def _refuse(self):
        from aura_mobile.services.http import ApiError

        self.calls += 1
        raise ApiError("consent_required", "Turn on health data in your privacy settings.", status=403)


def test_a_403_consent_refusal_frees_the_dates(tmp_path):
    state = LabState.load("s", tmp_path)
    with pytest.raises(RuntimeError, match="health data is off"):
        LabRunner(ConsentOffApi(), state).run(BY_FEATURE["DX-04"])
    assert state.touched == set() and state.runs == []


def test_any_failure_before_the_first_write_frees_the_dates(tmp_path):
    class Offline(FakeApi):
        def post_samples(self, packets):
            raise OSError("network down")

    state = LabState.load("s", tmp_path)
    with pytest.raises(OSError):
        LabRunner(Offline(), state).run(BY_FEATURE["DX-04"])
    assert state.touched == set() and state.runs == []


def test_a_run_stopped_before_sending_frees_the_dates(tmp_path):
    state = LabState.load("s", tmp_path)
    runner = LabRunner(FakeApi(), state)
    runner.state.save = lambda: runner.cancel() or LabState.save(state)  # Stop pressed right after the dates are marked
    with pytest.raises(RuntimeError, match="Stopped before anything"):
        runner.run(BY_FEATURE["DX-04"])
    assert state.touched == set() and state.runs == []


def test_load_drops_ghost_runs_from_before_22_oct(tmp_path):
    """21 Oct: the lab kept 18 runs the server refused (0 packets). They held most of the last 150 days, so DX-03
    found no free window."""
    import json

    real = {"feature_id": "DX-01", "target": "2026-05-31", "dates": ["2026-05-30", "2026-05-31"],
            "started_at": "2026-10-22T15:08:34+00:00", "posted_packets": 7000, "computed": False,
            "found_alert": None}
    ghost = {"feature_id": "DX-03", "target": "2026-08-26", "dates": ["2026-08-25", "2026-08-26", "2026-05-31"],
             "started_at": "2026-10-21T15:40:13+00:00", "posted_packets": 0, "computed": False, "found_alert": None}
    compute_only = {**ghost, "feature_id": "BS-02", "dates": ["2026-08-05"], "computed": True}
    path = tmp_path / "lab-s.json"
    path.write_text(json.dumps({"touched": sorted({*real["dates"], *ghost["dates"], "2026-08-05"}),
                                "runs": [real, ghost, compute_only], "computes": []}), encoding="utf-8")
    state = LabState.load("s", tmp_path)
    assert [r.feature_id for r in state.runs] == ["DX-01", "BS-02"]
    assert state.touched == {"2026-05-30", "2026-05-31", "2026-08-05"}  # 31 May stays: the real run used it too
    assert json.loads(path.read_text(encoding="utf-8"))["touched"] == sorted(state.touched)


def test_an_accepted_run_is_flagged(tmp_path):
    run = LabRunner(FakeApi(), LabState.load("s", tmp_path)).run(BY_FEATURE["NI-02"])
    assert run.accepted and not run.is_ghost


@pytest.mark.parametrize("feature", ["DX-04", "NI-02", "BS-02"])
def test_a_refused_run_stops_at_once_and_frees_its_dates(tmp_path, feature):
    api = NoConsentApi()
    state = LabState.load("s", tmp_path)
    with pytest.raises(RuntimeError, match="health data is off"):
        LabRunner(api, state).run(BY_FEATURE[feature])
    assert api.calls == 1  # one write, not a batch per minute of the day
    assert state.touched == set() and state.runs == []
    assert LabState.load("s", tmp_path).touched == set()


def test_rule_field_reads_the_metric_name():
    from aura_mobile.services.sim.runner import rule_field

    assert rule_field("sleepLatencyMin > 30 on 4+ nights") == "sleepLatencyMin"
    assert rule_field("Monday, final run: wakeRegularityMin ≥ 120") == "wakeRegularityMin"
    assert rule_field("3+ mood check-ins averaging ≤ 2") is None


def test_check_reports_the_computed_day_when_there_is_no_alert(tmp_path):
    class Computed(FakeApi):
        def metrics_day(self, day):
            return {"date": day, "final": True, "computedAt": "2026-10-22T15:35:31Z",
                    "metrics": {"sleepLatencyMin": 0, "totalSleepMin": 510}}

    runner = LabRunner(Computed(), LabState.load("s", tmp_path))
    run = runner.run(BY_FEATURE["DX-03"])
    seen = runner.check(run, "sleepLatencyMin > 30 on 4+ nights")
    assert seen.alert is None and seen.computed and seen.final
    assert (seen.field, seen.value) == ("sleepLatencyMin", 0)


def test_a_rule_counting_nights_gets_every_night_of_the_run(tmp_path):
    latency = {"2026-10-11": 0, "2026-10-12": 0, "2026-10-13": 26, "2026-10-14": 25, "2026-10-15": 37}

    class Nights(FakeApi):
        def metrics_day(self, day):
            return {"date": day, "final": True, "metrics": {"sleepLatencyMin": latency.get(day)}}

        def metrics_range(self, start, end, *, fields="summary"):
            assert fields == "sleepLatencyMin"
            return [{"date": d, "metrics": {fields: v}} for d, v in latency.items() if start <= d <= end]

    runner = LabRunner(Nights(), LabState.load("s", tmp_path))
    run = LabRun("DX-03", "2026-10-15", ["2026-10-10", "2026-10-15"], "2026-10-22T15:36:41+00:00", 3325)
    seen = runner.check(run, "sleepLatencyMin > 30 on 4+ nights", days=5)
    assert seen.nights == tuple(latency.items())
    assert runner.check(run, "totalSleepMin < 360", days=5).nights == ()


def test_night_count_reads_the_bar_and_how_many():
    assert night_count("sleepLatencyMin > 30 on 4+ nights") == (30.0, 4)
    assert night_count("totalSleepMin < 360") is None


def test_check_says_when_the_day_is_not_computed_yet(tmp_path):
    class NotYet(FakeApi):
        def metrics_day(self, day):
            return None

    runner = LabRunner(NotYet(), LabState.load("s", tmp_path))
    seen = runner.check(runner.run(BY_FEATURE["DX-04"]), "totalSleepMin < 360")
    assert not seen.computed and seen.value is None


def test_dx03_latency_sits_in_the_band_health_data_counts():
    """pipeline/sleep.py counts still minutes at 1.12–1.25× the night's floor as latency; 64 vs a 58 sleep HR read
    as sleep on dev (22 Oct). The floor is about the sleep HR minus the ±2 noise."""
    from aura_mobile.services.sim.band import segments_of

    shape = BY_FEATURE["DX-03"].shapes(date(2026, 10, 10), TZ)[0]
    latency = next(s for s in segments_of(shape) if s.kind == "latency")
    floor = shape.resting_hr - 1.6
    assert 1.12 * floor + 2 < latency.hr < 1.25 * floor - 2  # every noisy minute stays inside the band


def test_runs_never_share_a_day_file(tmp_path):
    """A night starts the evening before its date. DX-03 then DX-04 on dev (22 Oct) both wrote 16 Oct; DX-04's night
    waited 30 min for a late assemble. Each run now holds the evening before its first night too."""
    state = LabState.load("s", tmp_path)
    runner = LabRunner(FakeApi(), state)
    dx03 = runner.run(BY_FEATURE["DX-03"])
    dx04 = runner.run(BY_FEATURE["DX-04"])
    assert not set(dx03.dates) & set(dx04.dates)
    first = BY_FEATURE["DX-03"].dates(date.fromisoformat(dx03.target))[0]
    assert (first - timedelta(days=1)).isoformat() in dx03.dates


def test_every_run_writes_only_dates_aws_still_keeps():
    """Health Data drops band minutes 35 days after their own time; 15-17 Sep (dev, 22 Oct) lost their first hours
    before the assemble and DX-03's first two nights read 0. Every scenario fits the last 33 days on a fresh account."""
    today = date(2026, 10, 22)
    for scenario in SCENARIOS:
        target = choose_target(scenario, set(), today)
        assert target is not None, scenario.feature_id
        assert min(scenario.footprint(target)) >= today - timedelta(days=WINDOW_DAYS), scenario.feature_id


def test_a_full_window_has_no_room_rather_than_going_older():
    today = date(2026, 10, 22)
    touched = {(today - timedelta(days=back)).isoformat() for back in range(1, 20)}
    assert choose_target(BY_FEATURE["DX-02"], touched, today) is None
    assert choose_target(BY_FEATURE["DX-04"], touched, today) is not None


def test_dx02_days_never_overlap_and_each_has_a_waking_afternoon():
    """The 03:00 nights' 10:00-22:00 waking window was mostly the next 20:00 bedtime, so Health Data found no sleep
    (dev, 22 Oct: wakeRegularityMin 0). Each day now carries quiet waking hours, and no minute is sent twice."""
    shapes = BY_FEATURE["DX-02"].shapes(date(2026, 10, 19), TZ)
    assert all(s.day_minutes >= 360 for s in shapes)
    times = [p["t"] for s in shapes for p in band_day(s, with_rr=False)]
    assert len(times) == len(set(times))
