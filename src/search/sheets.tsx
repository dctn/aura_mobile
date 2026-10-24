// Logging's sheets (ui/sheets.py): the 7-question sleep check (DX-03) and "Help is close" after a low mood (NI-02).

import { useState } from 'react';
import { Group, Icon, Row, SheetFrame, Switch } from '../kit/kit';
import { QUESTIONS } from './logic';
import { ProgressLine } from './parts';

/** The Insomnia Severity Index, one question a step; `onDone` gets the seven 0-4 answers. */
export function SleepCheckSheet({ onDone, close }: { onDone: (answers: number[]) => void; close: () => void }) {
  const [step, setStep] = useState(0);
  const [picked, setPicked] = useState<(number | null)[]>(() => QUESTIONS.map(() => null));
  const n = QUESTIONS.length;
  const [question, words] = QUESTIONS[step];
  const chosen = picked[step];
  const last = step === n - 1;

  const next = () => {
    if (chosen === null) return;
    if (!last) {
      setStep(step + 1);
      return;
    }
    close();
    onDone(picked.map((v) => v ?? 0));
  };
  const back = () => (step === 0 ? close() : setStep(step - 1));

  return (
    <SheetFrame title={`${step + 1} of ${n}`} cancel={step === 0 ? 'Cancel' : 'Back'} onCancel={back} action={last ? 'See result' : 'Next'} onAction={next} actionDisabled={chosen === null}>
      <ProgressLine share={(step + 1) / n} />
      <div className="sx-question">
        <div className="sx-kicker">{step < 3 ? 'SLEEP CHECK' : 'HOW IT AFFECTS YOU'}</div>
        <div className="sx-qtext">{question}</div>
        {step === 0 && <div className="muted">Over the last two weeks. About 2 minutes in all.</div>}
      </div>
      <Group>
        {words.map((word, i) => (
          <Row
            key={word}
            title={word}
            trailing={chosen === i ? <span className="sx-check"><Icon name="check" size={18} stroke={2.4} /></span> : undefined}
            onClick={() => setPicked(picked.map((v, j) => (j === step ? i : v)))}
          />
        ))}
      </Group>
    </SheetFrame>
  );
}

const HELP: [string, string, string][] = [
  ['Talk to someone you trust', 'A friend, family member or colleague. Saying it out loud helps.', 'people'],
  ['Tele-MANAS · 14416', 'Free, 24×7, in many Indian languages (Government of India).', 'bubble'],
  ['iCall · +91 9152987821', 'Counsellors, Monday to Saturday, 10 am to 8 pm.', 'bubble'],
  ['Emergency · 112', 'If you feel unsafe or might hurt yourself, call now.', 'shield'],
];

/** NI-02: shown after a low mood, or when mood has been low most of the week. OK with the switch on adds a task. */
export function HelpSheet({ weekLow, onReachOut, close }: { weekLow: boolean; onReachOut: () => void; close: () => void }) {
  const [reach, setReach] = useState(true);
  return (
    <SheetFrame title="Help is close" cancel="Not now" onCancel={close} action="OK" onAction={() => { close(); if (reach) onReachOut(); }}>
      <div className="sx-intro">
        {weekLow ? "Your mood has been low most of this week. That's worth taking seriously." : "Thanks for telling us. You don't have to handle a low day alone."}
      </div>
      <Group heading="Reach out">
        {HELP.map(([title, detail, icon], i) => (
          <Row key={title} title={title} sub={detail} icon={icon} accent={i === HELP.length - 1 ? 'workout' : 'recovery'} disc look={i === HELP.length - 1 ? 'danger' : undefined} />
        ))}
      </Group>
      <Group footer="A small step for today.">
        <Row title="Add 'Reach out to someone'" sub="to today's tasks" trailing={<Switch on={reach} onChange={setReach} label="Add a task" />} />
      </Group>
    </SheetFrame>
  );
}
