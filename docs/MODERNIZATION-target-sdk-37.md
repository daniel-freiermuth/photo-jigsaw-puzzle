# RFC: raise targetSdk 36 → 37 (Android 17)

Status: proposal, needs a decision. Nothing in the build changes with this document.

## Current state (re-verified 2026-10-15)

- `app/build.gradle.kts`: `compileSdk = 37` (raised in 5320240 because OkHttp 5.5.0's `okhttp-android` AAR
  declares `minCompileSdk=37`), `targetSdk = 36`, `minSdk = 26`.
- `config/lint/lint.xml` ignores `OldTargetApi`, with a comment saying a targetSdk bump needs its own tested
  change. Removing the ignore makes `./gradlew :app:lintDebug` fail on AGP 9.4.1:
  `app/build.gradle.kts:18: Error: Not targeting the latest versions of Android; compatibility modes apply.`
- Android 17 / API 37 is a stable release: the installed `platforms/android-37.0/source.properties` says
  `Platform.Version=17`, `AndroidVersion.PreviewSdkInt=0`.
- The app ships through GitHub Releases and F-Droid (`fdroid/dev.daniel.jigsaw.yml`, README "Reproducible
  builds and F-Droid"), not Google Play. Play's target-API floor (API 36 for new apps and updates since
  31 August 2026) therefore does not apply, and targetSdk 36 would meet it anyway. F-Droid sets no target-API
  floor. **No external deadline forces this bump.**
- The manifest declares only `INTERNET` and sets `android:usesCleartextTraffic="true"`: plain-HTTP servers
  on the home network (Nextcloud, Immich, a URL list on a NAS) are a supported setup.

## What changes at targetSdk 37, applied to this app

Source: <https://developer.android.com/about/versions/17/behavior-changes-17> (updated 2026-10-09).

| Change for apps targeting 37 | Affects this app? |
|---|---|
| **Local network access needs the `ACCESS_LOCAL_NETWORK` runtime permission** | **Yes — breaks LAN-hosted sources.** See below. |
| Certificate Transparency on by default | Unlikely. No network security config, so only system CAs are trusted already; public CAs log to CT. A server with a private CA never worked. |
| Encrypted Client Hello (ECH) on by default | No action: only active when the TLS stack (OkHttp here) and the server support it; otherwise GREASE. |
| Orientation/resizability/aspect-ratio limits ignored on sw ≥ 600dp, no opt-out | No: the manifest sets no `screenOrientation`, `resizeableActivity` or aspect ratios. Tablet layout is worth a look but is not a regression. |
| Hide password characters when typing on a physical keyboard | Cosmetic, Nextcloud share password field (`SettingsScreen.kt`). |
| Lock-free `MessageQueue`; `static final` fields unmodifiable by reflection | No: no reflection on either (grep for `setAccessible`, `declaredField`, `MessageQueue` finds nothing). |
| `setContentCaptureEnabled(false)` ignored | Not used. |
| Native DCL read-only, RemoteViews memory limit, background audio, CP2 contacts, SMS OTP delay, Bluetooth RFCOMM `read()` | Not used. |

### Local network permission: verified on an API 37 emulator

Docs: <https://developer.android.com/privacy-and-security/local-network-permission>. Apps targeting < 37
get `ACCESS_LOCAL_NETWORK` implicitly with `INTERNET`; at 37 it is blocked by default for **all**
networking APIs, OkHttp included, and a blocked TCP connection "will typically result in a timeout error".

