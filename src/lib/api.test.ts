// The snapshot check and the saved copy (a reload draws at once; an outage still shows the demo).
import { beforeEach, describe, expect, it } from 'vitest';
import { API, isSnapshot, savedSnapshot, saveSnapshot, sampleSnapshot } from './api';

describe('snapshot', () => {
  beforeEach(() => localStorage.clear());

  it('runs the tests on the bundled sample, never the live data', () => {
    expect(API).toBe('');
  });

  it('accepts only the snapshot shape', () => {
    expect(isSnapshot({ at: 'x', zone: 'Asia/Kolkata', today: '2026-10-24', parts: {} })).toBe(true);
    expect(isSnapshot({ error: { code: 'unavailable' } })).toBe(false);
    expect(isSnapshot(null)).toBe(false);
  });

  it('keeps the last good answer and ignores a broken one', async () => {
    const sample = await sampleSnapshot();
    saveSnapshot(sample);
    expect(savedSnapshot()?.today).toBe(sample.today);
    localStorage.setItem('aura-demo:snapshot', '"nope"');
    expect(savedSnapshot()).toBeNull();
  });

  it('bundles a sample with no email or token in it', async () => {
    const text = JSON.stringify(await sampleSnapshot());
    expect(text).not.toMatch(/"e-?mail"\s*:/i); // no email field (a task may say "email")
    expect(text).not.toContain('@');
    expect(text).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}/);
  });
});
