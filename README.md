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

Adding a new backend: implement `ImageSource`, add a `SourceConfig` variant (`@Serializable` with a new
`@SerialName`, its stored `type`), and wire it in `toImageSource()` and the add-source dialog.

## Gameplay

- The main view shows every image from all configured sources on one timeline, newest first (Immich: capture
  time; Nextcloud: capture time where the server provides it — Nextcloud 28+ — else modification time; URL lists
  have no dates and go last). A source that fails to load is reported above the grid without hiding the others.
- Updating the gallery:
  - **Never older than a minute while visible:** while the gallery is on screen and the app is in the
    foreground, the sources are listed again whenever the list reaches one minute old. Coming back to it
    (cold start, from Settings, from a puzzle, from the background) lists them at once if the list is
    older than that, otherwise when it gets there. While the gallery isn't visible, nothing is listed.
  - **Adding a source** makes the list due at once.
  - **Progress:** ⟳ and pull-to-refresh show it; the automatic refreshes run silently.
  - **During a refresh** the current images stay on screen.
  - **Last known list:** each source's last listing is saved (`files/gallery/listings.json`). The gallery
    appears at once on start, and a source that can't be reached keeps showing its last known pictures,
    with the reason in the card above the grid.
- Downloaded pictures are kept in a 250 MB disk cache (`cache/http`), so thumbnails aren't downloaded again
  on every start and opening a puzzle doesn't download its picture again.
  - **Revisions:** Immich pictures are versioned by their thumbhash, Nextcloud files by their ETag. A
    picture is reused until it changes on the server, whatever caching headers the server sends.
  - **URL lists** follow the image server's own caching headers.
  - **Offline:** cached pictures are used, so started puzzles stay playable. Listings are always fetched
    from the server.
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
- Languages: English and German; the app is called "Jigsaw" in English and "Puzzle" in German. Settings →
  Language offers System default, English or Deutsch. On Android 13+ this is the same per-app language as
  system Settings → Apps → Jigsaw/Puzzle → Language. Older versions store it in the app. Error messages are
  translated too.
- Runs on Android 8.0 (API 26) and newer. Smoke-tested on Android 9, 13 and 15 (emulators) and on a Pixel 4a
  running Android 15.

## Build

Requires JDK 21 and an Android SDK with platform 37 (compileSdk 37) referenced by `local.properties` (`sdk.dir=…`) or `ANDROID_HOME`.

```sh
just check-all                     # = ./gradlew check; must pass before committing (CI runs the same)
./gradlew :app:installDebug        # build + install on a connected device/emulator
./gradlew :app:detekt --auto-correct  # apply ktlint formatting fixes
```

## Release APK

`just release-apk` builds `app/build/outputs/apk/release/app-release.apk` (about 1.6 MB). That build uses R8
full mode to shrink, optimise and obfuscate the code, removes unused resources, keeps only English and German
strings, and leaves out build metadata and the
dependency-info block. The APK is signed only when these are set: `RELEASE_KEYSTORE` (path),
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`.

CI (`.github/workflows/release.yml`) builds it on every push and PR, signing it with the repository secrets
`RELEASE_KEYSTORE_BASE64` (keystore, base64), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASSWORD`. The APK and its R8 `mapping.txt` (needed to decode crash stack traces) are uploaded as
a workflow artifact. Release and debug builds are signed with different keys: to switch between them,
uninstall first, which deletes the saved puzzles.

### Releasing

1. In `app/build.gradle.kts`, raise `versionCode` by one and set `versionName`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and the same for `de-DE`.
3. Push to `master`.

When the pushed `versionName` has no `v<versionName>` tag yet, CI publishes the signed APK and its
`mapping.txt` as a GitHub Release with the English changelog as notes. Creating that release also creates
the tag on the commit. CI refuses to release if the `versionCode` doesn't grow, the changelog is missing, or
the signing key is unavailable.

### Reproducible builds and F-Droid

Release APKs are reproducible: building the same commit with JDK 21 gives byte-identical contents, so
F-Droid can verify its own build against the GitHub release and ship it with our signature. For that, the
release build leaves out VCS info (`vcsInfo.include = false`). `fdroid/dev.daniel.jigsaw.yml` is the
metadata for [fdroiddata](https://gitlab.com/fdroid/fdroiddata). It points F-Droid at the release APKs and
pins the signing certificate. Store texts, icon, feature graphic and screenshots are in `fastlane/`. The icon
and feature graphics are generated by `python3 tools/launcher_icon.py --store`.
The screenshots show public-domain and CC0 photos from Wikimedia Commons ("Going am Wilden Kaiser Panorama",
"Lake Sherburne", "Komargorod pond 2013 G3", Heinrich Berann's NPS panorama of North Cascades, "Alone in the
unspoilt wilderness", "Lindenstumpf, Panorama"), some cropped to 3:2.

To check a release APK yourself, build the same tag without signing secrets and run
`apksigcopier compare jigsaw-X.Y.Z.apk --unsigned app/build/outputs/apk/release/app-release-unsigned.apk`.

## Checks

`just check-all` (`./gradlew check`) runs, with every warning treated as an error. GitHub Actions runs it on
every push to `master` and every pull request (`.github/workflows/check.yml`; lint/detekt reports are
uploaded as an artifact when it fails):

- **Kotlin compiler**: `allWarningsAsErrors`, `extraWarnings`, `progressiveMode`.
- **Android Lint**: all checks enabled (`checkAllWarnings`), including test sources and dependencies.
  Deviations, each with a reason: `config/lint/lint.xml`.
- **detekt 2** (pre-release, pinned) with its default-active rules, ktlint rules (detekt-rules-ktlint-wrapper,
  `intellij_idea` code style; it reads its style from detekt config, not `.editorconfig`, which mirrors it for
  standalone ktlint and IDEs) and
  [Compose rules](https://mrmans0n.github.io/compose-rules/), both the plain and the type-resolution variants.
  Deviations, each with a reason: `config/detekt/detekt.yml`. HTML reports land in `app/build/reports/detekt/`.
- **Unit tests** for debug and release.

## Layout

```
app/src/main/java/dev/daniel/jigsaw/
  source/   ImageSource + Nextcloud / Immich / URL-list implementations; Http (disk cache, revisions,
            offline fallback); persisted SourceStore and ImageListStore (last listings)
  puzzle/   JigsawCut (tab geometry), PuzzleBoard (pure game state), PieceRenderer, ImageDecoding
  ui/       Compose screens: gallery (main view), settings, puzzle canvas; AppViewModel; GalleryFeed
            (listing, refresh timing, saved lists); UiText (localised errors)
app/src/main/res/values/strings.xml      English text (default)
app/src/main/res/values-de/strings.xml   German text; Lint fails if a translation is missing or extra
app/src/main/res/drawable/ic_launcher_*  launcher icon layers, generated by tools/launcher_icon.py
                                         (python3 tools/launcher_icon.py [--preview DIR])
app/src/main/res/drawable/ic_*           other icons: Material Symbols (Outlined, fill 1, 24 dp) from the Android
                                         tab of fonts.google.com/icons, without their android:tint (Icon() tints)
```

## License

Copyright (C) 2026 Daniel Freiermuth

This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero
General Public License as published by the Free Software Foundation, either version 3 of the License, or (at
your option) any later version. See [LICENSE](LICENSE).
