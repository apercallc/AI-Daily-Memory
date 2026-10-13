# First-install journey

A walk through what a new person sees, in order, with what each step needs to do and what was
wrong before 2026-10-13. Written from the code and the unit and UI tests. **Not yet checked on a
physical phone and watch**; the "Device check" column is the list to tick off.

| # | Step | What happens now | Was wrong before | Device check |
|---|------|------------------|------------------|--------------|
| 1 | Open the app | A four-step walkthrough: what it does, Offline or Connect an account (Offline preselected), which permissions Android will ask for, how to start. Skip works on every step. | Straight to Today with no explanation. | ☐ |
| 2 | Choose Offline | Walkthrough ends on Today. Nothing is sent anywhere. | There was no clear "offline" choice. | ☐ |
| 2b | Choose Connect | Walkthrough ends on Settings, Account & server, with Create account / Sign in and a link to aidailymem.apercallc.com. | The account form asked for a server address and a token that nothing in the app could create. | ☐ |
| 3 | Tap Start | Microphone prompt with a one-line reason, then the notifications prompt (so the recording indicator can show). A denial explains and offers the system settings. | Unchanged. | ☐ |
| 4 | Battery card | Today shows a card with the right steps for the phone brand until battery optimization is off. | Unchanged. | ☐ |
| 5 | Recording | Indicator visible, Pause and End session on the phone. Under the status card: "Started on this phone". | No origin shown. | ☐ |
| 6 | Pair a watch | Watch shows phone status and Start / Pause / Stop. | Unchanged. | ☐ |
| 7 | Start from the watch | Phone records. The phone shows "Recording started from your watch" and "Started from your watch". The watch shows "Started from this watch". | Phone could not tell who started it. | ☐ |
| 8 | Start on both at once | One wins. The other device explains: "Already recording…". | Watch showed "Phone rejected the command"; phone showed "That action isn't available". | ☐ |
| 9 | Pause or stop from either | Applies, and the other device updates; the phone says "Paused from your watch". | Phone was silent when the watch paused it. | ☐ |
| 10 | Watch out of range | Commands show "not confirmed" with Try again or Dismiss. The phone keeps recording. | Unchanged. | ☐ |
| 11 | Settings | Appearance (Match my phone default), Account & server, Memory processing and Daily reports (both on by default), Test connection with a toast. | Processing off by default; three server fields. | ☐ |
| 12 | Connect, then Test connection | Toast: "Connected to aidailymem.apercallc.com and saved." Test connection later gives a toast for each outcome. | No test, no confirmation. | ☐ |
| 13 | Day ends, phone charging on Wi-Fi | Voices are cut out of the day, silence, noise, instruments and nature sound are left out, and the clips are processed on the hosted server. | Any loud sound counted as speech. | ☐ |

Known gaps this walkthrough does not close: the hosted server's sign-up page and its pricing are
outside this repository; the voice detector has only been run on synthetic audio.
