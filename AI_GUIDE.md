# AI_GUIDE.md — Podium, for an AI that just landed here

> **Read this first.** This is the practical, element-level map of the app. `AGENTS.md` is the
> short architecture index; `CLAUDE.md` is the rulebook; `docs/` is the long-form source of truth.
> This file exists so you can find *the exact composable, token or file* that draws a given button,
> tab, colour or animation without reading the whole repository.
>
> Repository root: this folder. Package: `app.podium`. UI language: English, sentence case.

---

## 0. How to use this guide

- Looking for a **screen**? §6.
- Looking for a **button / row / icon / label**? §6 and §7.
- Looking for a **colour, font or spacing value**? §8.
- Looking for an **animation**? §9.
- Looking for **where a feature lives**? §10.
- Looking for **what not to break**? §11.
- Want a **step-by-step “move this button” recipe**? §12.
- Need a **symbol search command**? §13.

Line numbers move. Prefer the **file + composable/symbol name** in the tables; use
`grep -n "fun Name" path` to get the current line.

---

## 1. What Podium is

Podium is a sideloaded Android music player designed as a **physical music device**: a virtual
screen above an iPod-style click wheel, inside a body whose finish, grain, glitter, fonts and
background the listener can change. It plays the device's local library, an online YouTube Music
catalogue, and online radio; it can download music for offline use and sync a listening session
with nearby Podiums (“Listen together”). No ads, no analytics, no backend.

| Item | Value |
|---|---|
| App name | Podium |
| Version | **1.1** (`versionCode` 11) — `app/build.gradle.kts` |
| Package | `app.podium` (debug: `app.podium.debug`) |
| License | GPL-3.0 (`LICENSE`), notices in `third_party/` |
| Language | Kotlin **2.4.20**, JVM toolchain **21**, bytecode 17 |
| UI | Jetpack Compose (BOM **2026.09.00**), Navigation3 **1.2.0**; **no Material3** |
| Playback | Media3 **1.11.1**, ExoPlayer behind `PlaybackController` |
| Storage | Room **3.0.3**, bundled SQLite, WAL + FTS5 |
| Images | Coil 3.6.3 |
| Glass | Kyant Backdrop 2.0.1 + Kyant Shapes, confined to `core:designsystem` |
| SDK | compileSdk 37, minSdk 29, targetSdk 36, portrait only |

**Key consequence:** Podium does **not** use tabs, a bottom bar, a drawer or Material navigation.
There is one virtual screen; menus are columns on a “paper”; destinations form a hierarchy.

---

## 2. Build, run, test

```bash
./gradlew :app:assembleDebug          # app.podium.debug, test tones + debug commands, R8 off
./gradlew :app:assembleRelease        # app.podium, minified + resource-shrunk
./gradlew :app:installDebug           # install debug build on a device
./gradlew test                        # JVM + Robolectric tests
./gradlew :feature:library:testDebugUnitTest   # one module
./gradlew lint
```

Useful flags:

| Flag | Effect |
|---|---|
| `-Ppodium.debuggable=true` | Makes the debug build attachable (slower cold start) |
| `-Ppodium.minifyDebug=true` | Runs R8 on a debug build to reproduce release-only bugs |
| `-Ppodium.applicationIdSuffix=.walktest` | Installs beside an existing copy without wiping data |
| `-Ppodium.versionCode=8 -Ppodium.versionName=0.1.5.2` | Fakes a newer build to test the updater |

Release signing reads `keystore.properties` (ignored) or `PODIUM_KEYSTORE_*` env vars. Without
them the release APK is unsigned. JDK **21** and an Android SDK are required.

---

## 3. Repository map (where things live)

```
app/                     Application, MainActivity, PodiumApp shell, AppGraph (manual DI),
                         repositories, settings persistence, guide, stickers, Podium Space, updater,
                         radio/, together/, downloads/, LocalMetadataEditorScreen.kt
core/
  model/                 Pure Kotlin ids and models (Track, Album, Artist, Playlist, Queue, ...)
  common/                Pure Kotlin utilities (Clock, DiskActivity, ...)
  lyrics/                Pure Kotlin LRC parsing/matching
  database/              Room database, entities, DAOs, FTS5 search, migrations, backups
  designsystem/          ALL visual tokens + shell + wheel + glass + typography + shared components
  interaction/           Wheel gesture detector, InputRouter, focus semantics, haptics
sources/
  api/                   Pure Kotlin source contract (MusicSource, SourceRegistry, matcher, ...)
  local/                 MediaStore-backed local library
  youtubemusic/          YouTube Music browse/search/auth/stream walker
  addon/                 User-added BitChord-protocol stream sources
  test/                  Debug-only fixture source
player/
  api/                   Playback contracts (PlaybackController, PlaybackState, Queue, ...)
  service/               Media3 MediaLibraryService, ExoPlayer engine, QueueManager, resolver
  remote/                MediaSession-based remote playback
feature/
  library/               Home, Music, Cover Flow, Albums, Artists, Songs, Favorites, Playlists
  online/                ONLINE section: menu, home, explore, search, artist, album, playlist, radio
  nowplaying/            Now Playing, Up Next, Lyrics, orb, vinyl stage, tray
  settings/              Settings tree, appearance editors, equaliser, sources, about
tools/newpipe-poc/       Research only, not shipped
build-logic/             Gradle convention plugins
docs/                    Authoritative long-form docs
artifacts/               Build output (APKs ignored by git)
site/                    Vercel marketing site
third_party/             License texts
```

Dependency direction: `feature/*` → `core:designsystem`, `core:interaction`, `player:api`,
repository interfaces. Domain modules (`core:model`, `core:common`, `core:lyrics`, `sources:api`,
`player:api`) contain **no Android imports**. Only `app` names concrete sources.

---

## 4. Boot and object graph

```
PodiumApplication
  └─ AppGraph(context)                 // composition root, built once (app/src/main/kotlin/app/podium/AppGraph.kt)
       ├─ SourceRegistry + SourceHealthMonitor + TrackMatcher
       ├─ PodiumDatabase (podium.db)
       ├─ LocalMusicSource, YouTubeMusicSource, addon sources
       ├─ PlaybackService dependencies, StreamResolver, artwork loader
       ├─ playbackController = OwnerAwarePlaybackController(local, remote, ...)
       └─ repositories: library, online, playlists, settings, favorites, ...
MainActivity (portrait, edge-to-edge)
  └─ PodiumApp(graph)
       ├─ Power.BOOTING → BootScreen → Power.ON
       ├─ Navigator + back stack + Navigation3 NavDisplay
       ├─ VirtualScreen { title bar + screen content + mini player + PodWheel }
       └─ global input (MENU, transport, hold-power = off, double-⏯ = Now Playing)
```

