// Search → Logging: the logbook of everything you note by hand that isn't one of the 18 scientific features (those,
// with water, mood, connection, strength minutes and the sleep check, are in Recovery). A row per category with
// today's entry count opens its page (logbook/LogbookPage.tsx); under them, this week's entries, newest first.
// Everything is added in this browser only.

import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import { Group, Page, Row } from '../kit/kit';
import { useBackLabel } from '../search/parts';
import { whenText } from '../search/when';
import { BY_KIND, CATEGORIES, categoryOf } from '../logbook/kinds';
import { entries, entryAt, entryText, todayCount } from '../logbook/derive';
import '../logbook/logbook.css';

export function Logging() {
  const aura = useAura();
  const nav = useNav();
  const back = useBackLabel();
  const { logs } = aura.data;
  const { today, zone } = aura;
  const week = entries(logs, today, zone, 7).slice(0, 12);

  return (
    <Page back={back} title="Logging">
      <Group>
        <Row
          title="Now in Recovery"
          sub="Water, mood, connection, strength and the sleep check"
          icon="recover"
          accent="recovery"
          disc
          chevron
          onClick={() => nav.setTab('recovery')}
        />
      </Group>

      <Group heading="Categories">
        {CATEGORIES.map((c) => {
          const n = todayCount(logs, today, zone, c.id);
          return (
            <Row
              key={c.id}
              title={c.label}
              icon={c.icon}
              accent={c.accent}
              disc
              value={n ? `${n} today` : undefined}
              chevron
              onClick={() => nav.push({ name: 'logbook', category: c.id })}
            />
          );
        })}
      </Group>

      <Group heading="This week">
        {week.length === 0 ? (
          <Row title={<span className="muted">{aura.errors.logs ?? 'Nothing yet'}</span>} />
        ) : (
          week.map((x) => {
            const cat = categoryOf(BY_KIND[String(x.type)]?.category ?? '');
            return <Row key={String(x.id)} title={entryText(x)} icon={cat?.icon} accent={cat?.accent} disc value={whenText(entryAt(x, zone), today, zone)} />;
          })
        )}
      </Group>
    </Page>
  );
}
