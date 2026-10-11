# Changelog

What changed in each Tether release, written for people who use the apps. The release job in
CI copies the section whose heading matches the tag (`## 0.5.2` for `v0.5.2`) into the GitHub
release notes, and the apps show it in their update popup. A tag without a matching section
fails the release.

## 0.5.4

- **Shelf folders.** Group Shelf items into folders on both devices. Folders sit as chips above your Shelf: tap one to open it, drag an item onto a chip to file it, or use **Move to…**. Rename, delete (items go back to your Shelf, nothing is removed) or keep a whole folder offline from its menu.
- **The Shelf holds up to 500 items** (was 50), and large Shelves sync in small pieces so nothing stalls.
- **Offline copies with a limit you choose.** Shelf files are kept on each device so they open, copy and save while the other one is away, inside folders too. Settings › Shelf sets the space they may use (500 MB, 1 GB, 2 GB, 5 GB or Off; 1 GB on the phone, 2 GB on the Mac by default). The least recently opened go first; items you keep offline and files you added on that device always stay.
- **Works without a connection.** Browse, open and copy what is on the device, and create, rename, delete and move folders; changes sync when the devices reconnect. Items not on this device yet show a small cloud.
- **No more Keychain password prompts on the Mac** for the Shelf after an update.
- **Fixed a crash when opening Tether** on the phone ("layouts are not part of the same hierarchy").
- **Reconnects faster.** The Mac restarts Bluetooth advertising when a link is lost and checks it regularly, so the phone finds it right away after a crash or restart; the phone retries at least once a minute while you're using it, and picks the Mac back up as soon as Tether restarts. A phone reconnecting to a Mac that still had the old session no longer fails with a protocol error.
- **Snippet cards** no longer repeat their title in the detail line.
- **What's new** shows reliably after updating, and on the Mac it stays until you close it.
- **A calmer Home on the phone.** Your Mac and its battery at the top ("Connected · Wi-Fi · 82%", "Connecting…" or "Not connected" with Reconnect), then one block with Clipboard, File, Hotspot and Photos next to what's playing on your Mac, your latest transfers with live progress, and the newest Shelf items. Empty sections are just one grey line.
- **Edit Home** at the bottom of Home: drag sections into the order you like and switch Clipboard history, Photos or Find my Mac on. Changes show on Home right away and are kept after a restart. Your 0.5.3 layout carries over. (No more long-press wiggle mode, which could close the app.)
- **Photos button**: pick photos and videos to send to your Mac; a badge counts the new ones.
- **Home never shows what's on your clipboard**: Send clipboard just says Clipboard, and Clipboard history says what kind of thing was copied ("Phone number", "Link"), not the text.
- **Home fits large text and every navigation style**: nothing is cut off at bigger font sizes, and nothing hides under the status bar or the three-button or gesture navigation bar.
- **A broken saved Home layout can no longer reset all your settings**; Home just goes back to its default.

## 0.5.3

- **Take Photo and Scan Document work from the Mac menu bar.** The phone now really opens the camera even when Tether is in the background (Android blocks that unless Tether is allowed to). If it can't, your phone shows a notification to tap, and the Mac tells you so instead of waiting silently.
- **Copy on a Shelf photo or file copies the file itself.** Paste in Finder, Mail or Messages gives you the picture or the document, not a line of text.
- **Shelf files open even when the other device is away.** Every Shelf file up to 100 MB is copied to both devices in the background (encrypted, like the rest of the Shelf).
- **Rename Shelf items** on both devices (right-click › Rename or double-click the title on the Mac; long-press or the detail screen on the phone). The new name is used when you open or copy the file.
- **Search the Shelf**: ⌘F on the Mac, the search bar on the phone. Searches titles, contents, file names and types.
- **Shelf cards say "Word document" or "PDF"** instead of long technical types, and image files show a thumbnail.
- **Transfers on the phone stay tidy**: Home shows the latest three with **See all**, a full list grouped by day with Sent / Received filters, swipe to delete and Clear all. Finished transfers fold away after a day.
- **Nothing scrolls under the phone's status bar or navigation bar** any more, on any screen, and the clock and battery icons stay readable in light mode too.
- **Find my Mac moved to Settings** on the phone, so Home stays short.
- **Shelf snippet cards** no longer repeat their title in the preview.
- **Continue on Mac cards** wait until a new photo is fully saved, so the thumbnail and size are right (no more black thumbnails).
- **What's new** now shows after every update, including updates from 0.5.1 and older.
- **A new Home on the phone, made of panels.** Send clipboard, Send file, Shelf, Mac battery, Clipboard, Now playing on your Mac, Recent transfers, Photos and Hotspot. Long-press or tap Edit to rearrange: drag to move, − to remove, the corner to resize, + to add from the panel picker. Your layout is saved.
- **See and control your Mac from the phone**: the Mac panel shows its battery, Now playing shows what Music or Spotify is playing on the Mac with play/pause and skip.
- **Send recent photos from Home**: pick a few in the Photos panel and tap Send.
- **Redesigned Shelf on both devices**: real thumbnails, a document icon with its type (PDF, DOC, ZIP), one calm line of detail ("PDF · 454 KB", "forms.gle", "Snippet · 2 lines"), where it came from and when, grouped Today and Earlier. On the Mac, hover a card for Copy, Open, Rename and More; Rename selects the name without its extension.
- **Transfers redesigned on the phone**: a large title, All / Sent / Received pills, the time of each transfer, and a percentage while it moves. Long-press a row to cancel, retry or remove.
- **Clear all notifications on the Mac**, in the main window and the menu-bar panel. It also clears their banners, and with Dismiss on both on, dismisses them on your phone too. It asks first only when there are many.

