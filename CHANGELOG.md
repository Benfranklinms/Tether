# Changelog

What changed in each Tether release, written for people who use the apps. The release job in
CI copies the section whose heading matches the tag (`## 0.5.2` for `v0.5.2`) into the GitHub
release notes, and the apps show it in their update popup. A tag without a matching section
fails the release.

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