Important: **`AppGraph` is the only place concrete sources are named.** Adding a source means
editing it. State holders belong in `AppGraph` or app-level repositories, not inside screens.

---

## 5. UI mental model — no tabs, one paper, one wheel

### 5.1 Destinations, not tabs

`Dest` is a sealed interface in `app/src/main/kotlin/app/podium/PodiumApp.kt`. It enumerates every
place:

- Fixed roots: `Home`, `Music`, `CoverFlow`, `Albums`, `Artists`, `Songs`, `Favorites`, `Playlists`,
  `NowPlaying`, `UpNext`, `Lyrics`, `Settings`, `Appearance`, `DeviceBody`, `VirtualDisplay`,
  `Theme`, `Font`, `LyricsFont`, `LyricsTheme`, `Background`, `Finish`, `CustomColor`,
  `DisplayColor`, `OnlineSources`, `OnlineChannel`, `Equalizer`, `CustomSources`,
  `AddCustomSource`, `ListenTogether`, `JoinTogetherAddress`, `Downloads`, `DownloadsCoverFlow`.
- Data-carrying places: `Album(id)`, `Artist(id)`, `Playlist(id,name)`, `AddSongs(id)`,
  `NamePlaylist(...)`, `MusicFolders(path)`, `Level(level)`, `EqualizerBand(band)`,
  `CustomSource(id)`, `CustomSourceOption(id,key)`, `Online(place)`, `Radio(place)`,
  `JoinTogetherCode(host,port,name)`, `EditLocalSong(trackId)`,
  `EditLocalAlbum(albumId,title,artist)`, `EditLocalArtist(artistId,name)`.

Navigation: `Navigator` (same file) owns a `SnapshotStateList<Dest>`. `push`, `pop`,
`showNowPlaying`. The stack is serialised by `BackStackSaver` using `Dest.encode()` /
`decodeDest()`, so it survives process death. Sheets, menus, HUDs and overlays are **not**
destinations.

`ScreenContent(...)` is the giant `when (screen)` that maps each `Dest` to a composable in
`feature/*`. **If a screen appears nowhere, it does not exist.**

### 5.2 The shell

`core/designsystem/src/main/kotlin/app/podium/core/designsystem/shell/VirtualScreen.kt`
draws the screen, and `app/.../PodiumApp.kt` composes the shell:

- `ScreenHeader` — title bar: back housing, animated title, status pills (time, output, play/pause,
  battery). File: `VirtualScreen.kt`.
- Screen content — `NavDisplay`, transition and previous-column glimpse, in `ScreenOs` inside
  `PodiumApp.kt`.
- `MiniPlayer` — `core/designsystem/.../component/Chrome.kt` (see §7.1), composed in `ScreenOs`.
- `PodWheel` — `core/designsystem/.../component/PodWheel.kt`, below the display, owned by
  `PodiumApp`.
- Menus/notices — `GlassMenu` / `GlassNotice` overlays; `LocalOverlayHost`.

`ScreenOs` also owns: atmosphere/background, screen insets, mini-player visibility
(`Dest.showsMiniPlayer()`), full-screen lyrics, defocus behind overlays, update prompt, keyboard
host, immersive moments (vinyl, stickers, space).

### 5.3 The Wheel

`PodWheel` emits semantic `PodiumInput` only. It never touches playback or navigation directly.
Button enum: `core/interaction/src/main/kotlin/app/podium/core/interaction/PodiumInput.kt`.

| Zone | Semantic | Default meaning |
|---|---|---|
| Top | `WheelButton.MENU` | Back; **hold = Home** |
| Right | `WheelButton.NEXT` | Next track |
| Bottom | `WheelButton.PLAY_PAUSE` | Play/pause; **hold = power off**; **double = Now Playing** |
| Left | `WheelButton.PREVIOUS` | Previous track |
| Center | `WheelButton.CENTER` | Activate focused item; long-press = context/More |
| Ring rotation | `PodiumInput.Rotate` | Move focus; volume on Now Playing |

Legends drawn in `PodWheel`: `MENU` text top, `Next` icon right, `Play/Pause` icon bottom,
`Previous` icon left. Mechanics: 18°/detent, 20 detents per revolution, ×1/×2/×4 acceleration.
`InputRouter` also maps keyboard, D-pad, rotary encoders, mouse wheel and TalkBack to the same
semantic events.

### 5.4 Lists

Never hand-roll a vertical list. Use `FocusList` and `ListInputEffect` from
`core:designsystem/component`:

- `FocusList` draws rows on the paper/carbon with focus lens and keep-in-view.
- `ListInputEffect(focus, onActivate, onLongPress)` wires wheel input to it.
- `rememberFocusListState("unique-key")` keeps focus across recomposition.
- `MenuRow`, `TrackRow`, `DetailHeader`, `MenuPreview` live in
  `core/designsystem/.../component/Rows.kt`.

This is how a row becomes focusable, scrolls, previews its next column and responds to Center.

---

## 6. Screen-by-screen element map

Legend: **“Row list”** means the order is a Kotlin `enum` or `buildList {}` that you can reorder
directly. **“Row label”** is the visible English string.

### 6.1 Home (“main menu”)

| Item | Location |
|---|---|
| Screen | `feature/library/.../LibraryScreens.kt` → `HomeScreen` |
| Shared menu renderer | same file → `PaperMenu`, `MenuEntry` |
| Row order | `HomeScreen` → `val entries = buildList { ... }` |
| Rows | Source actions (lock icon), **Music**, **Online** (only if online connected), **Radio**, **Listen together** (with session code/host value), **Shuffle songs**, **Now Playing** (only if active), **Settings** |
| Preview art | `MenuPreview.Artwork`, `.Carousel`, `.Instrument` in `Rows.kt` |
| Navigation callbacks | passed from `ScreenContent` in `PodiumApp.kt` (`onMusic`, `onOnline`, ...) |

To move/rename a Home item: edit the `entries` builder in `HomeScreen`; the callbacks are wired in
`ScreenContent` under `Dest.Home`.

