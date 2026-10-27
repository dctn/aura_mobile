// Search (ui/pages/search.py): a search box over two lists. "Health" lists the metric categories with a coloured
// glyph, the newest value and a chevron to the metric page; the second list opens Logging, Sessions and Settings.
// Typing also finds any other metric column by its label, and Recovery's Modes (all, or one shelf) by what they hold.

import { useMemo, useState } from 'react';
import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import * as m from '../lib/metrics';
import { Group, Icon, Page, Row, SettingsButton } from '../kit/kit';
import { PLACES, metricFields, newestOf, searchHits, valueText, type Place } from '../search/logic';
import '../search/search.css';

export function Search() {
  const aura = useAura();
  const nav = useNav();
  const [query, setQuery] = useState('');
  const days = aura.data.metrics.days as m.MetricDay[];
  const fields = useMemo(() => metricFields(days), [days]);
  const hits = searchHits(query, fields);
  const nothing = query.trim() !== '' && !hits.categories.length && !hits.places.length && !hits.fields.length;

  // a place on another tab (Recovery's Modes) opens on that tab, so its back link reads "‹ Recovery"
  const open = (p: Place) => {
    if (p.tab && p.tab !== nav.tab) nav.setTab(p.tab);
    nav.push(p.page);
  };

  const valueOf = (field: string) => {
    const newest = newestOf(days, field);
    return newest ? valueText(m.cardFor(field), newest.value) : '';
  };

  return (
    <Page root title="Search" right={<SettingsButton />}>
      <label className="searchbox sx-search">
        <Icon name="search" size={17} stroke={2} />
        <input type="search" placeholder="Search" value={query} onChange={(e) => setQuery(e.target.value)} aria-label="Search" />
        {query && (
          <button className="sx-clear" aria-label="Clear" onClick={() => setQuery('')}>
            <Icon name="xmark" size={14} stroke={2.4} />
          </button>
        )}
      </label>

      {nothing && <div className="muted sx-nothing">No results for “{query.trim()}”</div>}

      {hits.categories.length > 0 && (
        <Group heading="Health">
          {hits.categories.map((key) => {
            const card = m.CARDS[key];
            return (
              <Row key={key} title={card.label} icon={card.icon} accent={card.accent} value={valueOf(card.field)} chevron onClick={() => nav.push({ name: 'metric', field: card.field })} />
            );
          })}
        </Group>
      )}

      {hits.fields.length > 0 && (
        <Group heading="Metrics">
          {hits.fields.map((field) => {
            const card = m.cardFor(field);
            return <Row key={field} title={card.label} icon={card.icon} accent={card.accent} value={valueOf(field)} chevron onClick={() => nav.push({ name: 'metric', field })} />;
          })}
        </Group>
      )}

      {hits.places.length > 0 && (
        <Group>
          {PLACES.filter((p) => hits.places.includes(p.name)).map((p) => (
            <Row
              key={p.name}
              title={p.name}
              icon={p.icon}
              accent={p.accent}
              value={p.name === 'Sessions' && aura.data.active ? 'Running' : undefined}
              chevron
              onClick={() => open(p)}
            />
          ))}
        </Group>
      )}
    </Page>
  );
}
