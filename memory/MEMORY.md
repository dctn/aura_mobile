# Mobile project memory — index

One fact per file; update the file instead of adding duplicates.

- [Demo app](demo-app.md) — PyQt6 390×844 phone in `demo/`: layout, screens, run, Windows-app rules, the Store that makes taps instant; 24 Oct Figma-style redesign (pills, metric cards, drill pages); Home calendar with task/event editor (PC-style day view)
- [Mobile features map](mobile-features-map.md) — the 18 features, which service serves each, the app action, lab scenario type
- [Sign-in on dev Cognito](cognito-sign-in.md) — windows client, hidden-user quirks, both-codes trick, SES sandbox
- [Dev backend state](dev-backend-state.md) — dev routes all back 22 Oct; Health Data consent copy lags a grant by seconds; the 21 Oct Identity loss
- [Live check](live-check.md) — `scripts/live_check.py`: what it calls and writes, last result
- [Test lab rules](test-lab-rules.md) — past unused dates, 5 computes/hour, direct vs derived, push not set up; 22 Oct fixes (ghost runs, DX-02/DX-03 data, day footprints, check output, 33-day window: band minutes expire 35 d after their time) + how to prove a scenario offline; 23 Oct all-at-once month (16 alerts, needs fresh account + birth date)
- [Planning docs](planning-docs.md) — team's mobile plan, routing, checklist and research in IBT software + the Zoho tabs
- [Demo website](demo-website.md) — demo_website/: React+Vite static site of the phone, live from dev via the read-only aura-dev-demo-site snapshot; edits stay in the visitor's browser; hosted on GitHub Pages from branch demo-website; 25 Oct Today/Recovery/Logging split (Zoho App-log items in Search); 27 Oct Often used + 65 Modes from the Swift app
- [iOS app](aura-ios-app.md) — aura_ios_app/: native SwiftUI iPhone app from the Figma PNGs, branch ios-app; built only by GitHub Actions, tested on stubs, never yet signed in live