### 6.2 Music

| Item | Location |
|---|---|
| Screen | `LibraryScreens.kt` → `MusicScreen` |
| Row order | Source actions, **Cover Flow**, **Albums**, **Artists**, **Songs**, **Favorites**, **Playlists** (if repository present), **Downloads** (with count/artwork) |
| Renderer | same `PaperMenu` |
| Callbacks | `ScreenContent` under `Dest.Music` |

### 6.3 Songs

`LibraryScreens.kt` → `SongsScreen`.

- Loading: `LoadingScreen("Reading your music")`.
- Empty: `MessageState(PodiumSymbol.Note, "No songs yet", ...)`.
- Rows: `TrackRow(title, subtitle, focused, artworkUri, trailing = duration)`.
- Center: `onPlay(tracks, index)`; long-press: `songMenu(...)` → **Play next / Add to Up Next**,
  plus **Edit song info** for local tracks (v1.1 metadata editor).
- If you need to change the trailing text or add a badge, edit the `TrackRow` call in `SongsScreen`.

### 6.4 Albums / Album / Artists / Artist / Favorites / Cover Flow

File: `feature/library/src/main/kotlin/app/podium/feature/library/LibraryBrowse.kt`.

| Screen | Composable | Notable elements |
|---|---|---|
| Albums | `AlbumsScreen` | list of album rows, `onOpen(AlbumId)` |
| Album | `AlbumScreen` | `DetailHeader` (art, title, artist), **Play** / **Shuffle** rows, disc headers, track list |
| Artists | `ArtistsScreen` | artist rows, `onOpen(ArtistId)` |
| Artist | `ArtistScreen` | header, **Play** / **Shuffle**, albums, top songs / all songs |
| Favorites | `FavoritesScreen` | local liked tracks; play/shuffle; empty state |
| Cover Flow | `CoverFlowScreen` | 3D cover carousel; center opens album; flip shows tracks |

Shared helpers in the same file: `rememberTracks`, `rememberLoaded`, `EmptyState`,
`noMusicMessage`, `noMusic`, `songMenu`.

### 6.5 Playlists

File: `feature/library/src/main/kotlin/app/podium/feature/library/PlaylistScreens.kt`.

| Screen | Composable | Buttons / rows |
|---|---|---|
| Playlists | `PlaylistsScreen` | list, **New playlist** row, empty state |
| One playlist | `PlaylistScreen` | mosaic header, **Play**, **Shuffle**, track rows, **Add songs**, rename/delete via long-press menu |
| Add songs | `AddSongsScreen` | library list with add/remove toggles |
| Name playlist | `NamePlaylistScreen` | `PodiumTextField`, **Create** / **Rename** |

Playlist state: `feature/library/.../Playlists.kt`; `moved(...)` handles reordering.

### 6.6 Now Playing

Primary file: `feature/nowplaying/src/main/kotlin/app/podium/feature/nowplaying/NowPlayingScreen.kt`.
Controls/layouts: `.../NowPlayingControls.kt`.

| Element | Location |
|---|---|
| Screen entry | `NowPlayingScreen(...)` |
| Layout switch | `PortraitLayout` → `StackedPortrait(p)` (new) / `NostalgicPortrait(p)` (nostalgic) |
| New layout | `NowPlayingControls.kt` → `StackedPortrait` |
| Nostalgic layout | `NowPlayingControls.kt` → `NostalgicPortrait` |
| Artwork + atmosphere | `NowPlayingScreen.kt` → `ArtworkStage` |
| Title/artist text | `NowPlayingScreen.kt` → `TrackText` |
| Status column | `NowPlayingScreen.kt` → `StatusColumn`, `StatusSlot`, `LoadingBar` |
| Seek bar + times | `NowPlayingControls.kt` → `NowPlayingProgress`, `SeekRule`, `NowPlayingTimes` |
| Transport keys | `NowPlayingControls.kt` → `NowPlayingTransport`, `TransportKey` |
| Action tray | `NowPlayingControls.kt` → `NowPlayingActions` |
| Quality chip | `NowPlayingControls.kt` → `QualityChip` |
| “More” menu | `NowPlayingScreen.kt` → `val showMore` |
| Sleep timer menu | `NowPlayingScreen.kt` → `sleepTimerMenu` |
| Wheel modes | `NowPlayingScreen.kt` → `enum class WheelMode { Volume, Scrub, Actions }` |
| Tray order | `NowPlayingScreen.kt` → `enum class Action { Shuffle, Repeat, Favorite, Lyrics, Vinyl, Queue, More }` |
| Action icon/label/on-state | `NowPlayingScreen.kt` → `actionLook` |
| Action tap behaviour | `NowPlayingScreen.kt` → `val perform` |
| Vinyl stage | `feature/nowplaying/.../VinylStage.kt` |
| Orb | `OrbStage.kt`, `OrbFace.kt`, `LiveOrb.kt` |

**Action tray (left → right):** Shuffle · Repeat · Favorite · Lyrics · Vinyl · Queue (label
“Up Next”) · More. To move a tray button, reorder the `Action` enum; the tray iterates
`Action.entries`. To change its icon or label, edit `actionLook`.

| Action | Icon (`PodiumSymbol`) | Label | Lit state |
|---|---|---|---|
| `Shuffle` | `ShuffleOn` when on, else `Shuffle` | Shuffle | `shuffleEnabled` |
| `Repeat` | `RepeatOne` when one, else `Repeat` | Repeat / Repeat all / Repeat one | `repeatMode` |
| `Favorite` | `Favorite` | Favorite | `isFavorite` |
| `Lyrics` | `Lyrics` | Lyrics | none |
| `Vinyl` | `Album` | Vinyl | none |
| `Queue` | `Queue` | Up Next | none |
| `More` | `More` | More | none |

**Wheel modes on Now Playing:** `Volume` (rotate = volume) → Center = `Scrub` (rotate = seek) →
Center = `Actions` (rotate = move tray lens, Center = activate) → MENU returns to `Volume`.
Idle auto-returns to Volume.

**More menu order:** Lyrics, Up Next, Add/Remove from favorites, Add to playlist (when allowed),
Go to album / Go to each artist, Sleep timer, then either remote-owner actions or Clear Up Next.

**Transport keys:** Previous, Play/Pause, Next are drawn by `NowPlayingTransport`; the *physical*
wheel transport is always active regardless of layout.

