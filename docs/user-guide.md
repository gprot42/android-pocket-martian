# Pocket Martian user guide

How to use the app. For the document scanner and UK train times there are separate
guides: [Document scanner](scanner.md) and [UK trains and transport](uk-trains.md).

## Getting started

1. Install the latest APK from the [releases page](https://github.com/gprot42/android-pocket-martian/releases).
2. Create an API key at [console.x.ai](https://console.x.ai) and add prepaid **API credits**.
3. In the app, open **Settings → Account**, paste the key and tap **Save**.

### API credits, not SuperGrok

Pocket Martian is not the official Grok app. It talks to `https://api.x.ai` with your own
API key, and every request is paid from your API credits.

| Product | Where | What it pays for |
| --- | --- | --- |
| **SuperGrok / SuperGrok Heavy** | grok.com, X apps | Chat limits in those apps |
| **xAI API credits** | [console.x.ai → Billing](https://console.x.ai/team/default/billing) | Everything this app does: text, pictures, web search, voice |

A SuperGrok subscription does not fund this app. If you see "out of credits", top up API
credits or turn on auto top-up. Prompts with photos cost more, so a low balance often runs
out first when you attach one.

## Chatting

- **Models:** Grok 4.7 (default) or 4.6, in **Settings → Model & replies**. If 4.7 is
  unavailable, 4.6 is used. Grok 4.7 Fast is not offered: xAI serves it only
  through Cursor and Grok Build, not the public API.
- **Web search:** when a question depends on recent facts, Grok searches the web by itself.
  Sources appear as chips under the answer: tap to open, hold to copy.
- **While you wait:** the answer streams in as Grok writes it, with "Searching the web…"
  while it looks things up.
- **Stop and queue:** the round button sends. While a reply arrives it stops the reply, or,
  if you've typed something, queues it to send when the reply lands.
- **Your messages** are bubbles on the right; hold one to copy it. Each answer has copy and
  share icons. **New chat** clears the conversation and offers **Undo**.
- **The conversation is saved** on the phone and comes back when the app reopens, even
  after a crash. **New chat** removes the saved copy. The ⋮ menu has **History** of recent
  answers, **Share conversation** and **Resend last prompt**.
- **Share in:** share text, links or pictures to Pocket Martian from any app, or select text
  anywhere and choose **Ask Pocket Martian**.
- **Pinch to zoom** the chat. Themes (Light, Dark, Tokyo Night) and text size are under
  **Settings → Theme & text**.

## Speaking

- **Dictation:** tap the microphone in the prompt box, speak, then tap it again (or
  **Done**). Grok's speech-to-text turns it into text, added after anything you typed. Any
  language works. Audio is kept only in memory and sent once; listening stops after two
  minutes.
- **Voice translator:** in the ⋮ menu. Speak and hear your words translated in real time
  (Grok Voice API, about $0.05 a minute). Turn it on in **Settings → Voice**.

## Pictures

Ask for a picture ("draw a red sports car at dusk"), or attach a photo and ask for a change
("put me in a Lamborghini"). Grok makes or edits it with Grok Imagine, deciding for itself
when to, as it does with web search.

- The picture appears full width in the answer. Tap it to zoom, then **Save** (to
  Pictures/Pocket Martian) or **Share**.
- A follow-up like "now make it night" edits the last picture.
- Each picture costs US$0.04 on top of the usual tokens.
- **Create images** in **Settings → Model & replies** turns it off.

## Location

With **Use GPS location** on (the default) and permission granted, the app adds your
approximate position to prompts, so you can ask "how do I get from here to King's Cross?"
without naming a station. It's used only for answers, never for tracking. Turn it off in
**Settings → Location**, or deny the permission: chat works the same, you just name where
you are. See [UK trains and transport](uk-trains.md) for how it's used.

## Costs and usage

- **Show cost per query** (**Settings → Model & replies**) shows each answer's estimated cost.
- **Settings → Credits & usage** shows your live API balance and rate limits.
  It needs a management key: in [console.x.ai](https://console.x.ai) → **Settings →
  Management Keys**, create one, paste it under **Settings → Management key**, then
  open **Credits & usage** and tap **Refresh**.
- SuperGrok usage can't be read by an app; check it in the official Grok app.

## Updates

The GitHub version checks this repository's latest release once a day and offers it in a
banner (**Not now** hides it for that version). **Settings → Updates** checks on
demand or turns the check off. Android asks you to confirm the install, and the first time
to allow Pocket Martian to install apps. Updates install over your current version and keep
your settings and chats. The Google Play version is updated by Play instead.

## When something goes wrong

- **"Couldn't reach api.x.ai"** means the connection never opened: usually DNS, a VPN or a
  dead network route rather than xAI being down. The app has already retried a few times,
  looked the address up through DNS-over-HTTPS (Cloudflare, then Google) if the network's
  own DNS failed, and preferred IPv4, which mobile networks route more reliably. Try
  switching between Wi-Fi and mobile data, check **Private DNS** in the phone's network
  settings, or turn off a VPN or ad blocker that filters DNS.
- **"No internet connection"** appears at once when the phone has no network, rather than
  after a long wait.
- **"Connection to api.x.ai dropped"** means it was lost mid-answer. The app doesn't resend
  it automatically, because the request already ran and was billed. Tap **Retry** if you
  want it again (it appears when the prompt box is empty).
- **Logs:** **Settings → Logs** has one timing line per prompt (numbers only, never
  your words), for example
  `grok-4.7 effort=low | request sent 13ms · first text 3.9s · done 7.2s | 1 searches | ok`.
  **Copy All** to share it. **Debug mode** in Settings also records each request and
  response.
- **Settings → About** shows why the app last stopped, on Android 11 and later.

## Privacy

Your key, settings and conversations stay on your phone. What you ask goes to xAI to be
answered. The developer receives nothing. Full details: [privacy policy](privacy.html).