Experiment (API 37.0 `google_apis` x86_64 emulator, debug APKs of this commit, a URL-list source served by
`python3 -m http.server` on the host; `10.0.2.2` is the host on the emulator's own subnet `10.0.2.0/24`):

| APK | Source URL | Result in the gallery |
|---|---|---|
| targetSdk 36 | `http://10.0.2.2:8765/list2.txt` | image listed and loaded |
| targetSdk 37 | `http://10.0.2.2:8765/list2.txt` | after ~15 s (`CONNECT_TIMEOUT_SECONDS`): "Couldn't list images: 10.0.2.2: The server took too long to answer." |
| targetSdk 37 + `<uses-permission ACCESS_LOCAL_NETWORK>`, not granted | same | same timeout error |
| same APK after `adb shell pm grant dev.daniel.jigsaw android.permission.ACCESS_LOCAL_NETWORK`, tap Retry | same | image listed and loaded, no restart needed; opening the puzzle works |
| targetSdk 37 | `http://192.168.178.38:8765/list.txt` (host's Wi-Fi address, reached via the emulator's gateway) | image listed and loaded |

`dumpsys package` shows the difference: at 36, `ACCESS_LOCAL_NETWORK: granted=true` (implicit); at 37
without the declaration, no entry at all.

What this means for users:

- A Nextcloud/Immich server on the **same Wi-Fi subnet** stops working the moment targetSdk is 37, and
  the user sees a misleading "server took too long" after a 15 s wait. The same server keeps working over
  mobile data or via a public address routed through a gateway — so it looks like a flaky home network.
- The last row suggests "local" means on-link, not every RFC 1918 address; split-horizon DNS (a public
  hostname that resolves to a LAN address at home) would therefore also be blocked at home. This is an
  emulator observation; confirm on a real device on Wi-Fi.
- `./gradlew check :app:assembleRelease` **passes** at targetSdk 37 without the permission, and
  `:app:lintDebug` passes with it declared: nothing in the build catches this.

## Options

1. **Stay at 36, keep the `OldTargetApi` ignore (status quo).** Zero risk, zero work. LAN servers keep
   working through the implicit grant. Revisit if the app goes to Google Play (Play's next floor will be
   37, following the yearly pattern — [INFERENCE], not yet announced) or when an Android release stops
   installing target-36 apps (not on any announced schedule).
2. **Bump to 37 and declare + request `ACCESS_LOCAL_NETWORK` reactively.** When a source fails and its
   host resolved to a local address while the permission is missing, show a dedicated message
   ("Allow access to devices on your local network") with a button that launches the system permission
   request, then refresh. Users with internet-hosted servers never see a prompt.
3. **Bump to 37 and request `ACCESS_LOCAL_NETWORK` up front** (first start, or whenever a source is added).
   Simplest code, but prompts every user for a "Nearby devices" permission most of them do not need, and
   cannot know at add time whether the server is local (that depends on the network the phone is on).
4. **System pickers (`NsdManager` `FLAG_SHOW_PICKER`)** — not applicable: sources are URLs typed by the
   user, not mDNS-discovered devices.

## Recommendation

Option 1 now; option 2 when there is a reason to move (Play listing, a platform install floor, or a
platform feature that needs 37). Option 2 is a feature with its own UX, not a version bump, and nothing
currently pays for its risk. Keep the `OldTargetApi` ignore either way: it fires again with every AGP that
learns a new API level, and the existing comment in `config/lint/lint.xml` already states the policy.

## Migration steps for option 2 (in order, one commit each)

1. Manifest: `<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />`. Harmless below
   API 37 (unknown permissions are ignored) and, per the docs, must not be *requested* before targeting 37.
2. Detect "blocked by local network permission" early instead of after the 15 s connect timeout: wrap
   `Http`'s OkHttp client with a `Dns` (or connection) check that, on API 37+ with the permission missing,
   throws a dedicated `LocalNetworkPermissionException` when a resolved address is on a subnet of the active
   network (`ConnectivityManager.getLinkProperties(activeNetwork)`). Decide the address rule here (see
   open questions). Unit-test the classification (IPv4/IPv6, link-local, ULA, off-link private, public).
3. `UiText.kt`: map the new exception to a new string; add it to `values/` and `values-de/`.
4. UI: on that error, show an "Allow" action in the gallery error card / source error list that calls
   `rememberLauncherForActivityResult(RequestPermission())` with `ACCESS_LOCAL_NETWORK`, then
   `vm.feed.refresh()`. Handle permanent denial with a link to the app's system settings.
5. Raise `targetSdk = 37`; update README ("Runs on…", smoke-test list).
6. Test: `./gradlew check :app:assembleRelease`; API 37 emulator with a source on `10.0.2.2` (deny, allow,
   revoke in Settings while running); API 26 and API 36 emulators to confirm nothing changes there; a real
   device on home Wi-Fi with a LAN Nextcloud/Immich server, including split-horizon DNS if available.

Effort: about 1–2 days including device testing. Rollback: revert the `targetSdk` line; the permission
declaration and request UI are inert below 37.

## Open questions for a human

- Is a Google Play listing planned? That is the only concrete forcing function for 37.
- Is an occasional "Nearby devices" permission prompt acceptable product-wise, and with what wording?
- Which addresses count as local for the early check: on-link only (matches the emulator observation),
  or all private ranges (simpler, but false positives for routed private networks)? How do VPNs such as
  Tailscale (100.64.0.0/10 over a tun interface) behave? Needs a real-device test.

## Testing note: API 37 emulator

`cmdline-tools` 19.0's `avdmanager` writes `target=android-0` into the AVD's `.ini` for
`android-37.0` images; such an AVD crash-loops surfaceflinger
(`Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma`) and restarts system_server every few
seconds. Set `target=android-37.0` in `~/.android/avd/<name>.ini` (or use cmdline-tools ≥ 22) before
booting.
