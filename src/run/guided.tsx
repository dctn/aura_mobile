// A guided meditation's prompts under the clock (through Run.panel): one prompt at a time, moving on every
// (minutes × 60) / n seconds of run time, so the last one shows for the closing stretch. RunView shares the seconds
// run so far through RunElapsed; the panel only reads them.

import { createContext, useContext, type ReactNode } from 'react';

/** Seconds the run on screen has run so far, not counting pauses (RunView provides it around the panel). */
export const RunElapsed = createContext(0);

/** The prompt for `seconds` into a run of `minutes` (0-based `index`; open-ended or empty: the first). */
export function promptAt(prompts: string[], minutes: number, seconds: number): { index: number; n: number; text: string } {
  const n = prompts.length;
  if (!n) return { index: 0, n: 0, text: '' };
  const step = (minutes * 60) / n;
  const index = step > 0 ? Math.min(n - 1, Math.max(0, Math.floor(seconds / step))) : 0;
  return { index, n, text: prompts[index] };
}

function GuidedPanel({ prompts, minutes }: { prompts: string[]; minutes: number }) {
  const seconds = useContext(RunElapsed);
  const { index, n, text } = promptAt(prompts, minutes, seconds);
  if (!n) return null;
  return (
    <div className="rv-guide" aria-live="polite">
      <div className="rv-guide-n">{index + 1} of {n}</div>
      <div className="rv-guide-text">{text}</div>
    </div>
  );
}

export const guidedPanel = (prompts: string[], minutes: number): ReactNode => <GuidedPanel prompts={prompts} minutes={minutes} />;