### 6.7 Up Next (queue)

`feature/nowplaying/.../UpNextScreen.kt`.

- Header rows / toggles: Shuffle, Repeat, Autoplay.
- “Now playing” row, then upcoming sections.
- Long-press/center menu: Clear Up Next, Save as playlist.
- Queue data comes from `PlaybackController`; never write the queue here.

### 6.8 Lyrics

`feature/nowplaying/.../LyricsScreen.kt`.

- Full-screen `Dest.Lyrics`: **no title bar, no mini player** (`ScreenOs` checks `top == Dest.Lyrics`).
- Themes: Classic / Continuous (`LyricsTheme.kt`), fonts in `LyricsFont.kt`.
- Current line full opacity, others ~45%; no glass behind lyrics.
- Background choice under Settings ▸ Appearance ▸ Virtual display ▸ Lyrics background.

### 6.9 ONLINE

Files: `feature/online/src/main/kotlin/app/podium/feature/online/`.

| Screen / place | Composable |
|---|---|
| Dispatcher | `OnlineScreen` in `OnlineScreens.kt` |
| ONLINE menu | `OnlineMenuScreen` in `OnlineScreens.kt` |
| Home | `OnlineHomeScreen` |
| Shelf | `OnlineShelfScreen` |
| Explore | `OnlineExploreScreen` |
| Genre | `OnlineGenreScreen` |
| Search | `OnlineSearchScreen` in `OnlineSearch.kt` |
| Liked songs | `OnlineLikedScreen` |
| History | `OnlineHistoryScreen` |
| Radio | `OnlineRadioScreen` |
| Live radio / live genre | `OnlineLiveRadioScreen`, `OnlineLiveGenreScreen` |
| Library | `OnlineLibraryScreen` in `OnlineLibrary.kt` |
| Library playlists/albums/artists | `OnlineLibraryPlaylistsScreen`, `OnlineLibraryAlbumsScreen`, `OnlineLibraryArtistsScreen` |
| Account history | `OnlineAccountHistoryScreen` |
| Playlists | `OnlinePlaylistsScreen` in `OnlinePlaylists.kt` |
| One online playlist | `OnlineMyPlaylistScreen` |
| Name/rename playlist | `OnlineNamePlaylistScreen` |
| Artist | `OnlineArtistScreen` in `OnlineDetail.kt` |
| Album / online collection | `OnlineCollectionScreen` |
| Rows / menus | `OnlineCommon.kt` → `OnlinePaperMenu`, `OnlineTrackRow`, `showTrackMenu`, `TrackPlaylists`, `OnlineArtistRow` |

ONLINE menu row order (`OnlineMenuScreen` → `entries`): **Home**, **Explore**, **Search**,
**Library**, **Radio**, **Live radio**, **History**, then a **Sign in / Sign in again** row when
not signed in. Rows are capability-gated (`canBrowse`, `canExplore`, `canSearch`, ...).

Places are serialised by `OnlinePlace` in `OnlinePlace.kt`. Add a place there **and** in
`OnlineScreens.kt`, or it cannot be navigated to.

### 6.10 Settings

Primary file: `feature/settings/src/main/kotlin/app/podium/feature/settings/SettingsScreens.kt`.

`SettingsScreen` uses a private `enum class SettingsRow` for the order and a `val rows =
SettingsRow.entries.filter { ... }` for build/capability gating. Reordering that enum reorders the
screen. The visible label and value are set in the `FocusList` item block.

Current order and row labels:

| # | Enum | Visible label | Value / action |
|---|---|---|---|
| 1 | `Appearance` | Appearance | display theme label → `AppearanceScreen` |
| 2 | `PodiumSpace` | Podium body | “Stickers, help, turn off” |
| 3 | `FullScreen` | Full screen | On/Off (immersive) |
| 4 | `TitleBarBattery` | Battery in title bar | “Full screen only” / Shown / Hidden; disabled unless immersive |
| 5 | `NowPlayingLayout` | Now Playing | New / Nostalgic |
| 6 | `HideNowPlayingTray` | Now Playing's tray | Always shown / Hides when idle |
| 7 | `Keyboard` | Keyboard | Podium / Phone |
| 8 | `MusicFolders` | Music folders | All / “n songs” |
| 9 | `OnlineSources` | service name (default “Online music”) | summary → `OnlineServiceScreen` |
| 10 | `CustomSources` | Custom sources | summary → `CustomSourcesScreen` |
| 11 | `SmartDownloads` | Smart downloads | On/Off; toggles automatic offline downloads |
| 12 | `Autoplay` | Autoplay | On/Off |
| 13 | `Recommendations` | Online recommendations | On/Off, disabled unless autoplay |
| 14 | `AvoidRepeats` | Avoid repeats | On/Off, disabled unless autoplay |
| 15 | `Crossfade` | Crossfade | Off / “n s”; opens a menu |
| 16 | `Equalizer` | Equaliser | summary → `EqualizerScreen` |
| 17 | `SleepTimer` | Sleep timer | summary/menu |
| 18 | `OnlineLyrics` | Online lyrics | On/Off |
| 19 | `LyricsCredit` | Lyrics from LRCLIB, written by its community | disabled credit row |
| 20 | `Haptics` | Haptics | profile label/menu, or On/Off |
| 21 | `Clicks` | Click sound | On/Off |
| 22 | `StartupSound` | Startup sound | On/Off |
| 23 | `SoftwareUpdate` | Software update | version / menu |

Rows 9, 10, 11, 17 and 23 disappear when the build has no online service, custom sources, smart
downloads, sleep timer or updater. Toggles usually call a repository setter and a haptic; look at `val activate` in
`SettingsScreen`.

**Settings ▸ Appearance** — `feature/settings/.../Appearance.kt`:
- `AppearanceScreen`: rows `DeviceBody` (“Device body”) and `VirtualDisplay` (“Virtual display”),
  order in `private enum class AppearanceRow`.
- `DeviceBodyScreen`: order in `private enum class BodyRow`: **Finish**, **Custom color**,
  **Grain**, **Glitter**, **Glitter amount**, **Glitter glow**, **Follow tilt**, **Glitter density**,
  **Glitter size**, **Glitter opacity**. Finish opens `FinishScreen`; levels open `LevelScreen`.
