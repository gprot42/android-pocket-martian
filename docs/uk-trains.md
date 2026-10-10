# UK trains and transport

Ask about trains and transport as you would ask a person: "next train from St Albans to St
Pancras", "is the 17:40 to Sevenoaks running late?", "how do I get from here to the
airport?".

## Live train times with Realtime Trains

With your own [Realtime Trains](https://www.realtimetrains.co.uk) token, Grok reads live
departures, delays, cancellations, platforms and arrival times from Network Rail's running
data, rather than searching the web for them.

### Set it up

1. Create a token at [api-portal.rtt.io](https://api-portal.rtt.io). Either kind works: a
   long-life access token is used as it is, and a refresh token is exchanged for access
   tokens automatically.
2. In the app, open **Settings → UK train times**, paste the token and tap **Save**. Saving
   checks the token with Realtime Trains and tells you whether it was accepted. **Check**
   tests it again later; **Clear** removes it.

Without a token, train questions are answered from web search (below).

### How it works

The app gives Grok two tools, offered only when a token is saved:

| Tool | Realtime Trains endpoint | What Grok gets |
| --- | --- | --- |
| `uk_train_departures` | `GET https://data.rtt.io/gb-nr/location` | Departures from a station (e.g. `SAC`), optionally only trains calling at or coming from another, for up to 12 hours: scheduled and expected times, platform, status, destination, operator, delay reasons |
| `uk_train_service` | `GET https://data.rtt.io/gb-nr/service` | Every stop of one train, with scheduled and expected times and platforms: how arrival times are answered |

While Grok uses them, the chat shows **Checking live train times…**. Grok answers from
the results and names Realtime Trains as the source.

The API allows 30 requests a minute, 750 an hour, 9,000 a day and 30,000 a week; a
train question typically uses one to three.

### Your token stays yours

- **You enter your own token.** The app contains none, and none is in this repository or any
  published APK.
- **It's stored only on your phone,** in the app's private storage, excluded from cloud
  backup and device transfer.
- **It's sent only to `https://data.rtt.io`,** over HTTPS. It's never sent to xAI or included
  in anything you share.

Realtime Trains' terms ask that no token is placed in a distributed app. This app
distributes none: each person supplies their own, for their own use on their own phone.

## Without a token: web search

For rail and transport questions, Grok searches the web, starting with National Rail
(`nationalrail.co.uk`, `realtime.nationalrail.co.uk`). It then turns to the operators'
own sites:
- Thameslink, London Northwestern, Great Northern, East Midlands Railway and Intalink
  around St Albans;
- TfL and Citymapper in London;
- the other UK train operators elsewhere.

It is asked to search before giving any times, and to link its sources. Live departure
boards are built by script and often don't appear in search results, so a Realtime Trains
token gives better answers.

## Where you are

With location on, the app adds your approximate position to the prompt, so "from here"
works without naming a station.
- **Satellite position:** it uses the phone's own GPS, not Google Play services.
- **Reused fix:** a fix is reused for 10 minutes (**Settings → Location**), and only
  questions that need your position wait for a fresh one.
- **Place names:** a town or postcode is looked up only from a fix accurate to 50 m.

Turn location off any time; you can name where you are instead.
