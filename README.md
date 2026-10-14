# Jigsaw

Android jigsaw puzzle game that pulls its pictures from an online folder you already have.

## Image sources

Sources are pluggable (`source/ImageSource.kt`); each one only has to list images and say how to fetch
a thumbnail and a full-size version. Built in:

| Type | Link you paste | How it's read |
|---|---|---|
| **Nextcloud** public share | `https://cloud.example.com/s/<token>` (subpaths and `index.php/` are fine) | WebDAV `PROPFIND` on `public.php/webdav/` (falls back to `public.php/dav/files/<token>/`), recursing into subfolders. Gallery uses the share's server-side previews. Optional share password. |
| **Immich** shared link | `https://photos.example.com/share/<key>` or custom `…/s/<slug>` | `GET /api/shared-links/me`; album shares list assets via `/api/albums/{id}` on older servers, or the timeline API (`/api/timeline/buckets` + `/bucket`) on servers whose album response no longer includes assets (e.g. Immich 3.x). Gallery uses `size=thumbnail`, puzzles use the ~1440 px `size=preview`. Videos are skipped. Password-protected Immich links are not supported. |
| **URL list** | any URL to a text file (one image URL per line, `#` comments) or a JSON array of URLs | Relative entries resolve against the list's URL. |

Adding a new backend: implement `ImageSource`, add a `SourceConfig` variant, and wire it in `toImageSource()`,
`SourceStore`, and the add-source dialog.

## Gameplay

- The main view shows every image from all configured sources on one timeline, newest first (Immich: capture
  time; Nextcloud: capture time where the server provides it — Nextcloud 28+ — else modification time; URL lists
  have no dates and go last). A source that fails to load is reported above the grid without hiding the others.
- **Settings** (gear icon) holds the image sources (add/remove; tap a share link to open it in the browser) and
  puzzle preferences.
- Tap an image → a puzzle with classic tab/blank cuts. **Pieces** (Settings: about 12, 24, 48 or 96; the grid
  adapts to the picture's shape) applies to new puzzles and to ones with nothing on the board yet, which are cut
  afresh.
- Drag pieces; touching uses the real piece outline (tabs included).
- Drop a piece next to its true neighbour and they join and move as a group.
- The puzzle is solved once every piece is joined into one group; the board outline is just a guide.
- Groups can hang partly off-screen (half a cell always stays grabbable), so big halves can meet edge to edge.
- Optional **Snap pieces onto board** (Settings, off by default): pieces dropped near their spot lock in place.
- **Hint** shows a faint copy of the picture on the board.
- Optional **Rotate pieces** (Settings, off by default): pieces start out turned by quarter turns; tap a piece to
  turn its group clockwise. Pieces only join in the same orientation, only upright groups lock onto the board, and a
  puzzle is solved once it is one upright group. A puzzle counts as new until a piece sits on the board, so
  changing the setting turns (or straightens) its loose pieces in place, joined groups included; puzzles already
  under way keep the mode they started with.
- **⋮ → Start over** (with confirmation) cuts and scatters the puzzle anew with the current settings.
- Every image keeps its own puzzle: progress is saved after each move (and when leaving), so going back and
  reopening an image — even after the app was closed — resumes exactly where it was left, for any number of
  images. Saves live in the app's private storage (`files/puzzles/`, one small JSON per image) and are
  removed together with their source.
- Solved images open as the plain picture; **Play again** starts a fresh cut.
- Gallery tiles show each puzzle's state, zoomed on the pieces: before it is started, exactly what opening it would
  show (its pieces freshly scattered); once opened, its loose pieces as left; as soon as a piece sits on the board,
  only the board with what has been placed; once solved, the picture. Tiles are drawn from the saved state over the
  thumbnail, so they always match the save.

## Build

Requires JDK 17 and an Android SDK (compileSdk 36) referenced by `local.properties` (`sdk.dir=…`) or `ANDROID_HOME`.

```sh
just check-all                     # = ./gradlew check; must pass before committing (CI runs the same)
./gradlew :app:installDebug        # build + install on a connected device/emulator
./gradlew :app:detekt --auto-correct  # apply ktlint formatting fixes
```

## Checks

`just check-all` (`./gradlew check`) runs, with every warning treated as an error. GitHub Actions runs it on
every push to `master` and every pull request (`.github/workflows/check.yml`; lint/detekt reports are
uploaded as an artifact when it fails):

- **Kotlin compiler**: `allWarningsAsErrors`, `extraWarnings`, `progressiveMode`.
- **Android Lint**: all checks enabled (`checkAllWarnings`), including test sources and dependencies.
  Deviations, each with a reason: `config/lint/lint.xml`.
- **detekt** with `allRules = true`, ktlint formatting (`ktlint_official` style via `.editorconfig`) and
  [Compose rules](https://mrmans0n.github.io/compose-rules/), both the plain and the type-resolution variants.
  Deviations, each with a reason: `config/detekt/detekt.yml`. HTML reports land in `app/build/reports/detekt/`.
- **Unit tests** for debug and release.

## Layout

```
app/src/main/java/dev/daniel/jigsaw/
  source/   ImageSource + Nextcloud / Immich / URL-list implementations, persisted SourceStore
  puzzle/   JigsawCut (tab geometry), PuzzleBoard (pure game state), PieceRenderer, ImageDecoding
  ui/       Compose screens: gallery (main view), settings, puzzle canvas; AppViewModel
```
