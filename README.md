# Tether

**Your Android phone and your Mac, linked directly.** A real Mac app for your phone: messages, calls, notifications you can answer, your phone's files and photos, one-time codes on the clipboard, *Continue on Mac* for what you were just doing, typing into the phone, clipboard history with images, media controls, battery, find-my-phone, screen control and lock-when-you-walk-away. Over Bluetooth LE with a Wi-Fi fast path. End-to-end encrypted. No server, no account, no cloud.

<p>
  <img src="docs/screenshots/mac/main-home.png" width="620" alt="The Tether window on the Mac: sidebar with Home, Messages, Calls, Notifications, Files, Photos, Phone screen, Clipboard and Settings; Home shows a one-time code, Continue on Mac cards and quick actions">
  <img src="docs/screenshots/mac/slim-menu.png" width="260" alt="The slim menu-bar popover: phone and battery, now playing, latest notifications, Send clipboard and Send file">
</p>
<p>
  <img src="docs/screenshots/mac/main-files-light.png" width="440" alt="Browsing the phone's Downloads from the Mac in Light mode">
  <img src="docs/screenshots/mac/main-photos.png" width="440" alt="The phone's camera roll on the Mac">
  <img src="docs/screenshots/android/home.png" width="200" alt="Tether on Android">
</p>

