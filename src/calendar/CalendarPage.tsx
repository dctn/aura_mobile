// The calendar on a page of its own, so it gets the whole screen. The hours box opens tall enough to show the whole
// working day at once, with its zones shaded, so Auto-allocate's placements are all visible together — the two-hour
// window on Home is too small to show the feature clearly. Opens from the "Full view" chip on the Home calendar.

import { Page } from '../kit/kit';
import { useBackLabel } from '../search/parts';
import { CalendarCard } from './CalendarCard';

export function CalendarPage() {
  const back = useBackLabel();
  return (
    <Page back={back} title="Calendar">
      <CalendarCard pageHeight={0} full />
    </Page>
  );
}
