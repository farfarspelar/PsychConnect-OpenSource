# PsychConnect

**Two Minds. One Connection.**

PsychConnect is a free, haptic-first Android game about finding connection through touch, movement, and timing. It is built with Kotlin and the Android SDK, with a small Node.js WebSocket server for online play and optional PostgreSQL-backed profiles and records.

**Free to play. Open to explore. Built for connection.** Official PsychConnect releases have no ads, tracking, microtransactions, paid features, subscriptions, or mandatory donations. Optional donations do not grant gameplay advantages. These are the maintainer's commitments for official releases; the MIT license does not impose them on independent forks.

## Project status

- Android app: **0.6.7** (`versionCode` 34)
- Server package: **0.2.10**
- Minimum Android version: **Android 8.0 / API 26**
- Status: active prototype; see [known limitations](#known-limitations)

## Game modes

- **Versus / Deathmatch:** one player leads and earns points by keeping distance; the other follows and earns points by closing the gap. Heartbeat haptics, Sonar, hidden-until-revealed Decoys, alternating roles, match results, and online Versus records are implemented.
- **Together:** cooperative challenges include **2 Player Survival**, where either player's movement advances shared hazards, and **Puzzle**, a shared checkpoint maze with escalating levels and a cumulative score.
- **Solo Survival:** dodge increasingly frequent and faster hazards. Survival time is the score. Local top-five records are stored on-device; authenticated players can sync runs to the online Solo Survival top five.
- **Harmony:** experimental, untimed haptic sandbox with multitouch, movement and rotation responses, optional shared haptics, optional shared touch trails, and experimental device-dependent palm recognition. It has no score or competitive leaderboard.

Harmony palm recognition and several haptic mappings are experimental. Android touch-size and pressure readings differ by device, and palm detection is not guaranteed. Devices without useful measurements continue to use ordinary multitouch.

## Main features

- Haptic heartbeat and adjustable vibration settings.
- Normalized touch input, responsive trails, and discreet visual feedback.
- Short room codes and two-player WebSocket sessions.
- Optional pseudonymous profiles, recovery codes, match history, rankings, rivals, achievements, and presence. These online profile features require a configured PostgreSQL database.
- Offline haptic simulation and developer diagnostics.
- Static, responsive menu artwork with optional low-motion decorative effects.

## Manual device testing

The maintainer has reported functional playtests on a Samsung phone and tablet, including connecting two devices with a room code and using Versus, touch trails, and Harmony. This is hands-on prototype testing, not formal device certification or a complete haptic usability study.

## Requirements

### Android application

- JDK 17 or newer; this project has been built with JDK 21.
- Android SDK Platform 36 and Android Build Tools 35.x.
- Gradle wrapper 8.13, Android Gradle Plugin 8.13.2, Kotlin Gradle Plugin 2.2.20.
- Android Studio is optional; the Gradle wrapper can build from Linux.

The Android Gradle Plugin 8.13 requires JDK 17 and uses Gradle 8.13. See the [Android Gradle Plugin release notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes) and [JDK guidance](https://developer.android.com/build/jdks).

### Server

- Node.js **24.21.0** (pinned for Render and recorded in `server/.node-version`).
- npm with support for lockfile version 3.
- PostgreSQL 14 or newer only for persistent profiles, rankings, and related online records. Ordinary room-code multiplayer can run without it.

## Build and run tests

From the repository root:

```sh
./gradlew testDebugUnitTest assembleDebug
npm --prefix server ci
npm --prefix server test
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it on a connected Android device with:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Android app requests only Internet access and vibration. Cleartext networking is allowed in **debug builds only** for local LAN testing. Release builds use Android's secure default and should connect to internet servers with `wss://`/HTTPS.

To override the optional Buy Me a Coffee target when building, use a real HTTP(S) URL:

```sh
./gradlew assembleDebug -PpsychconnectSupportUrl=https://example.org/support
```

The official app has its configured voluntary-support link. No payment is required to use any feature.

## Run the server locally

```sh
cd server
npm ci
npm test
npm start
```

The server listens on port 8787 by default and serves the WebSocket endpoint at `/ws` plus the HTTP API on the same port. Override the port with `PORT`. The in-memory room/session server works without PostgreSQL; profiles and persistent rankings return a clear unavailable response until the database is ready.

### Optional local PostgreSQL

Create a disposable PostgreSQL database. Copy `server/.env.example` to `server/.env`, set `DATABASE_URL` to your local connection string, then load it before starting the server:

```sh
cp server/.env.example server/.env
# Edit server/.env for your local database, then:
set -a
. server/.env
set +a
npm --prefix server start
```

`DATABASE_SSL=disable` is only for a trusted local database. TLS certificate verification is enabled by default everywhere else. `DATABASE_SSL=insecure` disables certificate verification and should only be used deliberately for a trusted self-signed database. Automated tests use an in-memory PostgreSQL-compatible test database and do not read `DATABASE_URL`.

Database migrations run automatically at server startup. Never commit a real `.env`, connection string, password, or credential.

### Test multiplayer on a local network

1. Connect both Android devices and the computer running the server to the same Wi-Fi network.
2. Run `npm --prefix server start`; allow inbound TCP port 8787 on the trusted local network.
3. In the Android app, select a mode and choose Host Game or Join Game inside that mode. Set the server address to `ws://<computer-LAN-IP>:8787` for a debug build. The Android emulator can reach the host computer at `ws://10.0.2.2:8787`.
4. Share the displayed room code, have both players join and mark Ready, then play.

`ws://` is for local development only. Internet deployments should use TLS (`wss://`). Active rooms, presence, and reconnect state are held in memory and end when the server restarts.

## Profiles, online rankings, and deployment

The Android app uses the configured game server. The Render blueprint is in [`render.yaml`](render.yaml); it creates the Node web service but does **not** provision a PostgreSQL database. Set `DATABASE_URL` as a private Render environment variable to a PostgreSQL 14+ database, then confirm `/health` reports `profilesEnabled: true`. Do not put production secrets in `render.yaml` or source control. Applying this repository's Render settings or deploying changes is a separate operation; this project preparation does not deploy or modify the live service.

Profile credentials are random and stored on Android encrypted with an Android Keystore key. The server stores scrypt hashes. Recovery codes are shown once and also stored temporarily encrypted on the device until the player confirms they have saved the code. Public profiles are pseudonymous; no email, phone number, location, or contacts are requested. Players can change privacy settings or delete a profile.

Versus uses Elo (initial rating 1000, K=32); weekly rankings count rating changes since Monday 00:00 UTC. Together, 2 Player Survival, and Puzzle use pair-based cooperative records. Solo Survival uses an online top-five board when a player has a profile. **Solo Survival runs are self-reported:** the server checks authentication, run ID uniqueness, and score bounds, but does not replay or independently validate the run. Harmony has no public competitive leaderboard.

The HTTP/API endpoints require bearer authentication for profile-specific actions; public profile lookup exposes only the public profile fields. Profile creation, authentication, and general HTTP and WebSocket auth attempts are rate-limited in memory. WebSocket gameplay inputs are size-limited, validated, sequence-checked, and server-scored for supported competitive and pair modes. Rate-limit buckets and live sessions are not persistent or shared across multiple server processes. Do not treat these prototype safeguards as an anti-cheat guarantee.

## Project layout

```text
app/                  Kotlin Android app, UI, resources, and unit tests
server/               Node.js WebSocket/HTTP server and server tests
server/db/migrations/ Versioned PostgreSQL schema migrations
assets/               Original UI source artwork and animation references
render.yaml           Render Web Service blueprint; no database secret
```

Gameplay calculations are separated into `app/src/main/java/com/psychconnect/core/` and `server/` domain modules. See the source and README files alongside assets for details.

## Known limitations

- Network latency varies. The server uses a short fixed receipt-time interpolation buffer; it cannot eliminate large or unequal latency.
- Active rooms and presence are in memory. Persistent profile and leaderboard data require a working external PostgreSQL database.
- Public scores and rival statistics have different validation guarantees. In particular, Solo Survival accepts bounded, authenticated client-reported times and can be abused.
- Palm sensing is experimental and not verified across Android hardware. Multitouch capacity varies by device.
- Haptic strength and perceived rhythm vary across vibration motors; the reported Samsung tests do not establish broad accessibility or comfort.
- Optional menu/background music files are not included. The application remains functional without them. The bundled button-click WAV is procedurally generated for PsychConnect and offered under CC0 1.0; see [AUDIO_LICENSE.md](AUDIO_LICENSE.md).
- No signed release APK, signing key, or Play Store package is included.

## Contributing

Bug reports, feature ideas, translations, Android-device testing, and code contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md) first. For security issues, follow [SECURITY.md](SECURITY.md) and avoid posting exploit details publicly.

## License and artwork

Original project source code is licensed under the MIT License; see [`LICENSE`](LICENSE). Original PsychConnect artwork is offered separately under CC BY 4.0, with the rights and non-exclusivity limits described in [`ARTWORK_LICENSE.md`](ARTWORK_LICENSE.md). The procedural button sound is offered separately under CC0 1.0; see [`AUDIO_LICENSE.md`](AUDIO_LICENSE.md). Third-party software remains under its own terms; see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Voluntary support

PsychConnect's official release remains free, with no ads, subscriptions, microtransactions, or paid gameplay. Donations through the configured Buy Me a Coffee link are entirely optional and provide no gameplay advantage or extra rights.

## Credits

- Project concept and direction: **Kristian Simontaival**.
- Development: **Kristian Simontaival**, assisted by **OpenAI Codex**.
- Original generated visual artwork: **Kristian Simontaival**, using ChatGPT image-generation tools.

OpenAI does not sponsor, endorse, or maintain PsychConnect.