- `VirtualDisplayScreen`: order in `private enum class DisplayRow`: **Theme**, **Font**,
  **Lyrics font**, **Lyrics theme**, **Lyrics background** (Classic only), **Background**,
  **Background color**, **Background opacity** (image only), **No background** (Carbon only),
  **Contrast**. Rows are filtered by theme/background.
- `FontScreen`, `LyricsFontScreen`, `LyricsThemeScreen`, `BackgroundScreen`, `LevelScreen` in the
  same file.
- `ThemeScreen`, `FinishScreen`, `CustomColorScreen`, `Swatch` in `SettingsScreens.kt`.

**Other settings screens:**

| Screen | File / composable |
|---|---|
| Online music service | `OnlineService.kt` → `OnlineServiceScreen`; channels in `ServiceChannelScreen` |
| Custom sources | `CustomSources.kt` → `CustomSourcesScreen`, `CustomSourceScreen`, `AddCustomSourceScreen`, `CustomSourceTextOptionScreen` |
| Music folders | `MusicFolders.kt` → `MusicFoldersScreen`; `songsIncluded`, `foldersInside` |
| Equaliser | `Equalizer.kt` → `EqualizerScreen`, `EqualizerBandScreen`; `bandLabel`, `gainLabel` |
| Colour picker | `ColorPicker.kt` → `ColorBar`, `hsvToArgb` |

### 6.11 Online Radio

Files: `app/src/main/kotlin/app/podium/radio/RadioScreens.kt`, `RadioModels.kt`,
`RadioApiAndStore.kt`. Entry: `Dest.Radio(RadioPlace.Menu)`, dispatched by `RadioScreen`.

| Element | Location |
|---|---|
| Places/back-stack encoding | `RadioPlace` (`Menu`, `CoverFlow`, `Category`, `Search`, `AddCustom`, `EditStation`) |
| Dispatcher | `RadioScreen` |
| Menu | `RadioMenuScreen` |
| Cover Flow | `RadioCoverFlowScreen` |
| Category list | `RadioCategoryScreen` |
| Search | `RadioSearchScreen` |
| Add station | `RadioAddCustomScreen` |
| Edit station | `RadioEditStationScreen` |
| Rows model | `RadioMenuRow` in `RadioScreens.kt` |
| Categories | `RadioCategory` in `RadioModels.kt` (`FAVORITES`, `TUNISIA`, `TOP_WORLD`, `NEWS`, `MUSIC`, `QURAN`, `CULTURE`, `SPORTS`, `RECENTS`) |
| Station menu | `showStationMenu` |
| Play helper | `playStations` |

Radio menu order (`RadioMenuScreen` → `buildList`): **Cover Flow**, **Tunisian FM**,
**Favorites**, **Worldwide**, **News**, **Music**, **Quran**, **Culture**, **Sports**,
**Search stations**, **Add custom station**, then **Recently played** entries when present.
Long-press a recent station for `showStationMenu` (favorite, edit, remove, …).

### 6.12 Listen Together

Files: `app/src/main/kotlin/app/podium/together/TogetherScreens.kt`, `TogetherSession.kt`,
`TogetherProtocol.kt`.

| Screen | Composable |
|---|---|
| Listen together (host/join) | `ListenTogetherScreen` |
| 4-digit session code | `JoinTogetherCodeScreen` |
| Host address entry | `JoinTogetherAddressScreen` |
| Row model | `TogetherRow` in `TogetherScreens.kt` |

`ListenTogetherScreen` rows are state-dependent (`TogetherState`):

- **Idle:** Start hosting, Nearby sessions, Join by address, explanatory note.
- **Hosting:** session code, address, now playing, “Guests can control”, Find songs, listeners,
  End session.
- **Guest:** host name/listener count, host's current song, Resync, Listen here, host
  play/pause/next/previous (when allowed), Find songs, host queue, Leave session.

The code screen uses wheel rotation to change a digit and Center/Next/Previous to move between
digits; the fourth Center submits.

### 6.13 Downloads

Files: `app/src/main/kotlin/app/podium/downloads/DownloadsScreens.kt`,
`OfflineDownloadManager.kt`. Entries: `Dest.Downloads`, `Dest.DownloadsCoverFlow`.

| Element | Location |
|---|---|
| Downloads list | `DownloadsScreen` |
| Downloads Cover Flow | `DownloadsCoverFlowScreen` |
| Rows model | `DownloadsRow` in `DownloadsScreens.kt` |
| Manager / state | `OfflineDownloadManager` (`allEntries`, `completedTracks`, `smartDownloadsEnabled`) |

Row order: **Cover Flow**, **Play all**, **Shuffle** (when >1), **Smart downloads** toggle,
**Retry failed** (when failures exist), **In progress** section, **n downloaded** section,
**Remove all**. Empty state offers **Find songs**. A completed row plays the downloaded queue; a
failed row retries; an active row opens a Cancel download menu.

### 6.14 Local metadata editor

File: `app/src/main/kotlin/app/podium/LocalMetadataEditorScreen.kt`.

| Screen | Composable | Row enum |
|---|---|---|
| Edit song | `EditLocalSongScreen` | `EditSongRow` |
| Edit album | `EditLocalAlbumScreen` | `EditAlbumRow` |
| Edit artist | `EditLocalArtistScreen` | `EditArtistRow` |

- **Song:** header, title field, artist field, album field, lyrics field, **Paste lyrics**,
  **Clear lyrics** (when present), **Reset original** (when an edit exists), **Save changes**.
- **Album:** title field, artist field, **Save changes**.
- **Artist:** name field, **Save changes**.
- Fields use `PaperTextField`; edits are stored by `LocalMusicSource` and re-synced into the
  library.
- Entry points are the long-press menus in `feature/library/.../LibraryBrowse.kt`:
  **Edit song info**, **Edit album info**, **Edit artist name**, provided through
  `LocalSongEditorHost` / `LocalSongEditor`.

### 6.15 First-run guide, Podium Space, stickers, update

| Feature | File |
|---|---|
| Six-step guide | `app/src/main/kotlin/app/podium/guide/Guide.kt` → `GuideStep` (WELCOME, TURN, CENTER, MENU, ...) |
| Guide persistence | same file → `GuideStore` (`podium_guide` prefs) |
| Podium Space (pinch-out 3D) | `app/src/main/kotlin/app/podium/space/` |
| Stickers / LiteRT cutout | `app/src/main/kotlin/app/podium/stickers/` |
| Background image decode | `app/src/main/kotlin/app/podium/DisplayImages.kt` |
| Update centre / prompt | `app/src/main/kotlin/app/podium/update/` |
| Boot screen / power-off | `core/designsystem/.../shell/BootScreen.kt`, `app/.../Farewell.kt` |

