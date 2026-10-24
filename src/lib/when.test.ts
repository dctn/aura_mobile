import { describe, expect, it } from 'vitest';
import { minutesBetween, nowIn, offsetMinutes, offsetText, shiftIso, wallIso } from './when';

describe('offsets', () => {
  it('reads a zone offset from Intl', () => {
    expect(offsetMinutes('Asia/Kolkata', new Date('2026-10-24T07:59:09.316Z'))).toBe(330);
    expect(offsetMinutes('UTC', new Date('2026-10-24T07:59:09Z'))).toBe(0);
    // New York: summer time in July, standard time in December
    expect(offsetMinutes('America/New_York', new Date('2026-07-01T12:00:00Z'))).toBe(-240);
    expect(offsetMinutes('America/New_York', new Date('2026-12-01T12:00:00Z'))).toBe(-300);
  });

  it('treats an unknown zone as UTC', () => {
    expect(offsetMinutes('Not/AZone', new Date())).toBe(0);
  });

  it('writes the offset as +hh:mm', () => {
    expect(offsetText(330)).toBe('+05:30');
    expect(offsetText(-240)).toBe('-04:00');
    expect(offsetText(0)).toBe('+00:00');
  });
});

describe('wallIso', () => {
  it('makes a wall time in the zone', () => {
    expect(wallIso('Asia/Kolkata', '2026-10-24', 18 * 60)).toBe('2026-10-24T18:00:00+05:30');
    expect(new Date(wallIso('Asia/Kolkata', '2026-10-24', 18 * 60)).toISOString()).toBe('2026-10-24T12:30:00.000Z');
  });

  it('rolls minutes past midnight into the next day', () => {
    expect(wallIso('Asia/Kolkata', '2026-10-31', 23 * 60 + 90)).toBe('2026-11-01T00:30:00+05:30');
  });

  it('uses the offset in force on that day', () => {
    expect(wallIso('America/New_York', '2026-07-04', 9 * 60)).toBe('2026-07-04T09:00:00-04:00');
    expect(wallIso('America/New_York', '2026-12-04', 9 * 60)).toBe('2026-12-04T09:00:00-05:00');
  });
});

describe('nowIn', () => {
  it('is the time of day in the zone, on the demo day when given', () => {
    const at = new Date('2026-10-24T07:59:09Z'); // 13:29 in Kolkata
    expect(nowIn('Asia/Kolkata', undefined, at)).toEqual({ day: '2026-10-24', minute: 13 * 60 + 29, second: 9, iso: '2026-10-24T13:29:09+05:30' });
    expect(nowIn('Asia/Kolkata', '2026-09-30', at).iso).toBe('2026-09-30T13:29:09+05:30');
  });
});

describe('shiftIso', () => {
  it('keeps the wall time a day later', () => {
    expect(shiftIso('2026-10-24T18:00:00+05:30', 1, 'Asia/Kolkata')).toBe('2026-10-25T18:00:00+05:30');
    expect(shiftIso('2026-10-24T12:30:00.000Z', 1, 'Asia/Kolkata')).toBe('2026-10-25T18:00:00+05:30');
  });

  it('leaves what it cannot read', () => {
    expect(shiftIso('soon', 1, 'Asia/Kolkata')).toBe('soon');
    expect(shiftIso(null, 1, 'Asia/Kolkata')).toBe(null);
  });
});

describe('minutesBetween', () => {
  it('counts whole minutes', () => {
    expect(minutesBetween('2026-10-24T18:00:00+05:30', '2026-10-24T18:40:00+05:30')).toBe(40);
    expect(minutesBetween('x', '2026-10-24T18:40:00+05:30')).toBe(0);
  });
});
