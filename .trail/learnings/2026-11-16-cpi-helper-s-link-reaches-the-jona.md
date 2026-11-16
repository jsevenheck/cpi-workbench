---
title: "CPI Helper's link reaches the desktop app through an opt-in local receiver with a confirmation, not a custom link scheme"
author: Jona
date: 2026-11-16
branch: claude/nice-turing-nql3ja
cost: { minutes: 1, tokens: 1632 }
id: l-d69e1859
scope: repo
status: active
src: agent
touches:
  - docs/plan.md
  - app/src/lib/share.ts
---

## Decided
- Pasting CPI Helper's link into Import… works but is not intuitive: the plugin's tab shows "This site can't be reached" and the user must copy its address. The user asked for one click into the desktop app, with a confirmation in the app.
- The way in: the desktop app serves a small page on 127.0.0.1:<port> that reads the #share/ part and POSTs it to the app on the same origin; the app asks in a dialog (Import / Cancel) and runs nothing. Off by default under the settings, because the desktop app otherwise opens no port.
- A body that starts like XML or JSON and does not parse gets a "may have been cut off" warning, on this path and on a pasted link; it is still imported.
- All user-facing text in English (the user asked explicitly).

## Tried and ruled out
- A cpi-workbench:// link (Tauri deep-link + single-instance): on Windows the link arrives on the command line, which ends at 32,767 characters, so large traces, the ones that matter, would not arrive.

## Watch out
- A large traced body can arrive cut off before the link is built (the plugin files cut nothing; a link cut on the way fails to unpack in the app). Where it is cut is Q-T3 in the plan.
- Any web page can open the receiver's link, so the dialog must not claim CPI Helper as the sender.

In the code: docs/plan.md W2b and Q-T3; app/src/lib/share.ts is the one parser both paths use; RpcServer's Host/Origin checks are the model for the receiver.
