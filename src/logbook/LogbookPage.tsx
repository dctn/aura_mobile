// Search → Logging → one category (Nutrition, Meetings, Focus, …): what the phone works out first (from the logs, the
// calendar and the sessions), with the items only the desktop app or the band can measure named but left without a
// number; then a card per thing you type in; then this category's entries from the last 7 days.

import { useAura } from '../app/store';
import { Group, Page, Row } from '../kit/kit';
import { useBackLabel } from '../search/parts';
import { whenText } from '../search/when';
import { categoryOf, kindsIn } from './kinds';
import { DERIVED, entries, entryAt, entryText, type Ctx } from './derive';
import { KindForm } from './forms';
import './logbook.css';

/** A line under the computed values saying where they come from. */
const NOTES: Record<string, string> = {
  nutrition: 'From the meals you log today. The calorie goal is the one in your profile, else 2,000 kcal.',
  meetings: "From your calendar's meetings today. A free block is 90 minutes or more between 9:00 and 18:00; the maker score is the share of that day in free stretches of 2 hours or more.",
  focus: 'From the phone’s Timer and Flowtime sessions. Deep work is a session of 25 minutes or more; best and worst days are over the last 30 days and today.',
  worklife: 'Over the last 7 days. The boundary score is the share of days with no focus or meeting before 7:00 or after 19:00; overwork is 9 hours or more of both in a day.',
  body: 'BMI uses your newest weight entry, else the weight and height in your profile.',
  lifting: 'Over the last 7 days. Volume is sets × reps × weight; strength days include Recovery’s strength minutes.',
  habits: 'The desktop app and the band work these out; they show here once they sync.',
};

export function LogbookPage({ category }: { category: string }) {
  const aura = useAura();
  const back = useBackLabel();
  const { logs, events, sessions, me } = aura.data;
  const ctx: Ctx = { logs, events, sessions, me, today: aura.today, zone: aura.zone };
  const kinds = kindsIn(category);
  const computed = [...kinds.filter((k) => k.derive), ...kinds.filter((k) => k.source)];
  const forms = kinds.filter((k) => k.input);
  const mine = entries(logs, aura.today, aura.zone, 7, category);

  return (
    <Page back={back} title={categoryOf(category)?.label ?? 'Logging'}>
      {computed.length > 0 && (
        // a wrapper so long values (the muscle groups) can wrap instead of pushing the row past the phone's width
        <div className="lb-computed">
          <Group footer={NOTES[category]}>
            {computed.map((k) =>
              k.derive ? (
                <Row key={k.id} title={k.label} value={DERIVED[k.id]?.(ctx) ?? ''} />
              ) : (
                <Row key={k.id} title={k.label} sub={k.source === 'band' ? 'From the band' : 'From the desktop app'} />
              ),
            )}
          </Group>
        </div>
      )}

      {forms.map((k) => <KindForm key={k.id} kind={k} />)}

      {forms.length > 0 && (
        <Group heading="This week">
          {mine.length === 0 ? (
            <Row title={<span className="muted">Nothing yet</span>} />
          ) : (
            mine.slice(0, 20).map((x) => <Row key={String(x.id)} title={entryText(x)} value={whenText(entryAt(x, aura.zone), aura.today, aura.zone)} />)
          )}
        </Group>
      )}
    </Page>
  );
}
