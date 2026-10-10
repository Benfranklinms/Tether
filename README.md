# Tether

**Your Android phone and your Mac, linked directly.** Texts and calls on the Mac, notifications you can answer, media controls, clipboard, files, screenshots, battery, find-my-phone, screen control and lock-when-you-walk-away, over Bluetooth LE with a Wi-Fi fast path. End-to-end encrypted. No server, no account, no cloud.

<p>
  <img src="docs/screenshots/mac/menu.png" width="300" alt="Tether in the Mac menu bar: phone battery, quick actions and mirrored notifications with Reply">
  <img src="docs/screenshots/android/home.png" width="230" alt="Tether on Android">
  <img src="docs/screenshots/mac/messages.png" width="440" alt="Messages on the Mac: the phone's SMS conversations with dual-SIM reply">
</p>

**Download v0.3.1:** [Mac (.dmg)](https://github.com/Benfranklinms/Tether/releases/download/v0.3.1/Tether-0.3.1.dmg) · [Mac (.zip)](https://github.com/Benfranklinms/Tether/releases/download/v0.3.1/Tether-0.3.1.zip) · [Android (.apk)](https://github.com/Benfranklinms/Tether/releases/download/v0.3.1/Tether-0.3.1.apk) · [all releases](https://github.com/Benfranklinms/Tether/releases)

macOS 13+ (Apple silicon and Intel) · Android 8.0+ · MIT licensed

**New in 0.3.1 (fix release, update both devices):** the Bluetooth link no longer drops and reconnects every minute on phones with a 517-byte MTU (Android 13+ refused the 514-byte writes 0.3.0 used, so nothing reached the Mac after connecting); Messages loads reliably (one bounded read of the phone's threads, an answer even when the phone's SMS store fails, Wi-Fi for big lists, a 15 s timeout with **Retry** on the Mac, re-requests after a reconnect); the Mac panel tells you when macOS has Tether's notifications turned off and opens the right settings page.

---

## Contents

[Features](#features) · [Install](#install) · [Pair](#pair) · [Feature setup and permissions](#feature-setup-and-permissions) · [Bluetooth vs Wi-Fi](#bluetooth-vs-wi-fi) · [Security](#security) · [Troubleshooting](#troubleshooting) · [Architecture](#architecture) · [Build from source](#build-from-source) · [Limitations](#limitations) · [Credits](#credits-and-license)

## Features

| | What it does |
|---|---|
| **Messages** *(0.3)* | Your phone's SMS conversations in a Mac window: thread list with unread counts, full conversations (MMS text too), search across all messages, reply or start a new text, choose the SIM on dual-SIM phones, live updates as texts arrive and as yours go from *Sending…* to sent (or *Retry*). Nothing is stored on the Mac. |
| **Calls** *(0.3)* | Incoming calls show on the Mac with the caller's name (from your contacts), **Decline** and **Silence**, in the menu panel and as a notification. Missed calls become a Mac notification. |
| **Now playing** *(0.3)* | Whatever plays on the phone (Spotify, YouTube Music, podcasts…) with artwork, a live progress bar, play/pause, next/previous, seek and phone volume. Optionally the Mac's media keys and Control Center drive it. |
| **Lock when you walk away** *(0.3)* | Off by default. The Mac watches the Bluetooth signal of your phone (smoothed, with hysteresis and a delay you choose) and locks itself when you leave. Never while Screen Sharing or the phone-screen window is open. It never unlocks anything. |
| **Notifications** | Phone notifications appear on the Mac (per-app rules on both sides, quiet hours, muted words, hide contents). Dismiss on one, it clears on the other. |
| **Reply from the Mac** *(0.2)* | Inline reply for any app that offers one in its notification (WhatsApp, Messages, Telegram, Signal, Slack, Gmail…), from the banner or the menu. Other buttons such as *Mark as read* show up too. |
| **Clipboard** | Copy on the Mac, paste on the phone. Phone → Mac from the quick tile, share sheet or app. Password-manager items are skipped. |
| **Files** | Share sheet → Tether, or drag onto the menu. Resumable, SHA-256 verified, up to 4 GB on Wi-Fi. |
| **Screenshots and photos** *(0.2)* | New screenshots (on by default) and camera photos (off by default) go to the Mac automatically, with a thumbnail notification and a draggable recent strip. Photos wait for Wi-Fi. Never the old gallery, never twice. |
| **Phone battery** *(0.2)* | Level, charging and network in the menu (optionally the % in the menu bar), with a Mac alert at 20 %. |
| **Find my phone** *(0.2)* | The phone rings at full alarm volume, even on silent, with a full-screen Stop. Stops after 60 s or from the Mac. |
| **Phone screen** *(0.2)* | See the phone's screen in a resizable Mac window (hardware H.264, adaptive bitrate) and control it: click, long-press, drag, scroll, type, Back/Home/Recents. Needs Wi-Fi. |
| **Movable panel** *(0.2)* | Drag the menu panel's header (or press its detach button) to tear it off into a floating window: move and resize it, keep it on top (pin), and it remembers where it was. Its × docks it back under the menu-bar icon; clicking the icon while it's detached brings it to the front. |
| **Appearance** *(0.2)* | Pure Mono (solid black and greys) or Liquid Glass (real macOS 26 glass, monochrome; translucent vibrancy on macOS 13–15). Follows Reduce Motion and Reduce Transparency. |

## Install

### Mac

1. Download `Tether-0.3.1.dmg` (or the `.zip`), open it and drag **Tether** to **Applications**.
2. Tether is **ad-hoc signed and not notarized** (no paid Apple Developer account), so the first open is blocked:
   - **Right-click** Tether in Applications → **Open** → **Open**.
   - macOS 15 Sequoia and later: open it once, then **System Settings › Privacy & Security** → **Open Anyway**.
   - Or in Terminal: `xattr -dr com.apple.quarantine /Applications/Tether.app`
3. Allow **Bluetooth** when asked (required) and **Notifications** (to show your phone's notifications). On macOS 15+, allow **Local Network** for the Wi-Fi fast path.
4. Tether lives in the menu bar (two rings); no Dock icon. Click it for the panel; drag the panel's header to detach it as a floating window. **Updating** from any earlier version: replace the app; you stay paired.

Liquid Glass needs **macOS 26 Tahoe**. On older macOS the Liquid Glass option uses translucent vibrancy instead.

### Android

1. Download `Tether-0.3.1.apk` on the phone and open it. Allow *Install unknown apps* for your browser or file manager.
2. **Play Protect** may warn about an app from an unknown developer: tap **More details → Install anyway**. Tether has no internet servers; the APK is built in public CI from this repository.
3. Open Tether and follow the setup. Updating from any earlier version installs over it (same signing key) and keeps the pairing.
4. **Allow restricted settings** (Android 13+, sideloaded apps): before Android lets you turn on **Notification access** or **Accessibility**, or grant **SMS** and **Call logs / Phone** permissions to Tether (they appear greyed out or the request is silently refused), go to **Settings › Apps › Tether › ⋮ (top right) › Allow restricted settings**, confirm, then grant them. You only do this once. On Android 15 some phones ask again per permission group.

## Pair

1. On the Mac: menu-bar icon → **Pair a phone**. A QR code appears.
2. On the phone: **Scan code** and point it at the Mac. Done in one tap.
   - No camera? **Pair without camera**: both screens show a 6-digit code; confirm on both if they match.
3. Keep the phone within Bluetooth range. Tether reconnects by itself.

Both pairing screens show a live status line and explain failures. See [Troubleshooting](#troubleshooting).

## Feature setup and permissions

Everything new is on the phone in **Tether › Settings › On your Mac**, and on the Mac in **Settings › Features**. Permission rows appear only while something is missing.

| Feature | Phone permission | Mac permission | Notes |
|---|---|---|---|
| Link (required) | Nearby devices (Bluetooth); Location on Android ≤ 11 | Bluetooth | Allow battery "Unrestricted" on the phone for reliable background links |
| Notifications | Notification access (*restricted setting*) | Notifications | |
| Reply and actions | Notification access | Notifications | Works when the app's notification has an inline reply |
| **Messages** *(0.3)* | **SMS** (`READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, *restricted*), **Phone** (`READ_PHONE_STATE`, for the SIM list), **Contacts** (optional, names) | — | Phone: Settings › On your Mac › *Texts on the Mac* › Allow SMS access. Mac: menu → **Messages**. Tether is not your default SMS app and doesn't need to be |
| **Calls** *(0.3)* | **Phone** (`READ_PHONE_STATE`), **Call logs** (`READ_CALL_LOG`, *restricted*, for the number), **Contacts** (names), **Answer phone calls** (`ANSWER_PHONE_CALLS`, for Decline), **Do Not Disturb access** (for Silence) | Notifications | Without call logs/contacts the caller's name comes from the dialer's notification when possible |
| **Now playing** *(0.3)* | Notification access (already granted for notifications) | — | Mac media keys: Settings › Features › *Mac media keys control the phone* |
| **Proximity lock** *(0.3)* | — (on the phone: *Let the Mac sense this phone*, on by default) | — | Mac: Settings › Features › *Lock this Mac when the phone is away* (off by default); pick distance and delay |
| Screenshots / photos | Photos (`READ_MEDIA_IMAGES`; choose **Allow all**) | — | Saved to `~/Downloads/Tether` (Mac Settings › Features to change) |
| Battery | — (optional *Phone* permission adds 4G/5G) | Notifications for the 20 % alert | |
| Find my phone | Notifications; **Full-screen alerts** recommended (Android 14+) | — | Uses the alarm stream: rings in silent/vibrate; most phones let alarms through Do Not Disturb |
| Screen view | Android asks **every session** (*Start now*) | — | Needs Wi-Fi or hotspot |
| Screen control | **Accessibility › Tether screen control** (*restricted setting*) | — | Without it the Mac can view but not control |
| Files, clipboard | Notifications (progress); Camera only for QR pairing | Local Network (macOS 15+) for Wi-Fi | |

*Restricted* = on a sideloaded APK, Android 13+ blocks it until you **Allow restricted settings** (Install, step 4).

**Messages, step by step:** phone → Tether › Settings › On your Mac → *Texts on the Mac* → **Allow SMS access** (allow restricted settings first if Android refuses) → Mac menu → **Messages**. Pick a conversation, type, Return (or ⌘↩) sends. On a dual-SIM phone the SIM chip next to the field picks the SIM. ✎ starts a new text; the search field searches every message on the phone.

**Calls, step by step:** phone → *Call alerts on the Mac* → **Allow call access** (Phone, Call logs, Contacts, Answer calls) and, for Silence, **Do Not Disturb access** (Tether in the list → Allow). When a call rings, the Mac panel and a notification show the caller with Decline and Silence; the dialer's own call notification is not mirrored twice.

**Lock when you walk away:** Mac → Settings › Features → turn on *Lock this Mac when the phone is away*, choose **Near / Medium / Far** (≈1–2 m, 3–5 m, 8–10 m) and **Away for** (10 s – 2 min). *Signal now* shows the live reading so you can check it at your desk. Bluetooth signal varies a lot with pockets, bodies and walls: start with Medium and 30 s.

**Screen sharing, step by step:** Mac menu → **Phone screen** → on the phone tap the *Your Mac wants to see this screen* notification (or it opens directly if Tether is on screen) → **Start now**. A *Sharing your screen* notification with **Stop** stays while it runs. In the Mac window: click = tap, hold = long-press, drag = swipe, two-finger scroll = scroll, type into the focused field, ⌘V pastes, Return = enter, Esc or right-click = Back.

## Bluetooth vs Wi-Fi

- **Bluetooth LE** is always on: pairing, notifications, replies, texts, calls, now playing, clipboard, battery, ring, proximity and files up to 100 MB (about 30–100 KB/s).
- **Wi-Fi fast path**: when both are on the same network, or the **Mac is on the phone's hotspot**, Tether opens a direct TCP link (still Noise-encrypted, never via the internet). Big files, photos and the **screen stream** use it. If it drops, transfers continue over Bluetooth from the last verified byte.
- Many campus, office and guest networks block device-to-device traffic. If the Wi-Fi chip never appears, turn on the phone's **hotspot** and join it from the Mac.

## Security

- **Noise `XX_25519_ChaChaPoly_SHA256`** on both links (different prologues, so a Bluetooth handshake can't be replayed on Wi-Fi). One X25519 key per install: Android Keystore-wrapped on the phone, a 0600 file on the Mac.
- **Pairing** by QR (Mac key + one-time token, checked in the handshake) or 6-digit code comparison. Wi-Fi sessions only accept the paired key plus a single-use token offered over Bluetooth.
- **Key rotation** from Settings; **unpair** on either side forgets on both.
- **No servers, no account, no analytics.** Logs record events and sizes only: never clipboard text, notification content, file names or screen content.
- Texts, call details and now-playing data are shown on the Mac only while connected and are never written to disk on the Mac or logged on either side. Proximity uses only the signal strength of the existing encrypted link.
- Screen control acts only while you are sharing your screen, only on input from the paired Mac, and the accessibility service does not read or keep screen content.

Wire protocol: [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Troubleshooting

| Symptom | Try |
|---|---|
| Mac says Bluetooth is off / not allowed | Turn Bluetooth on; enable Tether in System Settings › Privacy & Security › Bluetooth; reopen Tether |
| Phone can't find the Mac | Keep the Mac's pairing window open, stay within a few metres; on some phones turn on Location; check *Nearby devices* |
| Pairing stalls at "securing the link" | Close and reopen the Mac pairing window, try again; then export diagnostics on both |
| Can't enable Notification access / Accessibility ("Restricted setting") | Settings › Apps › Tether › ⋮ › **Allow restricted settings**, then enable |
| Reply button missing on the Mac | The phone app's notification has no inline reply (e.g. some bank or system apps), or Reply is off in either app's Features |
| Screenshots don't arrive | Phone: Settings › On your Mac › Send new screenshots on, Photos permission **Allow all**; the phone sends only screenshots taken after you turned it on |
| Photos stay "Waiting for Wi-Fi" | Expected on Bluetooth: join the same Wi-Fi or the phone's hotspot |
| Screen says "needs Wi-Fi" | Same as above; screen video never goes over Bluetooth |
| Screen shows but clicks do nothing | Enable *Tether screen control* in phone Accessibility settings (allow restricted settings first) and *Control with mouse and keyboard* on the Mac |
| Messages spins or the list stays empty | Update **both** apps to 0.3.1 or later. The window now gives up after 15 s with the reason and **Retry**. Still empty: export diagnostics on both and look for `sms.threads` on the phone (it shows `ms`, `count`, `path` and `err`) and `sms.threads_failed` / `msg.decode_failed` on the Mac |
| Mac panel says "Mac notifications are off for Tether" | Click **Open Settings** (System Settings › Notifications › Tether) and allow notifications; the banner goes away when you come back |
| Link keeps reconnecting every minute or two | Update both apps to 0.3.1+. Mac diagnostics showing `link.closed reason=timeout remote=false` again and again mean the phone's Bluetooth writes aren't arriving: look on the phone for `ble.write_failed` / `executor.uncaught` and `ble.link_up` (its `frag` must be ≤ 512) |
| Messages window says "Allow SMS" / SMS permission greyed out | Phone: Settings › Apps › Tether › ⋮ › **Allow restricted settings**, then Tether › Settings › On your Mac › Allow SMS access |
| A text shows "Not sent" | Signal, airplane mode or the chosen SIM; press **Retry**. Short codes may be blocked by your carrier |
| Calls show a number instead of a name | Grant **Contacts** and **Call logs** to Tether on the phone |
| Decline does nothing / "Allow Decline" | Grant **Answer phone calls** (Allow call access row) |
| Silence says it needs Do Not Disturb access | Phone Settings › Notifications › Do Not Disturb access (or Special app access) › Tether › Allow |
| No now-playing card | The app must publish a media session (most players do); notification access must be on; *Media controls on the Mac* on in both apps |
| Mac locks while I'm at my desk | Choose a farther distance or a longer delay; check *Signal now*; phone in a back pocket or behind a laptop lid reads much weaker |
| Mac didn't lock when I left | The phone may still be in range (Bluetooth reaches 10 m+ through walls); choose Near. It also never locks during Screen Sharing or the phone-screen window |
| Phone doesn't ring loudly in Do Not Disturb | Allow alarms in your DND settings; allow Tether full-screen alerts |
| Link drops in the background | Phone: Tether › Settings › Battery optimization → Unrestricted (OnePlus/Oppo: also allow background activity) |
| Anything else | **Export diagnostics**: phone Settings › Export logs; Mac Settings › Diagnostics › Export… (no personal content) |

## Architecture

```
Android (Kotlin, Compose)                         macOS (Swift, SwiftUI)
┌──────────────────────────────┐                  ┌──────────────────────────────┐
│ App: BLE central, NotificationListener,         │ App: CoreBluetooth peripheral,│
│ MediaStore watcher, battery, ringer, SMS        │ UserNotifications, Bonjour,   │
│ provider + SmsManager, call state, MediaSession,│ VideoToolbox viewer, Messages,│
│ MediaProjection + MediaCodec, accessibility     │ MediaPlayer keys, proximity   │
├──────────────────────────────┤                  ├──────────────────────────────┤
│ android/core (pure JVM)      │  same protocol   │ mac/TetherCore (Swift pkg)   │
│ Noise XX · framing · messages│◄───────────────►│ Noise XX · framing · messages │
│ controller · transfers       │  shared vectors  │ controller · transfers       │
└──────────────────────────────┘  + interop test  └──────────────────────────────┘
          Bluetooth LE (GATT, fragments)  ·  Wi-Fi TCP (length-prefixed records)
```

The protocol is implemented twice, independently, and cross-checked: shared JSON test vectors, Noise test vectors, and an **interop harness** that runs the real Kotlin phone core against the real Swift Mac core over a pipe (pairing, every message type of all three revisions in both directions, a Wi-Fi session, media transfers, split H.264 frames, input mapping, old and future peers). The proximity filter (smoothing, hysteresis, dwell, lock policy) has its own unit tests.

```
android/core      Kotlin protocol core + tests (incl. InteropTest)
android/app       Android app (Compose) + Robolectric tests and screenshots
mac/TetherCore    Swift protocol core (Swift package) + tests
mac/Tether        Mac menu-bar app (SwiftUI)
tools/interop     Swift peer for the Kotlin ↔ Swift interop test
protocol/vectors  Shared test vectors
docs/PROTOCOL.md  Wire protocol
```

## Build from source

```sh
# Android (JDK 17)
cd android && ./gradlew detekt :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease

# Mac (Xcode 26 for Liquid Glass; Xcode 15+ builds with the fallback). brew install xcodegen swiftlint
cd mac && swiftlint lint --strict && (cd TetherCore && swift test)
xcodegen generate && open Tether.xcodeproj

# Interop: Kotlin phone core ↔ Swift Mac core
(cd tools/interop && swift build)
cd android && TETHER_SWIFT_PEER=$PWD/../tools/interop/.build/debug/tether-peer ./gradlew :core:test --tests '*InteropTest*'
```

**CI** (`.github/workflows/ci.yml`) runs on every push: detekt, core + Robolectric tests, Android lint, R8 release build and screenshots; the interop job (Ubuntu, Swift + JDK); SwiftLint, TetherCore tests and a universal Release build of the Mac app on macOS (checks Liquid Glass is linked), ad-hoc signed and packaged; demo-mode screenshots. A `v*` tag publishes the release with `Tether-<version>.apk`, `.dmg` and `.zip`.

**Signing.** `android/keystore/tether.jks` (password `tetherdebug`) is committed on purpose so every build installs over the previous one. It protects nothing: if you distribute a fork, use your own key. Try the Mac UI without a phone: `/Applications/Tether.app/Contents/MacOS/Tether --demo`.

## Limitations

- **Tested on real hardware only for 0.1.x** (pairing and notification mirroring on a MacBook Air + OnePlus Nord CE5). The 0.2 and 0.3 features are covered by unit, interop and build tests in CI (and the 0.2 reply/media paths by an emulator test) but have not yet run on a real phone and Mac.
- **Messages** reads and sends SMS through Android's own provider; Tether is not the default SMS app, so it can't mark messages as read on the phone, delete them, or send MMS (pictures/group MMS). MMS text is shown and attachments are labelled ("Photo") but not transferred. RCS / Google Messages chat messages aren't SMS and don't appear (their notifications are still mirrored, with Reply). OEM SMS providers differ; a few phones store messages where other apps can't read them.
- **Calls**: Decline uses `TelecomManager.endCall`, which some OEM dialers ignore; Silence mutes the ring stream (needs Do Not Disturb access) and the phone may still vibrate. Answering from the Mac isn't possible: call audio stays on the phone. Only incoming calls are shown.
- **Now playing** follows the phone's active media session; apps without one (some browsers, games) don't appear. Volume changes the phone's media volume (or a cast device's).
- **Proximity lock** relies on Bluetooth RSSI, which is noisy and differs between phones: treat the distances as rough. It locks via the system's lock-screen call (falling back to display sleep, which locks only if your Mac asks for the password immediately after sleep). It never unlocks.
- **Replies** work only where the phone app's notification offers an inline reply (RemoteInput). Some apps hide it on the lock screen or for certain chats; SMS apps vary. Tether can't start new conversations.
- **Screen sharing** needs Wi-Fi/hotspot. Android requires consent every session and shows its own capture indicator. Apps that set `FLAG_SECURE` (banking, some video apps, password fields) appear black. Latency target is under ~150 ms on a good network; busy Wi-Fi lowers quality first. Long-press and drags are sent when you release the mouse, not live. Typing uses accessibility text replacement: it works in normal text fields, not in games or custom keyboards' own fields.
- **Find my phone** can't override a phone with alarm volume locked by the OEM or a DND mode that blocks alarms; full-screen alerts may need permission on Android 14+.
- **Auto media** sends screenshots/camera photos taken after you enable it; edited copies are sent again only if their bytes differ. Android's "limited photo access" hides new photos from Tether; choose *Allow all*.
- **Background**: aggressive OEM battery management can still stop the link; set Tether's battery use to Unrestricted.
- **One phone, one Mac.** Not notarized; the APK key is public (see Signing). Android 10+ blocks background clipboard reads, so phone → Mac clipboard is manual (tile, share sheet or app).

## Credits and license

Tether is released under the [MIT License](LICENSE).

- The Bluetooth fragment framing and the Noise XX structure are adapted from [bitchat](https://github.com/permissionlesstech/bitchat) (Swift, released into the public domain under the Unlicense). Thank you to its authors. The Android implementation was written independently against the Noise specification and test vectors; no bitchat-android (GPL-3.0) code is used.
- [Manrope](https://github.com/sharanda/manrope) typeface, SIL Open Font License 1.1.
- Bouncy Castle (MIT-style), ZXing and zxing-android-embedded (Apache 2.0), AndroidX / Jetpack Compose (Apache 2.0).