## 0.5.2

- **Animations in both apps.** Pages slide in the direction you move, notifications and messages open with a hero transition, photos zoom out of their tile and back, lists cascade in and swipe away, buttons react when pressed. Everything falls back to a simple fade with Reduce Motion.
- **Videos play in Photos.** Opening a video on the Mac now fetches it from the phone (over Wi-Fi when it can, Bluetooth otherwise) with a progress ring, then plays it with the standard macOS controls.
- **The Wi-Fi fast path comes back by itself** when the Mac or the phone changes network, including when the Mac joins the phone's hotspot.
- **Shelf.** A pinned list shared by both devices: text, links, Wi-Fi networks, addresses, code snippets and files. Add from either side, drag to reorder, swipe or press Delete to remove. Up to 50 items.
- **Insert from Phone.** Choose Take Photo or Scan Document on the Mac, use the phone's camera, and the picture is pasted into the app you were using (or saved to the Desktop if Tether may not paste).
- **Find my Mac.** Ring the Mac from the phone: a loud chime and a full-screen "Found it" on every display, or lock the Mac instead.
- **Instant Hotspot** (best effort). One click on the Mac asks the phone to turn on its hotspot and joins it. It needs the hotspot name and password saved once in Tether on the phone, and Tether control turned on so the phone can flip the switch. On phones where the switch can't be found, the phone opens its hotspot settings for you instead.
- **Update popup and What's new.** When a new version is out, Tether shows a popup with these notes and Update now / Later / Skip this version. After updating, a one-time What's new sheet shows what changed.

## 0.5.1

- **Clipboard images work both ways.** Copying an image (including an image file in Finder, or a photo in a gallery app) sends the picture, not its file name.
- **Type on your phone** shows whether Tether control is on, with a **Fix** button that opens the setting on the phone, and reports every keystroke batch that doesn't land.
- **Calls float above everything**: an incoming call shows a banner at the top right with Answer, Decline and Silence, then a small live-call bar.
- **Photos is a proper gallery** with albums, date sections, filters, multi-select and a zooming viewer; **Files** has a grid view.
- **Setup is quieter**: Home shows one slim "Setup · n left" row only while something required is missing.
- Text never wraps mid-word or squeezes row titles, at any text size.

## 0.5.0

- **A full Mac app** with a sidebar (Home, Messages, Calls, Notifications, Files, Photos, Phone screen, Clipboard), ⌘F search across everything, a slim menu-bar popover, global shortcuts, widgets and a Finder Share extension.
- **Your phone's files and photos** on the Mac, with thumbnails, preview and drag to Finder.
- **One-time codes** copied to the Mac clipboard automatically, **Continue on Mac** cards, **open on phone**, **type on the phone**, **Focus and Do Not Disturb** kept in step, clipboard images and history.
- **Setup checklists** on both devices and **in-app updates** from GitHub Releases.
- Dark, Light or System appearance. New signing key on Android: uninstall 0.4.x once before installing.
