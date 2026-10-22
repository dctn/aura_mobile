"""The band simulator, the scenarios and the lab runner's date bookkeeping. No network."""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta

import pytest

from aura_mobile.core.features import ALERT_FEATURES, BY_ID, FEATURES, LabMode
from aura_mobile.services.sim.band import band_day, batches, healthy_day, live_packets, seeded
from aura_mobile.services.sim.runner import COMPUTES_PER_HOUR, LabRunner, LabState, choose_target
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


@pytest.mark.parametrize("feature", ["DX-04", "NI-02", "BS-02"])
def test_a_refused_run_stops_at_once_and_frees_its_dates(tmp_path, feature):
    api = NoConsentApi()
    state = LabState.load("s", tmp_path)
    with pytest.raises(RuntimeError, match="no health-data consent"):
        LabRunner(api, state).run(BY_FEATURE[feature])
    assert api.calls == 1  # one write, not a batch per minute of the day
    assert state.touched == set() and state.runs == []
    assert LabState.load("s", tmp_path).touched == set()
