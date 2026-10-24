import { describe, expect, it } from 'vitest';
import * as C from './clock';

describe('clockText', () => {
  it('shows minutes and seconds, rounded up', () => {
    expect(C.clockText(20 * 60)).toBe('20:00');
    expect(C.clockText(59.2)).toBe('01:00');
    expect(C.clockText(59)).toBe('00:59');
    expect(C.clockText(0)).toBe('00:00');
    expect(C.clockText(-3)).toBe('00:00');
    expect(C.clockText(125 * 60)).toBe('125:00');
  });
});

describe('minutesLeftText', () => {
  it('says what is left', () => {
    expect(C.minutesLeftText(30)).toBe('less than a minute left');
    expect(C.minutesLeftText(60)).toBe('1 minute left');
    expect(C.minutesLeftText(61)).toBe('2 minutes left');
  });
});

describe('the clock', () => {
  const t0 = 1_000_000;
  it('counts down from a wall-clock start', () => {
    const c = C.startClock(300, t0);
    expect(C.left(c, t0)).toBe(300);
    expect(C.left(c, t0 + 10_000)).toBe(290);
    expect(C.progress(c, t0 + 150_000)).toBe(0.5);
    expect(C.isUp(c, t0 + 299_000)).toBe(false);
    expect(C.isUp(c, t0 + 300_000)).toBe(true);
    expect(C.left(c, t0 + 400_000)).toBe(0);
  });

  it('stops while paused and adds the pauses up', () => {
    let c = C.startClock(300, t0);
    c = C.pause(c, t0 + 10_000);
    expect(C.left(c, t0 + 60_000)).toBe(290);
    expect(C.statusText(c, t0 + 60_000)).toBe('Paused · 04:50');
    c = C.resume(c, t0 + 60_000);
    expect(C.left(c, t0 + 70_000)).toBe(280);
    c = C.pause(C.resume(C.pause(c, t0 + 70_000), t0 + 80_000), t0 + 90_000);
    expect(C.elapsed(c, t0 + 1_000_000)).toBe(30);
    expect(C.pause(c, t0 + 95_000)).toBe(c); // pausing twice changes nothing
  });

  it('counts up when open-ended', () => {
    const c = C.startClock(0, t0);
    expect(C.openEnded(c)).toBe(true);
    expect(C.clockSeconds(c, t0 + 65_000)).toBe(65);
    expect(C.isUp(c, t0 + 10 ** 9)).toBe(false);
    expect(C.progress(c, t0 + 65_000)).toBe(0);
    expect(C.statusText(c, t0 + 65_000)).toBe('Running · 01:05');
  });

  it('says the time left in the mini bar', () => {
    expect(C.statusText(C.startClock(600, t0), t0 + 1_000)).toBe('09:59 left');
  });
});

describe('kicker', () => {
  it('names the kind and length', () => {
    expect(C.kicker('walk', 20)).toBe('MOVE · 20 MIN');
    expect(C.kicker('recovery', 5, true)).toBe('BREATHE · 5 MIN');
    expect(C.kicker('focus', 0)).toBe('FOCUS · OPEN');
    expect(C.kicker('', 10)).toBe('SESSION · 10 MIN');
  });
});

describe('breath', () => {
  it('grows for 5 s, shrinks for 5 s', () => {
    expect(C.breath(0)).toEqual({ inhaling: true, scale: C.BREATH_SMALL, text: 'Breathe in' });
    expect(C.breath(5).inhaling).toBe(false);
    expect(C.breath(5).scale).toBeCloseTo(1);
    expect(C.breath(2.5).scale).toBeCloseTo((1 + C.BREATH_SMALL) / 2);
    expect(C.breath(10).scale).toBeCloseTo(C.BREATH_SMALL);
    expect(C.breath(7.5).text).toBe('Breathe out');
  });
});
