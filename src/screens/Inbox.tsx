// The inbox (profile.py's InboxSheet, as a page): notifications still in the inbox, newest first. Unread ones are
// bold with a blue bell. A tap asks: Mark as read, Snooze 15 min (back in 15 minutes) or Dismiss; each stays in this
// browser (nothing is sent). As in the app, the newest 10.

import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import type { Json } from '../lib/types';
import { Disc, Page } from '../kit/kit';
import { inboxRows, inboxTime, unread } from '../search/logic';
import { useBackLabel } from '../search/parts';
import { isoIn, whenText } from '../search/when';
import '../search/search.css';

const INBOX_SHOWN = 10;

export function Inbox() {
  const aura = useAura();
  const nav = useNav();
  const back = useBackLabel();
  const rows = inboxRows(aura.data.inbox).slice(0, INBOX_SHOWN);
  const count = unread(aura.data.inbox);
  const read = (n: Json) => {
    if (!n.readAt) aura.edit.change('inbox', String(n.id), { readAt: isoIn(aura.zone, new Date()) });
  };
  const readAll = () => rows.forEach(read);
  const pick = (n: Json) => {
    const id = String(n.id);
    nav.showAlert({
      title: String(n.title || 'Aura'),
      buttons: [
        ...(n.readAt ? [] : [{ label: 'Mark as read', onPress: () => read(n) }]),
        { label: 'Snooze 15 min', onPress: () => aura.edit.change('inbox', id, { snoozedUntil: new Date(Date.now() + 15 * 60_000).toISOString() }) },
        { label: 'Dismiss', role: 'destructive' as const, onPress: () => aura.edit.change('inbox', id, { status: 'dismissed' }) },
        { label: 'Cancel', role: 'cancel' as const },
      ],
    });
  };

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
              <button key={String(n.id)} className={`row with-icon sx-note${fresh ? ' unread' : ''}`} onClick={() => pick(n)} aria-label={`${String(n.title ?? '')}${fresh ? ', unread' : ''}`}>
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
