# Pocket Martian

An unofficial Android app for chatting with xAI's Grok, using your own API key.

*Grok* is a Martian word, from Robert A. Heinlein's *Stranger in a Strange Land*, for
understanding something completely. **Pocket Martian** nods to that origin without
borrowing xAI's mark.

<p align="center">
  <img src="docs/screenshot.png" alt="Pocket Martian chat" width="280">
</p>

## What it does

- **Chat with Grok 4.7 or 4.6**, with answers streaming in as they're written.
- **Live web search** when a question needs current facts, with the sources linked.
- **Pictures with Grok Imagine:** ask for a picture, or attach a photo and ask for a change.
- **Document scanner without Google Play services:** photograph a page, get a straight,
  clean scan.
- **Speak your prompt,** and a real-time **voice translator**.
- **Live UK train times** with your own Realtime Trains token.
- **Share in and out:** send text, links and pictures to the app from any other app, and
  share answers back out.
- **Your data stays on your phone:** your key and conversations are kept in the app's
  private storage. What you ask goes to xAI to be answered; the developer receives
  nothing. See [Privacy](docs/user-guide.md#privacy).

## Get started

1. Install the latest APK from [Releases](https://github.com/gprot42/android-pocket-martian/releases).
   The app checks there for updates once a day.
2. Create an API key at [console.x.ai](https://console.x.ai) and add **API credits**.
   A SuperGrok subscription doesn't cover this app.
3. In the app, open **Settings → Account** and paste the key.

Requires Android 7.0 or later.

## Docs

- [User guide](docs/user-guide.md): chatting, voice, pictures, settings, costs and
  troubleshooting
- [Document scanner](docs/scanner.md)
- [UK trains and transport](docs/uk-trains.md)

## Building

```bash
./build.sh            # release APK, in private-not-in-git/builds/
./build.sh --emulate  # debug build, installed and launched on an emulator
```

There are two versions of the app:
- **`github`** is what `build.sh` makes. It includes its own update check.
- **`play`** has no update check and no permission to install apps, because Google Play
  forbids apps that update themselves.

Both are `com.pocketmartian.app`, signed with the same key. The release key is read from
`private-not-in-git/secrets/release-key.properties`, which git ignores. Without it,
release builds are signed with the debug key.

Built with Kotlin, Jetpack Compose, Material 3, Hilt, Retrofit/OkHttp and DataStore,
targeting Android 16 (API 36), minimum Android 7.0 (API 24). It talks to xAI's Responses
API (`https://api.x.ai/v1/responses`).

## License

[MIT](LICENSE).