---

## 7. Global elements and shared components

### 7.1 Title bar and mini player

| Element | File / symbol |
|---|---|
| Header container | `core/designsystem/.../shell/VirtualScreen.kt` → `ScreenHeader` |
| Back housing + chevron | `ScreenHeader` inside the `if (canGoBack)` block |
| Animated title | `ScreenHeader` → `AnimatedContent(targetState = title to depth, ...)` |
| Status pills | `ScreenHeader` → `StatusPills`; items built in `val status` |
| Clock (full screen) | `ScreenHeader` → `TitleBarClock` / `core/designsystem/.../shell/TitleBarStatus.kt` |
| Output icon | `OutputIcon.kt`; header call in `ScreenHeader` |
| Play/pause light | `ScreenHeader` → `Symbol(PodiumSymbol.Play/Pause)` with blinking paused light |
| Battery | `TitleBarBattery` |
| Mini player | `core/designsystem/.../component/Chrome.kt` → `MiniPlayer`; composed in `PodiumApp.kt` → `ScreenOs` |
| Mini player visibility | `PodiumApp.kt` → `Dest.showsMiniPlayer()` and `ScreenOs`’ `miniPlayerVisible` |
| Global back stack / power | `PodiumApp.kt` → `GlobalInput`, `Power` |

Title bar height = `Spacing.titleBar` = `edge + housing + edge` = 52 dp.

### 7.2 Wheel

`core/designsystem/.../component/PodWheel.kt` → `PodWheel`.

- Diameter: `wheelDiameter(windowWidth)` — 66% of width, clamped 232–312 dp.
- Zones/angles: `WheelButton.MENU -> -90f`, `NEXT -> 0f`, `PLAY_PAUSE -> 90f`,
  `PREVIOUS -> 180f`.
- Legends: `Legend(0f, MENU, "Back")`, `Legend(90f, NEXT, "Next track")` with
  `PodiumSymbol.Next`, `Legend(180f, PLAY_PAUSE, ...)` with `Pause/Play`, `Legend(270f,
  PREVIOUS, "Previous track")`.
- Center fraction 0.38 of diameter.
- Colours/materials: glass via `palette`; solid finishes paint the body/wheel, never the screen.

### 7.3 Shared rows and surfaces

| Component | File | Use |
|---|---|---|
| `MenuRow` | `core/designsystem/.../component/Rows.kt` | menu/settings row: label, value, chevron, leading icon |
| `TrackRow` | same | song row: art, title, subtitle, trailing |
| `DetailHeader` | same | album/artist/playlist header |
| `MenuPreview` | same | preview column for menus |
| `FocusList` | `component/FocusList.kt` | every vertical list |
| `GlassSurface`, `Modifier.glass` | `glass/Glass.kt` | glass only here |
| `ContinuousShape` / `continuousRect` | `tonal/ContinuousShape.kt` | outside-instrument rounded rects |
| `TonalSurface` | `tonal/TonalSurface.kt` | Carbon surfaces |
| `LiveOrb` | `orb/LiveOrb.kt` | Now Playing orb |
| `PodiumSymbol` | `symbol/Symbols.kt` | all icons |
| `PodiumTextField` | `component/PaperTextField.kt` | the only text field |
| `PodiumKeyboard` | `component/PodiumKeyboard.kt` | wheel-morphed keyboard |

---

## 8. Colour, typography, spacing

### 8.1 Colour tokens

File: `core/designsystem/src/main/kotlin/app/podium/core/designsystem/theme/Colors.kt`.

`PodiumColors` fields: `isDark`, `canvas`, `canvasRaised`, `labelPrimary`, `labelSecondary`,
`labelTertiary`, `separator`, `highlight`, `highlightText`, `onHighlight`, `like`, `critical`,
`warning`, `positive`, `glassTint`, `glassRim`, `shadow`, `style`.

| Token | Dark | Light |
|---|---|---|
| canvas | `#0C0E13` | `#F5F7F9` |
| canvasRaised | `#171A1F` | `#FFFFFF` |
| labelPrimary | `#F5F7F9` | `#161A1F` |
| labelSecondary | `#AEB3BA` | `#52575F` |
| labelTertiary | `#7C8088` | `#787C83` |
| separator | `#2B2E33` | `#D8DBDF` |
| highlight | `#367FE0` | `#1153BA` |
| highlightText | `#539AF2` | `#1C65C8` |
| onHighlight | `#FFFFFF` | `#FFFFFF` |
| like | `#F46A82` | `#CE2854` |
| critical | `#F7665B` | `#CC2827` |
| warning | `#F5AE39` | `#B17000` |
| positive | `#61CB7C` | `#218A45` |

Other palettes:

| Palette | canvas | labelPrimary | highlight | style |
|---|---|---|---|---|
| `CarbonTonalColors` (Carbon) | `#0B0B0D` | `#F3F1EC` | `#E6E3DC` | `TONAL` |
| `CarbonColors` (industrial / Classic lyrics) | `#0A0A0A` | `#F2F0EB` | `#F2F0EB` | `INDUSTRIAL` |
| `PaperColors` (Custom light / Classic lyric paper) | `#ECE8DF` | near-black | near-black | `INDUSTRIAL` |

`DisplayStyle` = `GLASS`, `INDUSTRIAL`, `TONAL`. Branch on `colors.isTonal` /
`colors.isIndustrial` — **never on the theme's display name** in screens.

### 8.2 Display themes and finishes

- `DisplayTheme` (`theme/Theme.kt`): **Glass**, **Carbon**, **Custom**. (Bone was withdrawn;
  `PaperColors` remains as a palette.)
- `FinishPreset` (`core/designsystem/.../shell/DeviceAppearance.kt`): **Glass**, **Steel gray**,
  **Burgundy**, **Glacier blue**, **Silver**, **Custom color**.
- Finishes change the **body and wheel**, never the virtual screen's colours.

### 8.3 Typography

`core/designsystem/.../type/Typography.kt` and coverage files in the same folder.

