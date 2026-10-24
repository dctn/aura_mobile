// The inbox (profile.py's InboxSheet, as a page): notifications still in the inbox, newest first. Unread ones are
// bold with a blue bell; a tap marks one read in this browser (nothing is sent).

import { useAura } from '../app/store';
import type { Json } from '../lib/types';
import { Disc, Page } from '../kit/kit';
import { inboxRows, inboxTime, unread } from '../search/logic';
import { useBackLabel } from '../search/parts';
import { isoIn, whenText } from '../search/when';
import '../search/search.css';

export function Inbox() {
  const aura = useAura();
  const back = useBackLabel();
  const rows = inboxRows(aura.data.inbox);
  const count = unread(aura.data.inbox);
  const read = (n: Json) => {
    if (!n.readAt) aura.edit.change('inbox', String(n.id), { readAt: isoIn(aura.zone, new Date()) });
  };
  const readAll = () => rows.forEach(read);

  return (
    <Page
      back={back}
      title="Inbox"
      sub={rows.length ? (count ? `${count} unread` : 'All read') : undefined}
      right={count > 1 ? <button className="link" onClick={readAll}>Read all</button> : undefined}
    >
      {aura.errors.inbox && <div className="muted">{aura.errors.inbox}</div>}
      {rows.length === 0 ? (
        <div className="list"><div className="row"><span className="grow muted">No notifications</span></div></div>
      ) : (
        <div className="list">
          {rows.map((n) => {
            const fresh = !n.readAt;
            return (
              <button key={String(n.id)} className={`row with-icon sx-note${fresh ? ' unread' : ''}`} onClick={() => read(n)} aria-label={`${String(n.title ?? '')}${fresh ? ', unread' : ''}`}>
                <Disc icon="bell" accent={fresh ? 'blue' : 'label3'} size={29} soft />
                <span className="grow">
                  <span className="sx-notehead">
                    <span className="title">{String(n.title || 'Aura')}</span>
                    <span className="small">{whenText(inboxTime(n), aura.today, aura.zone)}</span>
                  </span>
                  {n.body ? <span className="sub">{String(n.body)}</span> : null}
                </span>
                {fresh && <span className="sx-dot" aria-hidden="true" />}
              </button>
            );
          })}
        </div>
      )}
    </Page>
  );
}
