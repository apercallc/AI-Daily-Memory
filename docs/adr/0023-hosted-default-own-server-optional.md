# ADR-0023: Hosted Server by Default, Your Own Server Optional

- Status: Accepted
- Date: 2026-10-13
- Amends: ADR-0022 decision 1 and 2 (no server-address field). Everything else in ADR-0022
  stands: Offline or Connected, sign-up and sign-in in the app, sync through the connected
  account, processing and sync on by default.

## Context

ADR-0022 removed every place to type a server so a new person could not pick the wrong one. The
project is open source, though, and people who run their own copy of the backend had no way to
use the app with it. Making them rebuild the app for one constant is the wrong price for a rule
that exists to protect first-time users, who never see Advanced.

## Decision

1. The default is the hosted server, `https://aidailymem.apercallc.com`. A new install points
   there with nothing to type.
2. Under Advanced on the account screen, "Use my own server" takes an address. Only one server
   is active at a time. The address is reduced to an origin and must be https with a real host
   and no credentials (`ServerAddress`, core/recording); anything else is refused and the field
   shows why. "Use the default server" puts the hosted address back.
3. The server can be changed only while offline. A connected phone keeps the server its token
   belongs to, and a token is never sent to a different address than the one it was issued for.
4. A stored link to any valid https origin is kept on load (ADR-0022 dropped all but the hosted
   one). A stored address that is not a valid https origin is still dropped and its token is not
   sent anywhere.
5. Daily-report sync, uploads, key registration and connection tests already use the link's own
   address, so they follow whichever server the person connected to.

## Own AI provider (Advanced)

The optional own-provider path no longer asks for raw addresses first. Each stage has a Provider
list that fills the address and a model list for it, plus Custom for anything else, and a link to
where that provider issues keys. Turning the path on fills the first provider in each list, so
only the key is left to enter. Only providers whose request format the app already speaks are
listed: Deepgram for transcription, and OpenAI-style chat completions (OpenAI, Anthropic, Groq,
OpenRouter) for the summary. Model lists are suggestions; any model name can be typed.

## Consequences

- A person who types a server address is trusted with their own recordings' destination, so the
  disclosure text names the server they chose instead of the hosted one.
- Revoking a token still happens at the server that issued it, wherever that is.
- Preset addresses and model names are data in `ProviderPresets` and will need occasional updates.