- Display fonts: 17 typefaces (Classic, Gothic, ...) selectable in Appearance ▸ Font.
- Lyrics fonts: 7 faces, selectable in Appearance ▸ Lyrics font.
- Use `PodiumText` / `PodiumTheme.type` styles (`title`, `wheelLegend`, ...). Do not invent text
  styles; add a token if needed.

### 8.4 Spacing and metrics

File: `theme/Theme.kt` → `object Spacing`.

| Token | Value |
|---|---|
| `xxs` | 2 dp |
| `xs` | 4 dp |
| `s` | 8 dp |
| `m` | 12 dp |
| `l` | 16 dp |
| `xl` | 20 dp |
| `xxl` | 24 dp |
| `xxxl` | 32 dp |
| `gutter` | 16 dp |
| `menuRow` | 52 dp |
| `trackRow` | 60 dp |
| `edge` | 10 dp |
| `housing` | 32 dp |
| `titleBar` | 52 dp (`edge + housing + edge`) |
| `miniPlayer` | 56 dp |

---

## 9. Animation system

Tokens: `core/designsystem/.../theme/Theme.kt` → `PodiumMotion`.

| Token / function | Value / use |
|---|---|
| `Spatial` | cubic-bezier `(0.2, 0.7, 0.2, 1)` |
| `SpatialMillis` | 420 ms — moves along the paper |
| `Standard` | cubic-bezier `(0.2, 0, 0, 1)` |
| `EmphasizedDecelerate` | cubic-bezier `(0.05, 0.7, 0.1, 1)` |
| `focus()` | spring stiffness 1400, damping 0.86 |
| `focusFast()` | spring stiffness 2400, no bounce |
| `pressIn()` | spring stiffness 6000, damping 0.9 |
| `pressOut()` | spring stiffness 1500, damping 0.6 |
| `dramatic()` | spring stiffness 220, damping 0.85 |
| `contentIn()` | 160 ms (120 ms reduced motion) |
| `fadeFast()` | 120 ms (0 reduced) |
| `fadeStandard()` | 220 ms (150 reduced) |
| `atmosphere()` | 900 ms (200 reduced) |
| `settleBack()` | spring 300, damping 1 — ~380 ms |
| `rearrange()` | spring 220, damping 0.88 — ~330 ms |

Rules:

- Use `PodiumTheme.motion.*`; no ad-hoc durations in screens.
- Reduced motion is detected from the system animator scale and swaps spatial spring moves for
  120–160 ms cross-fades (`rememberReducedMotion` in `Theme.kt`).
- Navigation transitions: `paperForward` / `paperBack` / `paperMove` / `arc` / `paperPoints` in
  `PodiumApp.kt`; the previous column glimpses via `PreviousColumn`, `softArrival`,
  `softDeparture`, `riseFromMiniPlayer`. Now Playing rises from the mini player on Glass only.
- Tray hide/show: `rememberTrayAway` in `NowPlayingControls.kt`.
- Scroll-edge fade: `Modifier.scrollEdgeFade` in `VirtualScreen.kt`.
- Paused title-bar light: `PauseLitMillis`, `PauseDim`, `PauseDarkMillis`, `PauseFadeMillis` in
  `VirtualScreen.kt`.

Full narrative: `docs/animation-system.md`.

---

## 10. Tools and features — where they live

| Feature | Implementation |
|---|---|
| Local library | `sources/local/` (MediaStore), `core/database/` (Room, FTS5) |
| YouTube Music | `sources/youtubemusic/` (auth, browse, search, stream walker, PoTokens) |
| Custom stream sources | `sources/addon/`, UI in `feature/settings/CustomSources.kt` |
| Online Radio | `app/radio/` (`RadioScreens.kt`, `RadioModels.kt`, `RadioApiAndStore.kt`) |
| Listen Together | `app/together/` (`TogetherScreens.kt`, `TogetherSession.kt`, `TogetherProtocol.kt`) |
| Offline downloads | `app/downloads/` (`DownloadsScreens.kt`, `OfflineDownloadManager.kt`) |
| Local metadata / lyrics editor | `app/LocalMetadataEditorScreen.kt` + `sources/local/` edit storage |
| Playback service | `player/service/` → `PlaybackService`, `PodiumPlaybackEngine`, `QueueManager` |
| Remote playback | `player/remote/` |
| Queue / autoplay / sleep timer / crossfade / EQ | `player/service/`, contracts in `player/api/` |
| Lyrics (LRC, LRCLIB) | `core/lyrics/`, `feature/nowplaying/LyricsScreen.kt`, `app/SongPages.kt` |
| Equaliser | `feature/settings/Equalizer.kt`, engine in `player/service/` |
| Music folders | `feature/settings/MusicFolders.kt`, `app/RegistryMusicFolders.kt` |
| Online service/account | `feature/settings/OnlineService.kt`, `app/AppOnlineServiceSettings.kt` |
| Sign-in WebView | `app/WebSignInActivity.kt`, `app/HiddenOriginPage.kt` |
| Artwork | `core/designsystem/artwork/Artwork.kt`, `app/ResolvingArtworkLoader.kt` |
| Stickers | `app/stickers/` + LiteRT model in `app/src/main/assets` |
| Podium Space | `app/space/` |
| Guide | `app/guide/Guide.kt` |
| Updates | `app/update/`, Settings ▸ Software update |
| Debug hooks | `AppGraph` flows `debugOpen`, `debugGuide`, `debugSpace`, `debugStickerSource`, `debugArrange`, `debugLyrics` |
| Test fixtures | `sources/test/` (debug only) |

Playback rule: **UI never imports Media3 and never touches ExoPlayer.** All UI calls
`PlaybackController`; `QueueManager` is the only queue writer.

---

## 11. Rules and traps (condensed; full list in `CLAUDE.md` / `AGENTS.md`)

1. **Local/offline playback is the stable core.** Do not rewrite it for online work.
2. **YouTube Music is the only online catalogue** and the default/fallback stream.
3. **Custom sources are stream-only addons.** They are asked first for an EXACT copy, never enter
   `SourceRegistry`, are never browsed/searched, and never drive recommendations/history.
4. **Identity is source-qualified:** `TrackId = "<sourceId>|<providerKey>"`. Cross-source fallback
   only at matcher tier **EXACT**, never mid-track (except a custom EXACT copy taking over), never
   across `LOCAL` ↔ `ONLINE`.
