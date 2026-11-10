# AGENTS.md — Podium

> **Looking for an exact screen, button, colour or animation? Read [`AI_GUIDE.md`](AI_GUIDE.md)
> first.** It is the element-level map (file + composable/symbol for every screen, row, tray
> action, colour token and motion token). This file is the architecture index; `docs/` is the
> long-form source of truth.

> Read this file before changing anything. It is the fast onboarding map for an AI agent that has
> never seen this repository. Deeper material lives in `docs/` (see §15); `docs/` is the source of
> truth for behaviour, this file is the index to it.

---

## 1. What this is

**Podium** is a sideloaded Android music player built to feel like a physical music device: the
entire UI lives on a virtual screen above an iPod-style **click wheel**, inside a body whose finish,
fonts, background, glitter and stickers the listener can change. It plays the listener's own local
library **and** an online YouTube Music catalogue, with no ads, no analytics and no backend.

- Package: `app.podium` (debug build: `app.podium.debug`)
- Version: **0.1.5.1** (`versionCode` 7) — see `app/build.gradle.kts`
- License: **GPL-3.0** (`LICENSE`); third-party notices in `third_party/`
- Language in code/comments/docs: English. Commit messages: Conventional Commits.
- Min Android 10 (API 29), target 36, portrait-only.

---

## 2. Non-negotiables (condensed from `CLAUDE.md`)

1. **Local/offline playback must not break.** MediaStore + Room + Media3 `PlaybackService` +
   `QueueManager` is the stable core. Online work must not rewrite it.
2. **YouTube Music is the only online catalogue.** It is also the default stream and the fallback
   for every online song.
3. **Custom sources are stream sources only** (BitChord addon protocol). They are tried first for an
   EXACT copy, in the listener's order, and never enter `SourceRegistry`, are never browsed or
   searched, and never drive recommendations/history.
