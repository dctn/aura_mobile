import { describe, expect, it } from 'vitest';
import {
  BACKFILL_MAX, BACKFILL_MIN, DEFAULT_PINS, MAX_PINS, backfillMinutes, burnoutRisk, lastSync, missing, numericFields, pinsOf, syncText,
  togglePin, weeklyMinutes,
} from './homeParts';

const Z = 'Asia/Kolkata';

describe('pins', () => {
  it('defaults when the visitor has not chosen', () => {
    expect(pinsOf(null)).toEqual(DEFAULT_PINS);
    expect(pinsOf([])).toEqual([]);
    expect(pinsOf(['a', 'b', 'c', 'd', 'e', 'f', 'g'])).toHaveLength(MAX_PINS);
  });

  it('lists the scalar fields, known ones first', () => {
    expect(numericFields({ zeta: 1, recoveryScore: 80, totalSleepMin: 400, series: '[1,2]', flag: true, gone: null })).toEqual([
      'totalSleepMin', 'recoveryScore', 'flag', 'gone', 'zeta',
    ]);
  });

  it('ticks up to six', () => {
    expect(togglePin(['a'], 'a')).toEqual([]);
    expect(togglePin(['a'], 'b')).toEqual(['a', 'b']);
    const full = ['a', 'b', 'c', 'd', 'e', 'f'];
    expect(togglePin(full, 'g')).toBe(full);
  });
});

describe('burnout', () => {
  const day = (date: string, score: number) => ({ date, metrics: { burnoutIndexScore: score } });
  it("uses the server's word, else buckets the newest score", () => {
    expect(burnoutRisk([], { metrics: { burnoutIndexRisk: 'moderate' } }, '2026-10-24')).toBe('Moderate');
    expect(burnoutRisk([day('2026-10-23', 75)], null, '2026-10-24')).toBe('High');
    expect(burnoutRisk([day('2026-10-20', 20), day('2026-10-23', 45)], null, '2026-10-24')).toBe('Moderate');
    expect(burnoutRisk([], null, '2026-10-24')).toBe('No data yet');
  });
});

describe('profile gaps', () => {
  it('lists unset body details', () => {
    expect(missing({ displayName: 'Asha', birthDate: '1994-06-12', sex: null, heightCm: 165, weightKg: '' })).toEqual(['sex', 'weight']);
    expect(missing({ displayName: 'A', birthDate: 'x', sex: 'notSet', heightCm: 1, weightKg: 1 })).toEqual([]);
    expect(missing(null)).toEqual([]);
  });
});

describe('the band line', () => {
  const now = new Date('2026-10-24T08:00:00Z'); // 13:30 in Kolkata

  it('prefers the visitor Reconnect, else the newest computedAt', () => {
    expect(lastSync('2026-10-24T07:00:00Z', [], null)).toBe('2026-10-24T07:00:00Z');
    expect(lastSync(null, [{ computedAt: '2026-10-22T01:00:00Z' }, { computedAt: '2026-10-23T01:00:00Z' }], null)).toBe('2026-10-23T01:00:00Z');
    expect(lastSync(null, [], null)).toBeNull();
  });

  it('says when, in the zone', () => {
    expect(syncText(null, now, Z)).toEqual({ text: 'Not synced', stale: false });
    expect(syncText('2026-10-24T05:12:00Z', now, Z)).toEqual({ text: 'Synced 10:42', stale: false });
    expect(syncText('2026-10-23T17:00:00Z', now, Z)).toEqual({ text: 'Synced Fri 22:30', stale: false });
    expect(syncText('2026-10-22T05:00:00Z', now, Z)).toEqual({ text: 'Not synced since Thu', stale: true });
  });

  it('sends 30 min to 12 h on Reconnect', () => {
    expect(backfillMinutes(null, now)).toBe(BACKFILL_MIN);
    expect(backfillMinutes('2026-10-24T07:00:00Z', now)).toBe(60);
    expect(backfillMinutes('2026-10-20T07:00:00Z', now)).toBe(BACKFILL_MAX);
  });
});

describe('movement', () => {
  it('reads weeklyActiveMinutes', () => {
    expect(weeklyMinutes({ metrics: { weeklyActiveMinutes: 76.6 } })).toBe(76);
    expect(weeklyMinutes(null)).toBe(0);
  });
});