**Download v0.5.0:** [Mac (.dmg)](https://github.com/Benfranklinms/Tether/releases/download/v0.5.0/Tether-0.5.0.dmg) · [Mac (.zip)](https://github.com/Benfranklinms/Tether/releases/download/v0.5.0/Tether-0.5.0.zip) · [Android (.apk)](https://github.com/Benfranklinms/Tether/releases/download/v0.5.0/Tether-0.5.0.apk) · [all releases](https://github.com/Benfranklinms/Tether/releases)

macOS 13+ (Apple silicon and Intel; widgets need macOS 14) · Android 8.0+ · MIT licensed

### New in 0.5.0 (update both devices)

- **A real Mac app.** Tether now opens as a normal window with a Dock icon and a sidebar: **Home, Messages, Calls, Notifications, Files, Photos, Phone screen, Clipboard, Settings**. It remembers its size, position and page. **⌘F** searches messages, calls and notifications at once. The menu-bar icon keeps a **slim popover** (phone and battery, now playing, the latest notifications, *Send clipboard*, *Send file*, *Open Tether*). Choose Dock, menu bar or both, and open at login (Settings › Appearance / Mac).
- **Dark, Light or System**, in Pure Mono or Liquid Glass. Pure black, greys and white, now also as near-white, greys and near-black. Numbers and codes in JetBrains Mono.
- **Your phone's files and photos on the Mac.** Browse Downloads, Documents, WhatsApp media, DCIM and Pictures with thumbnails, preview, download, open, or **drag straight into Finder**. A Photos page shows the camera roll, newest first.
- **One-time codes go to the Mac clipboard** by themselves (from texts and notifications), with a toast. Paste and you're in.
- **Continue on Mac.** A new photo, a finished download, a link you copied, something you shared to Tether, the song you're playing or (optional) your browser tab shows up as a card on the Mac with one click to open it there.
- **Open on phone**: send any link or text from the Mac (Services menu › *Open on Phone*, the Share menu, or `tether://send`). **Type on your phone** from the Mac keyboard (Keyboard window).
- **Clipboard history** (last 20, both directions) and **images both ways**.
- **Focus ↔ Do Not Disturb**, **pause and lower Mac audio during calls** (restored afterwards), **fully-charged** and low-battery alerts, global shortcuts **⌥⌘V** (send clipboard), **⌥⌘F** (send a file), **⌥⌘T** (open Tether), drop files on the **Dock icon**, a **Finder Share extension**, and **widgets** (phone battery, now playing).
- **Proximity unlock** (opt-in, off by default): with your password in the Keychain behind Touch ID, the Mac can type it into the lock screen when your paired phone is near. Read the warnings in Settings › Mac first.
- **Setup checklists** on both devices with a Fix button per row, and **in-app updates** on both (see [Updates](#updates)).

---

## Contents

[Features](#features) · [Install](#install) · [Updates](#updates) · [Pair](#pair) · [Feature setup and permissions](#feature-setup-and-permissions) · [Bluetooth vs Wi-Fi](#bluetooth-vs-wi-fi) · [Security](#security) · [Troubleshooting](#troubleshooting) · [Architecture](#architecture) · [Build from source](#build-from-source) · [Limitations](#limitations) · [Credits](#credits-and-license)

## Features

| | What it does |
|---|---|
| **Mac window** *(0.5)* | Sidebar app with Home, Messages, Calls, Notifications, Files, Photos, Phone screen, Clipboard and Settings; ⌘F universal search; remembered window; slim menu-bar popover; Dock and/or menu bar; Dark / Light / System. |
| **Phone files and photos** *(0.5)* | Browse the phone's Downloads, Documents, WhatsApp media, DCIM and Pictures and the camera roll with thumbnails; preview, download, open, or drag into Finder. Needs *All files access* / *Photos* on the phone. |
| **One-time codes** *(0.5)* | Codes from texts and notifications are copied to the Mac clipboard automatically (toggle in both apps). |
| **Continue on Mac** *(0.5)* | Cards for a new photo or download, a copied or shared link, the song playing, or the current browser tab (optional, accessibility), with one click to continue on the Mac. |
| **Open on phone, typing** *(0.5)* | Send a link or text from any Mac app (Services, Share menu) to open on the phone; type into the phone's focused field from the Mac keyboard. |
| **Clipboard history and images** *(0.5)* | The last 20 copies from both devices, text and images; click to copy again. |
| **Mac extras** *(0.5)* | Global shortcuts ⌥⌘V / ⌥⌘F / ⌥⌘T, Dock-icon drop, Finder Share extension, widgets (battery, now playing), Focus ↔ DND sync, pause/duck Mac audio during calls, fully-charged alert, opt-in proximity unlock. |
| **Updates** *(0.5)* | In-app updates on both devices from the private GitHub releases (one read-only token per device), checked against SHA-256, package, version and signing key. |
| **Messages** *(0.3)* | Your phone's SMS conversations in a Mac window: thread list with unread counts, full conversations (MMS text too), search across all messages, reply or start a new text, choose the SIM on dual-SIM phones, live updates as texts arrive and as yours go from *Sending…* to sent (or *Retry*). Nothing is stored on the Mac. |
| **Calls** *(0.4)* | **Make calls from the Mac**: type a number or search your phone's contacts in the panel's **Calls** tab (dial pad included), call back from recent calls, press **Call** in a Messages conversation, or click any phone number link on the Mac. Pick the SIM on dual-SIM phones. Incoming calls show the caller with **Answer** (the phone's speaker turns on, optional), **Decline** and **Silence**, in the panel and as a notification. While a call is up the panel shows who, a timer, **End**, **Speaker** and **Mute** (only the controls your phone allows). Recent calls with a missed-call badge. The phone does the call and keeps the audio. |
| **One-time codes on Android 15+** *(0.4)* | Android 15 hides codes in notifications from apps like Tether ("Sensitive notification content hidden"). Tether detects it and shows a one-time guide on the phone and the Mac (a single adb command), then confirms when codes come through. |
| **Now playing** *(0.3)* | Whatever plays on the phone (Spotify, YouTube Music, podcasts…) with artwork, a live progress bar, play/pause, next/previous, seek and phone volume. Optionally the Mac's media keys and Control Center drive it. |
| **Lock when you walk away** *(0.3)* | Off by default. The Mac watches the Bluetooth signal of your phone (smoothed, with hysteresis and a delay you choose) and locks itself when you leave. Never while Screen Sharing or the phone-screen window is open. It never unlocks anything. |
| **Notifications** | Phone notifications appear on the Mac (per-app rules on both sides, quiet hours, muted words, hide contents). Dismiss on one, it clears on the other. |
| **Reply from the Mac** *(0.2)* | Inline reply for any app that offers one in its notification (WhatsApp, Messages, Telegram, Signal, Slack, Gmail…), from the banner or the menu. Other buttons such as *Mark as read* show up too. |
| **Clipboard** | Copy on the Mac, paste on the phone. Phone → Mac: **Send to Mac** in the text-selection menu *(0.4.3)*, the quick tile, the share sheet, opening the app, or **auto mode** *(0.4.3, one-time adb setup)* that sends every copy by itself. Password-manager items are skipped. |
| **Files** | Share sheet → Tether, or drag onto the menu. Resumable, SHA-256 verified, up to 4 GB on Wi-Fi. |
| **Screenshots and photos** *(0.2)* | New screenshots (on by default) and camera photos (off by default) go to the Mac automatically, with a thumbnail notification and a draggable recent strip. Photos wait for Wi-Fi. Never the old gallery, never twice. |
| **Phone battery** *(0.2)* | Level, charging and network in the menu (optionally the % in the menu bar), with a Mac alert at 20 %. |
| **Find my phone** *(0.2)* | The phone rings at full alarm volume, even on silent, with a full-screen Stop. Stops after 60 s or from the Mac. |
| **Phone screen** *(0.2)* | See the phone's screen in a resizable Mac window (hardware H.264, adaptive bitrate) and control it: click, long-press, drag, scroll, type, Back/Home/Recents. Needs Wi-Fi. |
| **Movable popover** *(0.2, slim in 0.5)* | Drag the menu-bar popover's header (or press its pin) to tear it off into a floating window that remembers where it was; × docks it back. |
| **Appearance** *(0.2)* | Pure Mono (solid black and greys) or Liquid Glass (real macOS 26 glass, monochrome; translucent vibrancy on macOS 13–15). Follows Reduce Motion and Reduce Transparency. |

## Install

### Mac

1. Download `Tether-0.5.0.dmg` (or the `.zip`), open it and drag **Tether** to **Applications**.
2. Tether is **ad-hoc signed and not notarized** (no paid Apple Developer account), so the first open is blocked:
   - **Right-click** Tether in Applications → **Open** → **Open**.
   - macOS 15 Sequoia and later: open it once, then **System Settings › Privacy & Security** → **Open Anyway**.
   - Or in Terminal: `xattr -dr com.apple.quarantine /Applications/Tether.app`
3. Allow **Bluetooth** when asked (required) and **Notifications** (to show your phone's notifications). On macOS 15+, allow **Local Network** for the Wi-Fi fast path.
4. Tether opens as a window with a Dock icon, plus a menu-bar icon (two rings) with a slim popover; Settings › Appearance › *Show in* picks Dock, menu bar or both. Drag the popover's header to detach it. **Updating** from any earlier version: replace the app (or use Settings › Updates); you stay paired. 0.5 adds two app extensions (widgets, Share); macOS may ask once to allow Tether to access data from its widgets.

Liquid Glass needs **macOS 26 Tahoe**. On older macOS the Liquid Glass option uses translucent vibrancy instead.

### Android

1. Download `Tether-0.5.0.apk` on the phone and open it. Allow *Install unknown apps* for your browser or file manager.
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
| **Call alerts** *(0.3, Answer 0.4)* | **Phone** (`READ_PHONE_STATE`), **Call logs** (`READ_CALL_LOG`, *restricted*, for the number), **Contacts** (names), **Answer phone calls** (`ANSWER_PHONE_CALLS`, for Answer and Decline), **Do Not Disturb access** (for Silence) | Notifications | Without call logs/contacts the caller's name comes from the dialer's notification when possible |
| **Calling from the Mac** *(0.4)* | **Phone › Make and manage calls** (`CALL_PHONE`), **Phone** (`READ_PHONE_STATE`, to see the call start and for the SIM list), **Answer phone calls** (End), **Contacts** (search); Speaker/Mute need nothing extra (`MODIFY_AUDIO_SETTINGS` is automatic) | Optional: Tether as the app for phone links (Settings › Features › *Phone number links* › **Use Tether**) | Phone: Settings › On your Mac › *Make calls from the Mac* › Allow calling. Audio stays on the phone/earbuds |
| **Recent calls** *(0.4)* | **Call logs** (`READ_CALL_LOG`, *restricted*), Contacts (optional) | — | Phone: *Recent calls on the Mac* |
| **One-time codes** *(Android 15+, 0.4)* | `RECEIVE_SENSITIVE_NOTIFICATIONS` app op, granted once over adb (below) | — | Not grantable from any settings screen |
| **Now playing** *(0.3)* | Notification access (already granted for notifications) | — | Mac media keys: Settings › Features › *Mac media keys control the phone* |
| **Proximity lock** *(0.3)* | — (on the phone: *Let the Mac sense this phone*, on by default) | — | Mac: Settings › Features › *Lock this Mac when the phone is away* (off by default); pick distance and delay |
| Screenshots / photos | Photos (`READ_MEDIA_IMAGES`; choose **Allow all**) | — | Saved to `~/Downloads/Tether` (Mac Settings › Features to change) |
| Battery | — (optional *Phone* permission adds 4G/5G) | Notifications for the 20 % alert | |
| Find my phone | Notifications; **Full-screen alerts** recommended (Android 14+) | — | Uses the alarm stream: rings in silent/vibrate; most phones let alarms through Do Not Disturb |
| Screen view | Android asks **every session** (*Start now*) | — | Needs Wi-Fi or hotspot |
| Screen control | **Accessibility › Tether screen control** (*restricted setting*) | — | Without it the Mac can view but not control |
| Files, clipboard | Notifications (progress); Camera only for QR pairing | Local Network (macOS 15+) for Wi-Fi | |
| **Clipboard auto mode** *(0.4.3, optional)* | `READ_LOGS` (granted once over adb), **Display over other apps** (`SYSTEM_ALERT_WINDOW`, adb or the settings button); Android 13+ also asks *Allow Tether to access all device logs?* each time Tether starts | — | Phone: Settings › On your Mac › Clipboard. *Send to Mac* in the selection menu needs nothing |

| **Files browser** *(0.5)* | **All files access** (Settings › Apps › Special access › All files access) | — | Phone: Settings › Continue, files & updates › *Browse files* |
| **Photos browser** *(0.5)* | **Photos and videos** (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`; *Allow all*) | — | |
| **One-time codes** *(0.5)* | SMS and/or Notification access (already used above) | — | Mac Settings › Mac › *Copy one-time codes automatically* |
| **Continue on Mac** *(0.5)* | Photos; All files access (downloads); Notification access (song); **Accessibility** for the browser tab (optional) | Notifications (optional banner) | |
| **Open on phone** *(0.5)* | **Display over other apps** (opens directly; otherwise a tap-to-open notification) | — | Mac: Services › *Open on Phone* (enable in System Settings › Keyboard › Shortcuts › Services if hidden) |
| **Typing from the Mac** *(0.5)* | **Accessibility › Tether screen control** | — | Mac: Phone screen › Keyboard, or the Dock menu |
| **Focus ↔ DND** *(0.5)* | **Do Not Disturb access** | Shortcuts named *Tether Focus On/Off* (phone → Mac); a Focus automation opening `tether://dnd?on=1` / `?on=0` (Mac → phone) | macOS has no public Focus API, so Shortcuts does the switching |
| **Pause/duck Mac audio on calls** *(0.5)* | Phone permission (already used for call alerts) | — | Volume is restored unless you changed it meanwhile |
| **Proximity unlock** *(0.5, off by default)* | — | **Accessibility**; Mac password stored in the Keychain after Touch ID / password confirmation | Only types when the Mac is locked, the phone is near and the encrypted link is up; at most twice per 5 minutes |
| **Widgets / Share** *(0.5)* | — | Add widgets from the desktop's *Edit Widgets*; Share › Tether in Finder | |
| **Updates** *(0.5)* | **Install unknown apps** for Tether; a GitHub token | A GitHub token | See [Updates](#updates) |

*Restricted* = on a sideloaded APK, Android 13+ blocks it until you **Allow restricted settings** (Install, step 4).

**Messages, step by step:** phone → Tether › Settings › On your Mac → *Texts on the Mac* → **Allow SMS access** (allow restricted settings first if Android refuses) → Mac menu → **Messages**. Pick a conversation, type, Return (or ⌘↩) sends. On a dual-SIM phone the SIM chip next to the field picks the SIM. ✎ starts a new text; the search field searches every message on the phone.

**Calls, step by step:** phone → Tether › Settings › On your Mac → *Call alerts on the Mac* and *Make calls from the Mac* → **Allow call access** / **Allow calling** (Phone, Call logs, Contacts, Answer calls, Make calls; allow restricted settings first if Android refuses) and, for Silence, **Do Not Disturb access** (Tether in the list → Allow).
- **Call someone:** Mac panel → **Calls** tab → type a name or number (or open the dial pad) → Return or the phone button. The phone starts the call by itself, even locked in your pocket, and the panel shows the call card. Talk on the phone's speaker (press **Speaker**) or earbuds. Recent calls have a call-back button; a **Call** button sits in every one-to-one Messages conversation.
- **Phone number links:** Mac Settings › Features › *Phone number links* › **Use Tether** (macOS may ask to confirm). Clicking a number in Safari, Mail, Contacts or Calendar then opens the Calls tab with it filled in (*Show first*, the default) or calls right away (*Call now*).
- **Incoming:** the panel and a notification show the caller with **Answer**, Decline and Silence. *Speaker on when answering from the Mac* (Settings › Features, on by default) switches the phone's speaker on so you can talk without picking it up. The dialer's own call notification is not mirrored twice.
- **During a call:** **End**, **Speaker** and **Mute** appear when your phone lets other apps do them; Tether checks that each one actually took effect and hides it if Android ignored it. Hold isn't available to apps other than the phone app.

**One-time codes on Android 15+ (one-time setup):** when Android hides a notification's content from Tether, the phone (Settings › On your Mac › *One-time codes*) and the Mac panel show this guide:
1. Phone: **Settings › About phone › tap Build number 7 times** (OnePlus: About device › Version) to enable Developer options.
2. **Developer options › Wireless debugging** on, phone and Mac on the same Wi-Fi.
3. Mac: `brew install android-platform-tools`. Phone: *Pair device with pairing code* → Mac: `adb pair <ip:port>` and enter the code, then `adb connect <ip:port>` (the address on the Wireless debugging screen).
4. Mac: `adb shell cmd appops set --user 0 in.benfranklin.tether RECEIVE_SENSITIVE_NOTIFICATIONS allow`
5. **OnePlus / OPPO / realme:** also turn on **Developer options › Disable permission monitoring**, or the phone reverts the change.
6. Restart the phone. Tether shows *Codes are shown* once Android allows it (and brings the guide back if the phone ever reverts it).

### Clipboard auto mode (one-time setup)

Android 10+ lets only the app on screen (or the keyboard) read the clipboard. Two ways around it:

- **Send to Mac (no setup):** select text in any app → **Send to Mac** in the menu that pops up (it may be under ⋮). A toast confirms.
- **Auto mode:** with *log reading* allowed, Tether sees the system's "clipboard access denied" line each time something is copied, opens an invisible window for a split second, reads the clip and sends it. The same trick KDE Connect, XClipper and ClipCascade use. Wireless adb only, no cable:

1. Phone: **Settings › About device › Version › tap Build number 7 times** (other phones: About phone › Build number) to enable Developer options.
2. Phone and Mac on the same Wi-Fi. **Developer options › Wireless debugging** on. **OnePlus / OPPO / realme:** also **Developer options › Disable permission monitoring**, or Android refuses the grant.
3. Mac: `brew install android-platform-tools`
4. Phone: **Wireless debugging › Pair device with pairing code**. Mac: `adb pair <IP:port shown under the code>` and type the 6-digit code.
5. Mac: `adb connect <IP:port>` using the address on the Wireless debugging screen (a different port from pairing). `adb devices` should list the phone.
6. Mac (Tether › Settings › On your Mac › Clipboard › **Send to Mac** puts these on the Mac's clipboard):
   ```sh
   adb shell pm grant in.benfranklin.tether android.permission.READ_LOGS
   adb shell appops set in.benfranklin.tether SYSTEM_ALERT_WINDOW allow
   adb shell am force-stop in.benfranklin.tether
   ```
7. Open Tether → Settings › On your Mac › Clipboard → turn on **Send copies automatically**. Android 13+ asks **Allow Tether to access all device logs?** → **Allow**. Then switch to another app: the status turns to *Working* when Tether's self-check succeeds (or *On · copy something to check*).
8. Optional: hide the *Tether pasted from your clipboard* toast with **Settings › Privacy › Show clipboard access** off.

The grants survive reboots and updates; you can turn Wireless debugging off afterwards. Android 13+ asks for log access again each time Tether's process starts (after a reboot, an update or if Android closes it): open Tether and tap **Allow** (Settings › On your Mac › Clipboard shows *Allow log access* when it's needed).

**Panel layout:** the chevron on the now-playing card collapses it to a mini row (artwork, title, play/pause); **More** under the quick actions shows the other three. Mac Settings › General › Panel picks and orders the pinned three.

**Lock when you walk away:** Mac → Settings › Features → turn on *Lock this Mac when the phone is away*, choose **Near / Medium / Far** (≈1–2 m, 3–5 m, 8–10 m) and **Away for** (10 s – 2 min). *Signal now* shows the live reading so you can check it at your desk. Bluetooth signal varies a lot with pockets, bodies and walls: start with Medium and 30 s.

**Screen sharing, step by step:** Mac menu → **Phone screen** → on the phone tap the *Your Mac wants to see this screen* notification (or it opens directly if Tether is on screen) → **Start now**. A *Sharing your screen* notification with **Stop** stays while it runs. In the Mac window: click = tap, hold = long-press, drag = swipe, two-finger scroll = scroll, type into the focused field, ⌘V pastes, Return = enter, Esc or right-click = Back.

## Updates

The repository is private, so each device needs a **read-only GitHub token** once:

1. Open [github.com/settings/personal-access-tokens/new](https://github.com/settings/personal-access-tokens/new) (Settings › Updates › *Create a token on GitHub* opens it with the name filled in).
2. **Repository access › Only select repositories › Benfranklinms/Tether**. **Permissions › Repository › Contents: Read-only** (Metadata: Read-only is added automatically). Pick an expiry you like.
3. Paste it in **Mac: Settings › Updates** and **Phone: Settings › Continue, files & updates › Updates**. Until then both show *Add a GitHub token to check for updates*.

The token is stored only on that device (Mac Keychain; Android Keystore-encrypted storage), never logged, and sent only to `api.github.com`. Tether reads `releases/latest`, downloads the asset through the GitHub API (`Accept: application/octet-stream`) and follows GitHub's redirect to its file CDN **without** the token. Before installing it checks the release's SHA-256, and on Android the package name, a newer version code and the same signing certificate; on the Mac the bundle id, version and code signature. Android asks you to confirm the install; the Mac replaces the app and relaunches. A custom public feed URL (Tether's `tether-update.json` format) can be used instead of GitHub in the same screens.

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
| Paired but stuck on "Looking nearby…" / "Not connected to your phone" | Tap **Reconnect** (phone home screen, Mac menu-bar panel): it restarts Tether's Bluetooth side, the same as quitting and reopening the app. Both apps also do this on their own after about 45 s down (then 90 s, 180 s, up to every 5 min), and sooner after the screen turns on or the Mac wakes |
| Reconnect doesn't help | Export diagnostics on both. `radio.restart` lines (`reason=manual` or `watchdog`, `attempt`, `total`) show each restart; on the phone `ble.hard_restart` follows with `ble.radio` / `ble.stage` transitions, on the Mac `ble.shutdown` then `ble.advertising`. Phone restarts with no `ble.link_up` after them mean the phone never found the Mac: toggle Bluetooth on both, check *Nearby devices* and Location on the phone |
| Messages window says "Allow SMS" / SMS permission greyed out | Phone: Settings › Apps › Tether › ⋮ › **Allow restricted settings**, then Tether › Settings › On your Mac › Allow SMS access |
| A text shows "Not sent" | Signal, airplane mode or the chosen SIM; press **Retry**. Short codes may be blocked by your carrier |
| Calls show a number instead of a name | Grant **Contacts** and **Call logs** to Tether on the phone |
| "Allow Phone calls for Tether on your phone" when calling | Phone: Settings › On your Mac › *Make calls from the Mac* › **Allow calling** (Make and manage phone calls) |
| "Tap the notification on your phone to start the call" | Android refused the direct call on this phone; tap Tether's *Call …* notification on the phone. Export diagnostics (`call.dial_failed`) and report it |
| "Your phone didn't start the call" | The phone showed an error (airplane mode, no SIM, blocked number). Emergency numbers are refused on purpose: call them from the phone |
| Speaker or Mute button missing during a call | Your phone ignored that change from a non-phone app, so Tether hid the button (log line `call.speaker_ignored` / `call.mute_ignored`). Use the phone's own button |
| Clicking a phone number opens FaceTime | Mac Settings › Features › *Phone number links* › **Use Tether** |
| Copies on the phone don't reach the Mac | Use **Send to Mac** in the selection menu, or set up auto mode. Settings › On your Mac › Clipboard shows what's missing: *Needs one-time setup* (run the adb commands), *Restart Tether to finish* (run the force-stop command, reopen), *Allow display over other apps*, *Allow log access* (tap it, then Allow). Diagnostics: `clip.auto_status`, `clip.auto_read`, `clip.auto_skip why=…`, `clip.auto_launch_blocked`, `clip.auto_probe_unseen` |
| Notifications say "Sensitive notification content hidden" | Android 15+ hides one-time codes from Tether: follow *One-time codes on Android 15+* above |
| Answer / Decline / End do nothing | Grant **Answer phone calls** (Allow call access row). Some OEM dialers ignore Decline/End from other apps |
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
│ provider + SmsManager, Telecom calls, call log, │ VideoToolbox viewer, Messages,│
│ MediaProjection + MediaCodec, accessibility     │ MediaPlayer keys, proximity   │
├──────────────────────────────┤                  ├──────────────────────────────┤
│ android/core (pure JVM)      │  same protocol   │ mac/TetherCore (Swift pkg)   │
│ Noise XX · framing · messages│◄───────────────►│ Noise XX · framing · messages │
│ controller · transfers       │  shared vectors  │ controller · transfers       │
└──────────────────────────────┘  + interop test  └──────────────────────────────┘
          Bluetooth LE (GATT, fragments)  ·  Wi-Fi TCP (length-prefixed records)
```

The protocol is implemented twice, independently, and cross-checked: shared JSON test vectors, Noise test vectors, and an **interop harness** that runs the real Kotlin phone core against the real Swift Mac core over a pipe (pairing, every message type of all four revisions in both directions, a Wi-Fi session, media transfers, split H.264 frames, input mapping, the released 0.3.1 Mac core, old and future peers), simulated multi-hour link soaks with call traffic, and emulator tests that ring the phone through the emulator's modem and answer, decline, end and place calls end to end. The proximity filter (smoothing, hysteresis, dwell, lock policy) has its own unit tests.

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
# + the released 0.3.1 Mac core as the old peer: git worktree add /tmp/old031 v0.3.1 && (cd /tmp/old031/tools/interop && swift build)
#   then also export TETHER_SWIFT_PEER_OLD=/tmp/old031/tools/interop/.build/debug/tether-peer

# Emulator (calls ring through the emulator modem; needs a running emulator with telephony)
cd android && bash scripts/emulator-tests.sh
```

**CI** (`.github/workflows/ci.yml`) runs on every push: detekt, core + Robolectric tests, Android lint, R8 release build and screenshots; the interop job (Ubuntu, Swift + JDK, plus the released 0.3.1 Mac core); an emulator job (API 34: notification reply, MediaStore, real incoming/outgoing calls); SwiftLint, TetherCore tests and a universal Release build of the Mac app on macOS (checks Liquid Glass is linked), ad-hoc signed and packaged; demo-mode screenshots. A `v*` tag publishes the release with `Tether-<version>.apk`, `.dmg` and `.zip`.

**Signing.** Release APKs are signed with Tether's release key, stored encrypted as `android/keystore/tether-release.p12.enc` and decrypted in CI with the `TETHER_STORE_PASSWORD` secret. Local and fork builds use the standard Android debug key. 0.5.0 switched to this key, so uninstall 0.4.x once before installing 0.5.0 (you'll pair again). Try the Mac UI without a phone: `/Applications/Tether.app/Contents/MacOS/Tether --demo`.

## Limitations

- **Real-hardware testing is partial.** Pairing, notifications and the 0.3.0 Messages/link bugs were found and fixed on a MacBook Air + OnePlus Nord CE5 (Android 16). 0.3.1's fixes and everything new in 0.4 are covered by unit, interop, soak and build tests in CI, and calling by emulator tests (a real incoming call from the emulator modem answered, declined and ended; a call placed from the background), but have not yet run on a real phone and Mac. OEM dialers can behave differently from the emulator's.
- **Messages** reads and sends SMS through Android's own provider; Tether is not the default SMS app, so it can't mark messages as read on the phone, delete them, or send MMS (pictures/group MMS). MMS text is shown and attachments are labelled ("Photo") but not transferred. RCS / Google Messages chat messages aren't SMS and don't appear (their notifications are still mirrored, with Reply). OEM SMS providers differ; a few phones store messages where other apps can't read them.
- **Calls**: the call always runs on the phone and **its audio stays on the phone or its earbuds** — Android has no API for an app to route cellular call audio to a Mac, so talk on the phone's speaker or earbuds. Answer uses `TelecomManager.acceptRingingCall`, Decline/End `TelecomManager.endCall` (deprecated, still working on stock Android; some OEM dialers ignore them). Speaker and Mute use AudioManager and work only where the phone lets a non-phone app change them; Tether verifies and hides them otherwise. Hold, merge and call waiting controls need the default-phone-app role, which Tether doesn't take. For outgoing calls Android doesn't tell other apps when the other side picks up, so the timer runs from dialing. Silence mutes the ring stream (needs Do Not Disturb access) and the phone may still vibrate. Emergency numbers can't be dialled from the Mac. With call waiting the card follows the newest call. Phone links: macOS keeps FaceTime as the tel: handler until you choose **Use Tether**.
- **One-time codes (Android 15+)** need the adb step once; Tether can't grant itself that access. Some OEMs revert it (OnePlus/OPPO/realme: *Disable permission monitoring*).
- **Now playing** follows the phone's active media session; apps without one (some browsers, games) don't appear. Volume changes the phone's media volume (or a cast device's).
- **Proximity lock** relies on Bluetooth RSSI, which is noisy and differs between phones: treat the distances as rough. It locks via the system's lock-screen call (falling back to display sleep, which locks only if your Mac asks for the password immediately after sleep). It never unlocks.
- **Replies** work only where the phone app's notification offers an inline reply (RemoteInput). Some apps hide it on the lock screen or for certain chats; SMS apps vary. Tether can't start new conversations.
- **Screen sharing** needs Wi-Fi/hotspot. Android requires consent every session and shows its own capture indicator. Apps that set `FLAG_SECURE` (banking, some video apps, password fields) appear black. Latency target is under ~150 ms on a good network; busy Wi-Fi lowers quality first. Long-press and drags are sent when you release the mouse, not live. Typing uses accessibility text replacement: it works in normal text fields, not in games or custom keyboards' own fields.
- **Find my phone** can't override a phone with alarm volume locked by the OEM or a DND mode that blocks alarms; full-screen alerts may need permission on Android 14+.
- **Auto media** sends screenshots/camera photos taken after you enable it; edited copies are sent again only if their bytes differ. Android's "limited photo access" hides new photos from Tether; choose *Allow all*.
- **Background**: aggressive OEM battery management can still stop the link; set Tether's battery use to Unrestricted.
- **One phone, one Mac.** Not notarized (see Signing). Android 10+ blocks background clipboard reads: phone → Mac is manual (selection menu, tile, share sheet or app) unless you set up auto mode over adb. Auto mode briefly takes focus to read (a keyboard that's open may flicker), Android shows *Tether pasted from your clipboard* each time unless you turn that off, it can't read while the phone is locked, it reads text only, and on Android 13+ log access must be re-allowed after every Tether restart. It relies on the system log wording; a phone maker that changes it would break auto mode (Send to Mac keeps working).

## Credits and license

Tether is released under the [MIT License](LICENSE).

- The Bluetooth fragment framing and the Noise XX structure are adapted from [bitchat](https://github.com/permissionlesstech/bitchat) (Swift, released into the public domain under the Unlicense). Thank you to its authors. The Android implementation was written independently against the Noise specification and test vectors; no bitchat-android (GPL-3.0) code is used.
- [Manrope](https://github.com/sharanda/manrope) typeface, SIL Open Font License 1.1.
- Bouncy Castle (MIT-style), ZXing and zxing-android-embedded (Apache 2.0), AndroidX / Jetpack Compose (Apache 2.0).
