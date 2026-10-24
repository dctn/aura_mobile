import { describe, expect, it } from 'vitest';
import { localParts } from '../lib/agenda';
import { isoInZone, longDay, monthTitle, shortDay, zoneOffset, zonedIso, zonedMs } from './zone';

describe('zone helpers', () => {
  it('builds an ISO string with the zone offset, not the browser one', () => {
    expect(zonedIso('2026-10-24', 15 * 60, 'Asia/Kolkata')).toBe('2026-10-24T15:00:00+05:30');
    expect(zonedIso('2026-10-24', 0, 'Asia/Kolkata')).toBe('2026-10-24T00:00:00+05:30');
    expect(zonedIso('2026-07-01', 9 * 60 + 5, 'Europe/London')).toBe('2026-07-01T09:05:00+01:00');
    expect(zonedIso('2026-01-15', 23 * 60 + 59, 'America/Los_Angeles')).toBe('2026-01-15T23:59:00-08:00');
    expect(zonedIso('2026-10-24', 600, 'UTC')).toBe('2026-10-24T10:00:00+00:00');
  });

  it('reads back to the same day and minute through agenda.localParts', () => {
    for (const zone of ['Asia/Kolkata', 'America/New_York', 'Australia/Adelaide', 'Pacific/Kiritimati']) {
      for (const minute of [0, 30, 8 * 60, 13 * 60 + 15, 23 * 60 + 45]) {
        expect(localParts(zonedIso('2026-10-24', minute, zone), zone)).toEqual({ day: '2026-10-24', minute });
      }
    }
  });

  it('gets the offset right on both sides of a DST change', () => {
    // US clocks go forward at 02:00 on 8 March 2026
    expect(zonedIso('2026-03-08', 60, 'America/New_York')).toBe('2026-03-08T01:00:00-05:00');
    expect(zonedIso('2026-03-08', 3 * 60, 'America/New_York')).toBe('2026-03-08T03:00:00-04:00');
    // and back at 02:00 on 1 November 2026
    expect(zonedIso('2026-11-01', 3 * 60, 'America/New_York')).toBe('2026-11-01T03:00:00-05:00');
  });

  it('rolls minutes past midnight into the next day', () => {
    expect(zonedIso('2026-10-31', 24 * 60 + 30, 'Asia/Kolkata')).toBe('2026-11-01T00:30:00+05:30');
  });

  it('formats a moment in the zone', () => {
    const ms = Date.parse('2026-10-24T07:59:09.316Z');
    expect(isoInZone(ms, 'Asia/Kolkata')).toBe('2026-10-24T13:29:09+05:30');
    expect(zoneOffset(ms, 'Asia/Kolkata')).toBe(330);
    expect(zonedMs('2026-10-24', 13 * 60 + 29, 'Asia/Kolkata')).toBe(Date.parse('2026-10-24T07:59:00Z'));
  });

  it('writes the day labels', () => {
    expect(shortDay('2026-10-24')).toBe('Sat 24 Oct');
    expect(longDay('2026-10-24')).toBe('Saturday 24 October');
    expect(monthTitle('2026-10-24')).toBe('Oct 2026');
  });
});
