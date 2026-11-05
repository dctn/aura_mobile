// The zones page: today's calendar on a page of its own, so the whole working day shows at once with its shades and
// tasks. Below it, the day as a list, what each shade means and, for this preview, the score. Also opens from the
// address #zones. The tasks here are to look at; they're moved and edited on Home.

import { useMemo, type CSSProperties } from 'react';
import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import { Group, Page, Row, Segmented } from '../kit/kit';
import * as A from '../lib/agenda';
import { DEFAULT_PREVIEW_SCORE, PREVIEW_SCORES } from '../lib/local';
import { TitledGroup, useBackLabel } from '../search/parts';
import { GUTTER, HOUR, PAD, blocks, endOf, lookTone, spanText, yOf } from './layout';
import { WorkHoursSheet } from './WorkHoursSheet';
import { ZONE_KINDS, ZONE_MEANING, ZONE_NAMES, asSchedule, hoursRange, zoneDay, zoneReason, type DayZone } from './dayZones';
import '../search/search.css';
import './calendar.css';

/** Only a stretch this long has room for its name on the shade. */
const LABEL_FROM_MINUTES = 30;

const toneVar = (token: string) => ({ ['--tone' as string]: `var(--${token})` }) as CSSProperties;

/** The day's shades, behind the hour lines. They take no taps. `shift`: how far the column's top is cut off. */
export function Shades({ zones, shift = 0 }: { zones: DayZone[]; shift?: number }) {
  return (
    <div className="cal-shades" style={{ left: GUTTER }} aria-hidden="true">
      {zones.map((z) => (
        <div key={z.start} className={`cal-shade ${z.kind}`} style={{ top: yOf(z.start) - shift, height: yOf(z.end) - yOf(z.start) }}>
          {z.end - z.start >= LABEL_FROM_MINUTES && <span>{ZONE_NAMES[z.kind]}</span>}
        </div>
      ))}
    </div>
  );
}

/** Only the hours around the working day, with no box of its own to scroll, so the page shows it whole. */
function WholeDay({ zones, items, now }: { zones: DayZone[]; items: A.Item[]; now: number | null }) {
  const [from, to] = hoursRange(zones);
  const shift = yOf(from * 60) - PAD;
  const visible = items.filter((i) => i.start !== null && endOf(i) > from * 60 && i.start < to * 60);
  const inside = now !== null && now >= from * 60 && now <= to * 60;

  return (
    <div className="card cal-card">
      <div className="cal-day" style={{ height: (to - from) * HOUR + 2 * PAD }}>
        <Shades zones={zones} shift={shift} />
        {Array.from({ length: to - from + 1 }, (_, i) => (
          <div key={i} className="cal-hour" style={{ top: PAD + i * HOUR }}>
            {from + i < 24 && <span className="cal-hlabel" style={{ width: GUTTER - 8 }}>{`${String(from + i).padStart(2, '0')}:00`}</span>}
            <span className="cal-hline" style={{ left: GUTTER }} />
          </div>
        ))}
        <div className="cal-lanes cal-lanes-still" style={{ left: GUTTER + 4 }}>
          {blocks(visible).map((b) => (
            <div
              key={b.key}
              className={`cal-block${b.item.done ? ' done' : ''}${b.short ? ' short' : ''}`}
              style={{
                ...toneVar(lookTone(b.item.look)),
                top: b.top - shift,
                height: b.height,
                left: `${(b.lane / b.lanes) * 100}%`,
                width: `calc(${100 / b.lanes}% - 3px)`,
              }}
              title={`${b.item.title} · ${spanText(b.item)}`}
            >
              <span className="cal-btext">
                <span className="cal-btitle">{b.short ? `${b.item.title} · ${A.clock(b.item.start ?? 0)}` : b.item.title}</span>
                {!b.short && <span className="cal-bspan">{spanText(b.item)}</span>}
              </span>
            </div>
          ))}
        </div>
        {inside && now !== null && (
          <div className="cal-now" style={{ top: yOf(now) - shift, left: GUTTER }} aria-hidden="true">
            <i />
          </div>
        )}
      </div>
    </div>
  );
}

export function ZonesPage() {
  const aura = useAura();
  const nav = useNav();
  const back = useBackLabel();
  const { today, zone } = aura;
  const { events, tasks } = aura.data;
  const schedule = asSchedule(aura.data.me.workSchedule);
  const score = aura.local.previewScore ?? DEFAULT_PREVIEW_SCORE;
  const day = zoneDay(schedule, score, today);
  const items = useMemo(() => A.dayItems(today, events, tasks, zone, today).timed, [events, tasks, zone, today]);
  const now = A.localParts(aura.now.toISOString(), zone)?.minute ?? null;
  const openHours = () => nav.openSheet({ render: (close) => <WorkHoursSheet close={close} /> });

  const note =
    day.status === 'needsSchedule' ? 'Set your work hours to see your zones.'
    : day.status === 'dayOff' ? 'Today is not one of your work days, so nothing is shaded.'
    : zoneReason(score, day.heavyMinutes);

  return (
    <Page back={back} title="Zones">
      <p className="cal-sheetnote">{note}</p>

      {day.status === 'ready' && <WholeDay zones={day.zones} items={items} now={now} />}

      <div className="cal-previewscore">
        <span className="cal-previewlabel">Preview score</span>
        <Segmented
          options={PREVIEW_SCORES.map((s) => ({ value: String(s), label: String(s) }))}
          value={String(score)}
          onChange={(v) => aura.edit.previewScore(Number(v))}
        />
        <span className="cal-previewhint">For trying the shades only. The real capacity score replaces this.</span>
      </div>

      {day.status === 'ready' && (
        <TitledGroup title="Your day">
          {day.zones.map((z) => (
            <Row key={z.start} title={ZONE_NAMES[z.kind]} value={`${A.clock(z.start)}–${A.clock(z.end)}`} trailing={<i className={`cal-swatch ${z.kind}`} aria-hidden="true" />} />
          ))}
        </TitledGroup>
      )}

      <TitledGroup title="What each shade means">
        {ZONE_KINDS.map((kind) => (
          <Row key={kind} title={ZONE_NAMES[kind]} value={ZONE_MEANING[kind]} trailing={<i className={`cal-swatch ${kind}`} aria-hidden="true" />} />
        ))}
      </TitledGroup>

      <Group>
        <Row title="Work hours" value={schedule ? `${schedule.workdayStart}–${schedule.workdayEnd}` : 'Not set'} chevron onClick={openHours} />
      </Group>
    </Page>
  );
}
