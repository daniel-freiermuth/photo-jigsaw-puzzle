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

- The main view shows every image from all configured sources; a source that fails to load is reported
  above the grid without hiding the others.
- **Settings** (gear icon) holds the image sources (add/remove) and puzzle preferences.
- Tap an image → ~24 pieces with classic tab/blank cuts.
- Drag pieces; touching uses the real piece outline (tabs included).
- Drop a piece next to its true neighbour and they join and move as a group.
- The puzzle is solved once every piece is joined into one group; the board outline is just a guide.
- Groups can hang partly off-screen (half a cell always stays grabbable), so big halves can meet edge to edge.
- Optional **Snap pieces onto board** (Settings, off by default): pieces dropped near their spot lock in place.
- **Hint** shows a faint copy of the picture on the board.

## Build

Requires JDK 17 and an Android SDK (compileSdk 36) referenced by `local.properties` (`sdk.dir=…`) or `ANDROID_HOME`.

```sh
./gradlew :app:testDebugUnitTest   # engine, geometry, and source-parsing tests
./gradlew :app:installDebug        # build + install on a connected device/emulator
```

## Layout

```
app/src/main/java/dev/daniel/jigsaw/
  source/   ImageSource + Nextcloud / Immich / URL-list implementations, persisted SourceStore
  puzzle/   JigsawCut (tab geometry), PuzzleBoard (pure game state), PieceRenderer, ImageDecoding
  ui/       Compose screens: gallery (main view), settings, puzzle canvas; AppViewModel
```
