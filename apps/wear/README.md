# Wear OS

Implemented: the watch is a remote control for the phone, showing phone-acknowledged
state only (ADR-0008). Shipped via the Wear Data Layer (commands via MessageClient,
revision-ordered state snapshots via DataClient):

- Start / Pause / Resume / Stop
- Highlight and Quick Note
- Recording health and phone connectivity
- Command acknowledgement / timeout UX (a command that cannot reach the phone shows as
  unconfirmed, never as success)

Remaining before the phase exits: session duration on the watch face, watch battery
benchmark, accessibility pass, and the physical OnePlus Watch 2 endurance tests.

Do not implement standalone watch recording until phone-first reliability and watch battery behavior are measured.