5. **No Material3.** Compose foundation/ui + Podium tokens only.
6. **Glass only through `core:designsystem`** (`Modifier.glass`, `GlassSurface`). No direct
   `Modifier.blur`, `RenderEffect`, `RuntimeShader` or Backdrop imports in feature modules. Glass
   only on functional elements, never rows/cards/artwork/screen/text containers.
7. **Providers never leak into generic UI.** Branch on capabilities, availability, route and owner.
8. **The virtual screen is sacred.** Finishes change body/wheel only, never screen colours.
9. **No hand-rolled vertical lists.** Use `FocusList` and `ListInputEffect`.
10. **Copy lives in Kotlin.** No `strings.xml`. Sentence case; all-caps only for wheel `MENU` and
    the boot wordmark. Errors explain and give a next step.
11. **Use `ContinuousShape` / `continuousRect`** for rounded rectangles outside the instrument, not
    `RoundedCornerShape`. Capsules stay capsules; the instrument keeps squared corners.
12. **State holders** belong in `AppGraph`/repositories, not screens. **Don't write the queue**
    outside `QueueManager`.
13. **Docs travel with code.** Update the relevant `docs/` file in the same commit; record
    significant decisions in `docs/decision-log.md`.
14. **Comments explain why**, and cite decision ids (`D-xx`, `ADR-xxx`) when behaviour is
    non-obvious.
15. **No new dependency** without checking `gradle/libs.versions.toml` and the layering rules.
16. **Migrations:** bump `PodiumDatabase.VERSION`, add a `Migration` + test, update
    `docs/data-model.md`.
17. **Formatting:** 4 spaces, LF, ordered imports, no wildcards (`.editorconfig`).
18. **Windows paths in old docs** (`D:\...`) are the author's machine; ignore them.

---

## 12. Recipes

### 12.1 Move a button inside a screen

1. Identify the screen composable in §6.
2. If the order is an enum, reorder the enum (e.g. `Action`, `SettingsRow`, `BodyRow`,
   `DisplayRow`). If it is a `buildList`, move the `add(...)` line.
3. If the button is in a `Row`, move its composable call within the `Row`; keep spacing tokens
   (`Spacing.s`, `Spacing.m`, ...) rather than hard-coded dp.
4. Check the focus index logic: `rememberFocusListState` and `ListInputEffect` use list indices;
   enums keep this automatic. If you hard-coded an index, update it.
5. Run the module test and take a screenshot on device.

### 12.2 Move a button in Now Playing's tray

- Reorder `enum class Action` in `NowPlayingScreen.kt`. The tray iterates `Action.entries` and
  draws one slot per entry.
- Update `actionLook` if the icon/label should travel with the action (it already keys on the
  enum).
- If you add an action, add it to `Action`, `actionLook`, and `perform`, and check
  `WheelMode.Actions` clamping.

### 12.3 Rename a visible label

- Find the exact string with `grep -rn "\"Label\"" feature/ app/`.
- Settings labels are in the `FocusList` item block of `SettingsScreen`.
- Menu labels are in the `buildList` of the menu composable.
- Do **not** move copy into resources.

### 12.4 Add a Settings row

1. Add a value to `enum class SettingsRow` (position = display order).
2. Add an `activate` branch (toggle or navigate).
3. Add a `MenuRow(...)` branch in the `FocusList` item block.
4. Add the backing setting to `DeviceSettingsRepository` / `SharedPrefsDeviceSettings.kt` if new.
5. Add any gating to the `rows` filter.
6. Update `docs/` and the Settings table in this file if the order changes.

### 12.5 Add a destination/screen

1. Add a `Dest` case in `PodiumApp.kt`.
2. Add it to `fixedDests` if it has no data, or to `encode`/`decodeDest` if it carries data.
3. Add the `when (screen)` branch in `ScreenContent` and wire callbacks.
4. Add a composable in the owning `feature/` module; use `FocusList`, `MenuRow`/`TrackRow`.
5. Add the title to `titleOf`.
6. Add the navigation call (usually `navigator.push(Dest.X)`).

### 12.6 Change a colour or add a theme

- Change tokens in `theme/Colors.kt` only. Do not hard-code `Color(...)` in feature modules.
- Carbon-specific surfaces go through `tonal/` (`tonalSurface`, `AlbumTone`).
- If the new look needs a new `DisplayStyle`, branch on that style, not on a theme name.

### 12.7 Change motion

- Use or add a token in `PodiumMotion` (`theme/Theme.kt`); do not inline `tween(...)` values.
- Respect `motion.reduced`; check `docs/animation-system.md` §5.

---

## 13. Search cheat sheet

```bash
# Find a composable
grep -rn "fun HomeScreen" feature app

# Find a visible string
grep -rn "\"Now Playing\"" feature app

# Find a colour literal (should be rare outside theme/)
grep -rn "Color(0x" feature app

# Find every settings row label
grep -n "MenuRow(" feature/settings/src/main/kotlin/app/podium/feature/settings/SettingsScreens.kt

# Find navigation to a destination
grep -rn "navigator.push(Dest\." app/src/main/kotlin/app/podium/PodiumApp.kt

# Find an animation token
grep -rn "PodiumTheme.motion\|motion\." feature/nowplaying | head
```

Useful long-form docs:

| Topic | Doc |
|---|---|
| Architecture / decisions | `docs/architecture.md`, `docs/decision-log.md` |
| Screens | `docs/screen-inventory.md` (note: some rows predate current code) |
| Navigation | `docs/navigation-map.md` |
| Design system / colours / type | `docs/design-system.md` |
| Motion | `docs/animation-system.md` |
| Wheel / focus / keyboard / haptics | `docs/interaction-model.md`, `docs/accessibility.md` |
| Playback / queue / audio | `docs/audio-architecture.md`, `docs/playback-state-machine.md`, `docs/queue-and-autoplay.md` |
| Data | `docs/data-model.md` |
| Sources | `docs/architecture/MUSIC_SOURCE_ARCHITECTURE.md`, `.../YOUTUBE_MUSIC_ARCHITECTURE.md`, `.../CUSTOM_SOURCES.md` |
| Lyrics | `docs/architecture/LYRICS_ARCHITECTURE.md` |
| Customization / Space | `docs/architecture/PODIUM_CUSTOMIZATION.md`, `.../PHYSICAL_SPACE.md` |
| Build / release | `docs/deployment.md` |
| Tests | `docs/testing-strategy.md` |

If this guide and the code disagree, **the code wins**; update this guide in the same change.