4. **Identity is source-qualified.** `TrackId = "<sourceId>|<providerKey>"`. Cross-source fallback
   only at matcher tier **EXACT**, never mid-track (except a custom source's EXACT copy taking over),
   never across `LOCAL` ↔ `ONLINE` environments.
5. **UI never touches ExoPlayer.** Everything goes through `PlaybackController`. `QueueManager` is
   the only writer of the queue.
6. **No Material3.** Compose foundation/ui + Podium design tokens only.
7. **Glass only through `:core:designsystem`** (`Modifier.glass`, `GlassSurface`). No direct
   `Modifier.blur`, `RenderEffect`, `RuntimeShader` or Backdrop imports in feature modules. Glass is
   for functional elements (wheel, power button, mini player, title-bar controls, menus, sheets,
   HUDs) — never rows, cards, artwork, the virtual screen or text containers.
8. **Providers never leak into generic UI.** Screens branch on capabilities, availability, route and
   owner — never on a provider name.
9. **No secrets in git; no analytics or crash SDKs.** `allowBackup=false`,
   `usesCleartextTraffic=false`.
10. **The virtual screen is sacred (D-26):** finishes change the body and wheel, never the screen's
    colours. Rounded rectangles outside the instrument use `ContinuousShape` / `continuousRect`
    (D-103), not `RoundedCornerShape`.

If a change conflicts with these, stop and re-read `docs/decision-log.md`.

---

## 3. Stack (exact, from `gradle/libs.versions.toml`)

| Area | Choice |
|---|---|
| Language | Kotlin **2.4.20**, JVM toolchain **21** (bytecode 17) |
| Build | AGP **9.4.1**, Gradle wrapper **9.8.0**, KSP **2.3.12**, convention plugins in `build-logic/` |
| SDK | compileSdk **37**, minSdk **29**, targetSdk **36** |
| UI | Jetpack Compose BOM **2026.09.00**, Navigation3 **1.2.0** |
| Media | Media3 **1.11.1** (`exoplayer`, `exoplayer-hls`, `session`, `ui`, ...) |
| Storage | Room **3.0.3** with bundled SQLite **2.7.1** (WAL, FTS5) |
| Images | Coil **3.6.3** |
| Glass | Kyant Backdrop **2.0.1** + Kyant Shapes **1.2.1** (confined to `core:designsystem`) |
| ML | LiteRT **2.3.0** (on-device sticker cutout) + bundled `.tflite` (no compression) |
| JS | AndroidX JavaScriptEngine **1.1.1** (signature deciphering) |
| Tests | JUnit 4, Robolectric **4.17**, Turbine, Compose UI test, AndroidX Test |
| Net | OkHttp (via source modules), kotlinx.serialization **1.11.0** |

No Hilt/Koin/Dagger (manual DI), no Retrofit, no Firebase.

---

## 4. Repository map

```
app/                        Android application: composition root, navigation shell, app-level
                            repositories, settings persistence, guide, stickers, Podium Space, updater
core/
  model/                    Pure Kotlin: Track/Album/Artist/Playlist ids, capabilities, queue models
  common/                   Pure Kotlin utilities (Clock, DiskActivity, ...)
  lyrics/                   Pure Kotlin LRC parsing/matching
  database/                 Room database, entities, DAOs, FTS5 search, migrations, backups
  designsystem/             Theme tokens, VirtualScreen, PodWheel, glass, typography, components
  interaction/              WheelGestureDetector, InputRouter, FocusList semantics, haptics
sources/
  api/                      Pure Kotlin source contract: MusicSource, SourceRegistry, descriptors,
                            capabilities, matcher, StreamResolver, health
  local/                    MediaStore-backed local library source ("On this device")
  youtubemusic/             YouTube Music source: auth, browse/search, stream walker, PoTokens
  addon/                    User-added BitChord-protocol stream sources (not in the registry)
  test/                     Debug-only fixture source (test tracks/tones)
player/
  api/                      Pure Kotlin playback contracts: PlaybackController, PlaybackState,
                            Queue, OwnerAwarePlaybackController, autoplay, sleep timer
  service/                  Media3 MediaLibraryService + ExoPlayer engine + QueueManager + resolver
  remote/                   MediaSession-based remote playback (for REMOTE-route sources)
feature/
  library/                  Home, Music, Cover Flow, Albums, Artists, Songs, Favorites, Playlists
  online/                   ONLINE section: home, explore, search, artist, album, playlist, radio, ...
  nowplaying/               Now Playing, Up Next, Lyrics, orb, vinyl stage, tray
  settings/                 Settings tree, appearance editors, equaliser, sources, about
tools/newpipe-poc/          Isolated research module (not shipped)
build-logic/                Gradle convention plugins
docs/                       Authoritative documentation (start at docs/README.md)
artifacts/                  Build output location (`artifacts/apk`, APKs ignored by git)
site/                       Vercel marketing site
third_party/                License texts and notices
```

Dependency direction: `feature/*` → `core:designsystem` / `core:interaction` / `player:api` /
repository interfaces. Domain modules (`core:model`, `core:common`, `core:lyrics`, `sources:api`,
`player:api`) contain **no Android imports**. Only `app` knows the concrete sources.

---

## 5. Boot and object graph

```
PodiumApplication
  └─ AppGraph(context)              // composition root (ADR-009), built once
       ├─ SourceRegistry + SourceHealthMonitor + TrackMatcher
       ├─ PodiumDatabase (podium.db)
       ├─ LocalMusicSource, YouTubeMusicSource, addon sources
       ├─ PlaybackService dependencies, StreamResolver, artwork loader
       ├─ playbackController = OwnerAwarePlaybackController(local, remote, ...)
       └─ repositories (library, online, playlists, settings)
MainActivity (portrait, edge-to-edge)
  └─ PodiumApp(graph)
       ├─ Power.BOOTING → BootScreen (self-test, startup chord) → Power.ON
       ├─ Navigator + back stack + Navigation3 NavDisplay
       ├─ VirtualScreen { PodWheel + KeyboardHost + content }
       └─ global input (MENU, transport, hold-power = off, double-⏯ = Now Playing)
```

- `AppGraph` is the only place concrete sources are named. Adding a source means editing it.
- `PodiumApp.kt` owns the `Dest` hierarchy, the `Navigator`, back-stack persistence
  (`BackStackSaver`), global key handling and the screen switch.
- `Farewell.kt` handles the power-off animation.

---

## 6. UI shell, navigation and input

**Shell.** `core:designsystem/shell/VirtualScreen.kt` draws the screen, status bar, title bar,
mini player and the wheel. `DeviceAppearance` supplies the hardware palette. The display occupies
0.60–0.82 width:height; the wheel sits below it. Everything is inside `PodiumTheme`.

**Navigation.** `Dest` (sealed interface in `PodiumApp.kt`) enumerates every place: fixed roots
(`Home`, `Music`, `CoverFlow`, `Albums`, `Artists`, `Songs`, `Favorites`, `Playlists`,
`NowPlaying`, `UpNext`, `Lyrics`, `Settings`, ...) plus data-carrying destinations (`Album(id)`,
`Artist(id)`, `Playlist(id,name)`, `Online(place)`, `MusicFolders(path)`, `Level(level)`,
`CustomSource(id)`, `EqualizerBand(band)`, ...). Destinations are serialised to strings so the stack
survives process death. Sheets, menus, HUDs and overlays are **not** destinations.

**Wheel semantics** (`docs/interaction-model.md`):

| Input | Meaning |
|---|---|
| Rotate ring | Move focus one detent (accelerated in long lists); in Now Playing: volume |
| Center press | Activate focused element |
| Menu | Back |
| ⏮ ⏯ ⏭ | Transport, always |
| Long-press | Context menu ("More") |
| Hold ⏯ | Power off; any press wakes |
| Double ⏯ (300 ms) | Open Now Playing from anywhere |

Now Playing has its own `WheelContext`s: `Volume` → (center) `Scrub` → (center) `Actions`, each
auto-returning on idle. `InputRouter` translates touch, keyboard, D-pad, rotary encoders, mouse wheel
and TalkBack into the same semantic events. `WheelGestureDetector` uses 18°/detent, 20 detents per
revolution, ×1/×2/×4 acceleration, and emits `Rotate`/`Press`/`LongPress`.

**Keyboard.** `PodiumTextField` is the only text field. The Podium keyboard morphs out of the wheel
(`WheelKeyboard`, `KeyboardSession`, `KeyboardHost`); Settings ▸ Keyboard can restore the phone IME.

---

## 7. Playback

```
UI ── PlaybackController (player:api)
        ├─ OwnerAwarePlaybackController
        │     ├─ LOCAL  → MediaController → PlaybackService : MediaLibraryService
        │     └─ REMOTE → player:remote MediaSessionRemotePlayback
        └─ PlaybackState (StateFlow)  ← single source of truth for UI
PlaybackService
  ├─ PodiumPlaybackEngine (ExoPlayer, main looper)
  ├─ QueueManager (only queue writer) → QueuePersister → Room
  ├─ ResolvingDataSource → StreamResolver → SourceRegistry / addon sources
  ├─ QualityInspector, AutoplayEngine, SleepTimer, Crossfade
  └─ SessionPolicy (SystemUI, Bluetooth, Android Auto, Wear)
```

Key contracts (read before touching playback):

- `player/api/.../Playback.kt`, `PlaybackController.kt`, `PlaybackState.kt`, `Queue.kt`
- `player/api/.../OwnerAwarePlaybackController.kt` — chooses LOCAL vs REMOTE per route; the current
  owner is part of the state.
- `player/service/.../PodiumPlaybackEngine.kt` — ExoPlayer wiring, effects, gapless/crossfade.
- `player/service/.../QueueManager.kt` — queue, shuffle, repeat, reorder, persistence.
- `player/service/.../StreamResolver` — resolves a source-qualified track to a playable URI.

Rules: UI never imports Media3; background playback is a `MediaLibraryService`; sleep timer, EQ,
sound effects, crossfade live in the engine, not in screens; "Lossless/Hi-Res" claims only per
`docs/audio-architecture.md` §5.

---

## 8. Music sources

| Source id | Module | Environment | Notes |
|---|---|---|---|
| `local` | `sources:local` | LOCAL | MediaStore, incremental sync; albums/artists/songs/search; local playlists and favorites |
| `ytmusic` | `sources:youtubemusic` | ONLINE | YouTube Music browse/search/artist/album/playlist/radio/history/likes; optional Google sign-in via WebView; **DIRECT** streaming |
| `test`, `test-mirror` | `sources:test` | debug only | Deterministic test tones + mirror (fixtures) |
| addons | `sources:addon` | stream-only | BitChord protocol, added in Settings ▸ Custom sources |

**YouTube Music streaming** (`YouTubeStreamWalker`): rotates YouTube client identities, obtains a
guest visitor ID, proves a stream with `bytes=0-` + last-2-bytes requests, decrypts ciphered
signatures using bundled `yt-dlp-ejs` via JavaScriptEngine, and optionally mints BotGuard PoTokens
using the listener's session. Downloads from YouTube Music are explicitly **not** permitted.
`tools:newpipe-poc` is a research fallback and is not shipped.

**Addons** are never in `SourceRegistry` and never browsed. They are asked first, in order, for an
EXACT copy of an online song with a 5 s budget; YouTube Music plays otherwise or until the copy
loads. A resting addon is retried in the background (D-96).

**Capabilities, not names.** Always branch on `SourceDescriptor` + `Capabilities` + `environment` +
`route` + `owner`. Provider-specific behaviour stays inside its source module.

---

## 9. Persistence

Room database `podium.db` (WAL), **schema v6** (`PodiumDatabase.kt`). Tables:

`source_account`, `track`, `album`, `artist`, `liked_track`, `queue_state`, `queue_item`,
`online_liked_track`, `online_playlist`, `online_playlist_track`, `online_history`,
`track_equivalence`, `local_playlist`, `local_playlist_track`, and the FTS5 virtual table `track_fts`
(with triggers for search).

- Local library, local favorites and local playlists are **separate** from online likes, playlists
  and history. Never mirror one into the other.
- Only EXACT equivalences are persisted; a user's "not the same song" is final.
- Before a destructive migration the database is copied to `podium.db.bak-v<old>`.
- Settings are **not** in Room: `SharedPrefsDeviceSettings` uses the `device` SharedPreferences file
  (`KEY_*` constants at the bottom of that file). Source credentials/preferences live in
  `SourceCredentials.kt`, `SourcePreferencesFile.kt`, `AppOnlineServiceSettings.kt`.
- `PrefsStreamWalkMemory.kt` caches the stream-walk results.

Read `docs/data-model.md` before changing entities or DAOs.

---

## 10. Design system and customization

`core:designsystem` is the only place that knows how Podium looks.

- **Theme:** `DisplayTheme` = `GLASS`, `CARBON`, `CUSTOM`. (Bone was withdrawn in D-108; its palette
  survives as `PaperColors` for Custom and Classic lyrics.) Branch on `colors.style` /
  `colors.isTonal` / `colors.isIndustrial` — never on the theme's name.
- **Finishes:** Glass, Steel gray, Burgundy, Glacier blue, Silver, Custom colour; optional grain and
  glitter. Finish changes the body and wheel only.
- **Type:** 17 display fonts (`DisplayFont`) + 7 lyrics-only faces (`LyricsFont`), sizes normalised
  so row rhythm stays constant.
- **Display:** background `NONE` / `SOLID` / `IMAGE` (photo picker), contrast, lyrics theme
  `CLASSIC` / `CONTINUOUS_LINE`.
- **Components:** `VirtualScreen`, `PodWheel`, `StatusPills`, `FocusList` (the paper list — use it,
  don't hand-roll vertical lists), `GlassSurface`, `LiveOrb`, `ContinuousShape`.
- **Motion:** `docs/animation-system.md` (reduced-motion aware). No ad-hoc durations; use tokens.
- **Copy:** sentence case; all-caps only for wheel `MENU` and the boot wordmark; errors explain and
  suggest a next step; no middle-dot meta strings.

**Customization extras (app module):** `stickers/` cuts subjects out on-device with LiteRT and lets
them be stuck anywhere (even over the screen); `space/` implements "Podium Space" (pinch out to lift
the device into 3D); `guide/` is the first-run tour; `DisplayImages.kt` decodes custom backgrounds.

---

## 11. Lyrics

`core:lyrics` parses LRC (plain and word-synchronised). `OnlineMusicRepository`/`SongPages` fetch
lyrics from LRCLIB only after consent (`KEY_ONLINE_LYRICS`), cache them, and match by source-qualified
identity. Now Playing ▸ Lyrics is a full-screen destination with no header. Word-level reveal is
driven by playback position, never by a separate clock. See `docs/architecture/LYRICS_ARCHITECTURE.md`.

---

## 12. Updates

`app/update/` checks GitHub releases, downloads the APK and hands it to the system installer
(`REQUEST_INSTALL_PACKAGES`). Releases use a stable signing key so updates install over the old app
and keep data. APKs are never committed; see `artifacts/apk/README.md` and `docs/deployment.md`.

---

## 13. Build, run, test

```bash
./gradlew :app:assembleDebug        # app.podium.debug (not debuggable by default, R8 off)
./gradlew :app:assembleRelease      # app.podium, minified + resource-shrunk
./gradlew :app:installDebug
./gradlew test                      # JVM unit tests + Robolectric
./gradlew :feature:library:testDebugUnitTest   # one module
./gradlew lint
```

Useful flags:

- `-Ppodium.debuggable=true` — make the debug build attachable (slower first frame).
- `-Ppodium.minifyDebug=true` — run R8 on debug to reproduce release-only bugs.
- `-Ppodium.applicationIdSuffix=.walktest` — install beside an existing build without wiping data.
- `-Ppodium.versionCode=8 -Ppodium.versionName=0.1.5.2` — fake a newer build to test the updater.

Release signing reads `keystore.properties` (ignored) or `PODIUM_KEYSTORE_FILE`,
`PODIUM_KEYSTORE_PASSWORD`, `PODIUM_KEY_ALIAS`, `PODIUM_KEY_PASSWORD`. Without them the release APK
is unsigned.

Tests: ~120 files under `*/src/test` (mostly Robolectric for UI/shell/design-system behaviour), plus
`app/src/androidTest/.../MotionProbe.kt` for on-device motion measurement. `:player:service` has
timing-sensitive crossfade/pipeline tests that can be flaky. `docs/testing-strategy.md` defines what
must be covered.

---

## 14. Conventions and traps

- **Comments explain *why*.** Existing code uses decision ids (`D-xx`, `ADR-xxx`) in KDoc; keep that
  style and cite the decision when behaviour is non-obvious.
- **No new dependencies without checking `libs.versions.toml`** and the layering rules.
- **Don't add a state holder in a screen** if it belongs in `AppGraph` or an app-level repository.
- **Don't branch on provider ids in feature modules.** Add a capability instead.
- **Don't write the queue outside `QueueManager`.**
- **Copy lives in Kotlin**: there is no `res/values/strings.xml`; user-facing text is written inline
  in the composables (follow the existing tone and sentence case).
- **Formatting**: `.editorconfig` (4 spaces, LF). Keep imports ordered; no wildcard imports.
- **Migrations**: bump `PodiumDatabase.VERSION`, add a `Migration`, add a test, and update
  `docs/data-model.md` in the same commit.
- **Docs travel with code.** Behaviour changes require a docs update in the same commit; significant
  decisions go to `docs/decision-log.md` (ADR in `docs/adr/` if major).
- **Windows-authored repo**: paths in docs mention `D:\...`; ignore those, they are the author's
  machine.

---

## 15. Where to read next

Start at `docs/README.md`. The highest-value files:

| If you are changing... | Read |
|---|---|
| Anything architectural | `docs/architecture.md`, `docs/decision-log.md` |
| Screens, layout, glass, type | `docs/design-system.md`, `docs/screen-inventory.md` |
| Wheel, focus, keyboard, haptics | `docs/interaction-model.md`, `docs/accessibility.md` |
| Navigation, `Dest`, back stack | `docs/navigation-map.md` |
| Playback, effects, quality, radio, sleep | `docs/audio-architecture.md`, `docs/playback-state-machine.md`, `docs/queue-and-autoplay.md` |
| Database, entities, search | `docs/data-model.md` |
| Sources / YouTube Music / addons | `docs/architecture/MUSIC_SOURCE_ARCHITECTURE.md`, `.../YOUTUBE_MUSIC_ARCHITECTURE.md`, `.../CUSTOM_SOURCES.md`, `.../SOURCE_CAPABILITY_MATRIX.md` |
| Lyrics | `docs/architecture/LYRICS_ARCHITECTURE.md` |
| Themes, fonts, backgrounds, stickers, Podium Space | `docs/architecture/PODIUM_CUSTOMIZATION.md`, `.../PHYSICAL_SPACE.md` |
| Releases, signing, CI | `docs/deployment.md`, `docs/releases/` |
| Privacy and threat model | `docs/security.md` |
| Performance | `docs/performance.md` |
| Tests | `docs/testing-strategy.md` |
| Product intent / scope | `docs/product-spec.md`, `docs/vertical-slice-plan.md` |

Current release notes: `docs/releases/v0.1.5.1.md`; full history: `CHANGELOG.md`.

---

## 16. Glossary

- **Podium / device** — the whole app framed as one physical object.
- **Virtual screen / display** — the rectangular screen area above the wheel.
- **Wheel** — the circular input at the bottom; rotate, center, Menu, transport zones.
- **Finish** — the body/wheel material (Glass, Steel gray, Burgundy, Glacier blue, Silver, Custom).
- **Display theme** — Glass, Carbon, Custom (visual system of the screen).
- **Source** — a provider of tracks; identified by `SourceId` and described by `SourceDescriptor`.
- **Environment** — `LOCAL` (device library) or `ONLINE` (network catalogue).
- **Route** — how a source delivers audio: `DIRECT`, `REMOTE`, `EMBEDDED`.
- **Owner** — which playback implementation currently holds the session (`LOCAL` or `REMOTE`).
- **EXACT** — matcher tier meaning the same recording; the only tier allowed to fall back or persist.
- **Addon / custom source** — user-added BitChord-protocol stream source, asked first for an EXACT
  copy.
- **Podium Space** — the pinch-out 3D presentation of the device.
- **Tray** — Now Playing's transport strip, optionally hidden when idle (D-108/D-109).
