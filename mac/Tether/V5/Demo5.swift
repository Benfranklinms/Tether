import AppKit
import SwiftUI
import TetherCore

/// Sample 0.5.0 data for --demo and the CI screenshots.
enum Demo5 {
    /// 0.5.3: file cards show a friendly kind, not their MIME type.
    private struct DemoFile {
        let name: String
        let size: Int64
        let mime: String
        let from: String
        let age: Int64
    }

    /// 0.5.3: file cards show a friendly kind, real thumbnails and "Phone · 12m".
    static func demoShelfFiles(_ start: ShelfState, now t: Int64) -> ShelfState {
        let files = [
            DemoFile(name: "Quiz_answer_script.docx", size: 11_264,
                     mime: "application/vnd.openxmlformats-officedocument.wordprocessingml.document", from: "mac", age: 5 * 60_000),
            DemoFile(name: "Aptitude Practice Set 15 Questions.pdf", size: 464_896, mime: "application/pdf", from: "mac", age: 2 * 60_000),
            DemoFile(name: "IMG_20261010_195129.jpg", size: 4_300_000, mime: "image/jpeg", from: "phone", age: 12 * 60_000),
            DemoFile(name: "Screenshot_2026-10-09-21-14.png", size: 1_150_000, mime: "image/png", from: "phone", age: 50 * 3_600_000),
            DemoFile(name: "tether-debug.zip", size: 13_000_000, mime: "application/zip", from: "mac", age: 74 * 3_600_000),
        ]
        var all = start.items
        for (i, f) in files.enumerated() {
            let file = ShelfFile(name: f.name, size: f.size, mime: f.mime, sha256: "demo\(i)", owner: f.from)
            all.append(ShelfState.fileItem(file, id: "demo-file-\(i)", from: f.from, now: t - f.age))
        }
        // Oldest first, so the newest ends up on top (Today, then Earlier).
        return all.sorted { $0.created < $1.created }.reduce(ShelfState()) { $0.add($1) }
    }

    /// 0.5.4: three folders, a few items filed in them (the rest stay loose, as in the mockup).
    static func demoFolders(_ start: ShelfState, now t: Int64) -> ShelfState {
        var s = start
        for (i, name) in ["Work", "Receipts", "Trip"].enumerated() {
            s = s.createFolder(id: "demo-folder-\(i)", name: name, now: t - Int64(10 - i) * 60_000) ?? s
        }
        let filed: [(String, String)] = [
            ("demo-file-4", "demo-folder-0"), ("demo-0", "demo-folder-0"), ("demo-file-3", "demo-folder-1"), ("demo-1", "demo-folder-2"),
        ]
        for (item, folder) in filed { s = s.moveTo(item, folder: folder, now: t) ?? s }
        return s
    }

    /// Thumbnails for the demo's picture files (there is no encrypted local copy in demo mode).
    static let shelfThumbs: [String: NSImage] = ["demo-file-2": image(0.62), "demo-file-3": image(0.42)]

    static func load(_ m: AppModel) {
        guard m.peer != nil else { return }
        let t = Demo.now()
        let v = m.v5
        m.peerCaps.formUnion(Caps5.all)
        m.peerCaps.formUnion(Caps6.all)
        // 0.5.2 Shelf (as in the mockup, with neutral sample data).
        var shelf = ShelfState()
        let samples: [(String, String, Int64)] = [
            ("adb mdns services\nadb connect 192.168.29.142:37099", "mac", 3 * 3_600_000),
            ("Public Library\n1 Market St, San Francisco, CA 94105", "phone", 3_600_000),
            ("Event registration\nhttps://forms.gle/x7Tq2abc", "mac", 14 * 60_000),
            ("WIFI:S:Office_5G;T:WPA;P:demo@2027;;", "phone", 2 * 60_000),
        ]
        for (i, s) in samples.enumerated() {
            shelf = shelf.add(ShelfState.item(from: s.0, id: "demo-\(i)", from: s.1, now: t - s.2))
        }
        v.shelf = demoFolders(demoShelfFiles(shelf, now: t), now: t)
        v.otp = OtpPayload(code: "482913", app: "Messages", src: "sms", ts: t - 20_000)
        _ = v.continueCards.add(ContinuePayload(id: "p1", kind: ContinueKinds.photo, title: "IMG_20261010_163210.jpg",
                                                subtitle: "Just now · 3.1 MB", root: FsRoots.dcim, path: "Camera/IMG_20261010_163210.jpg",
                                                size: 3_100_000, ts: t), thumb: png(0.55), now: t)
        _ = v.continueCards.add(ContinuePayload(id: "l1", kind: ContinueKinds.link, title: "Compose Multiplatform 1.9 release notes",
                                                subtitle: "github.com", url: "https://github.com", ts: t), thumb: Data(), now: t)
        _ = v.continueCards.add(ContinuePayload(id: "s1", kind: ContinueKinds.song, title: "Kesariya", subtitle: "Arijit Singh",
                                                app: "Spotify", ts: t), thumb: png(0.3), now: t)
        v.folder = PhoneFolder(root: FsRoots.downloads, path: "", entries: [
            FsEntry(name: "Telegram", dir: true, mtime: t - 3_600_000),
            FsEntry(name: "Quarterly_Report.pdf", size: 2_480_000, mtime: t - 7_200_000, mime: "application/pdf"),
            FsEntry(name: "IMG-20261009-WA0004.jpg", size: 412_000, mtime: t - 86_400_000, mime: "image/jpeg"),
            FsEntry(name: "Meeting_Notes.docx", size: 88_000, mtime: t - 172_800_000,
                    mime: "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            FsEntry(name: "screen-recording.mp4", size: 28_400_000, mtime: t - 259_200_000, mime: "video/mp4"),
        ], total: 5)
        v.thumbs[m.thumbKey(root: FsRoots.downloads, path: "IMG-20261009-WA0004.jpg")] = image(0.6)
        v.thumbs[m.thumbKey(root: FsRoots.downloads, path: "screen-recording.mp4")] = image(0.25)
        var photos: [PhotoItem] = []
        for i in 0..<18 {
            let video = i % 7 == 3
            let date: Int64 = t - Int64(i) * 3_600_000
            photos.append(PhotoItem(id: "\(1000 + i)", name: "IMG_\(1000 + i).jpg", size: 2_000_000, date: date - (i > 9 ? 86_400_000 : 0),
                                    w: 3000, h: 4000, mime: video ? "video/mp4" : "image/jpeg", video: video,
                                    dur: video ? 42_000 : 0, fav: i == 1))
        }
        v.library = PhoneLibrary(items: photos, more: true)
        v.albums = [
            AlbumInfo(id: AlbumKinds.all, name: "All Photos", count: 1284, cover: "1000", kind: AlbumKinds.all),
            AlbumInfo(id: AlbumKinds.camera, name: "Camera", count: 912, cover: "1001", kind: AlbumKinds.camera),
            AlbumInfo(id: AlbumKinds.screenshots, name: "Screenshots", count: 203, cover: "1002", kind: AlbumKinds.screenshots),
            AlbumInfo(id: AlbumKinds.whatsapp, name: "WhatsApp Images", count: 96, cover: "1004", kind: AlbumKinds.whatsapp),
            AlbumInfo(id: AlbumKinds.videos, name: "Videos", count: 41, cover: "1003", kind: AlbumKinds.videos),
            AlbumInfo(id: AlbumKinds.favorites, name: "Favorites", count: 12, cover: "1001", kind: AlbumKinds.favorites),
            AlbumInfo(id: "b:77", name: "Instagram", count: 20, cover: "1005"),
        ]
        for i in 0..<18 { v.thumbs[m.thumbKey(root: FsRoots.photos, path: "\(1000 + i)")] = image(0.2 + Double(i % 6) * 0.12) }
        let clips: [ClipItem] = [
            ClipItem(kind: .text, text: "482913", direction: .fromPhone, ts: t - 20_000, hash: "a"),
            ClipItem(kind: .text, text: "https://github.com/Benfranklinms/Tether/releases", direction: .fromMac, ts: t - 600_000,
                hash: "b"),
            ClipItem(kind: .text, text: "Design review moved to Monday", direction: .fromPhone, ts: t - 3_600_000, hash: "c"),
        ]
        for c in clips.reversed() { v.clips.add(c) }
        v.phoneDnd = DndStatePayload(on: false, src: "phone", ts: t, canSet: true)
        v.phoneStatus = PhoneStatusPayload(perms: [
            PermInfo(id: "nearby", label: "Nearby devices", ok: true, required: true),
            PermInfo(id: "notif", label: "Notification access", ok: true, required: true),
            PermInfo(id: "battery", label: "Battery unrestricted", ok: false, required: true),
            PermInfo(id: "sms", label: "SMS", ok: true),
            PermInfo(id: "allfiles", label: "All files access", ok: true),
            PermInfo(id: "accessibility", label: "Accessibility (Tether control)", ok: false),
            PermInfo(id: "readlogs", label: "Read logs (adb)", ok: false),
            PermInfo(id: "sensitive", label: "Sensitive notifications (adb)", ok: false),
        ], sdk: 35, ts: t)
        v.setupGate = SetupHomeGate()
        v.update.phase = .idle
    }

    /// A flat grey square, so thumbnails look like photos without shipping any.
    static func image(_ grey: Double) -> NSImage {
        let size = NSSize(width: 160, height: 160)
        let img = NSImage(size: size)
        img.lockFocus()
        NSGradient(starting: NSColor(white: grey, alpha: 1), ending: NSColor(white: max(0, grey - 0.25), alpha: 1))?
            .draw(in: NSRect(origin: .zero, size: size), angle: -60)
        img.unlockFocus()
        return img
    }

    /// A release as the GitHub API returns it, for the update-sheet screenshot (body shaped like
    /// CI's release notes: "What's new in" from CHANGELOG.md, then Downloads).
    static func updateInfo() -> UpdateInfo? {
        let body = """
        ## What's new in 0.5.2

        - **Animations** across the Mac and Android apps.
        - **Videos play** in Photos.
        - **Wi-Fi fast path comes back by itself** when the network or hotspot changes.
        - **Shelf**, **Insert from Phone**, **Find my Mac** and **Instant Hotspot**.

        ## Downloads

        - macOS: Tether-0.5.2.dmg or Tether-0.5.2.zip
        """
        let obj: [String: Any] = [
            "tag_name": "v0.5.2", "body": body, "html_url": "https://github.com/Benfranklinms/Tether/releases/tag/v0.5.2",
            "assets": [["name": "Tether-0.5.2.zip", "size": 5_900_000,
                        "browser_download_url": "https://github.com/Benfranklinms/Tether/releases/download/v0.5.2/Tether-0.5.2.zip"]],
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: obj) else { return nil }
        return UpdateFeed.parse(data)
    }

    static func png(_ grey: Double) -> Data {
        guard let tiff = image(grey).tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
              let data = rep.representation(using: .png, properties: [:]) else { return Data() }
        return data
    }
}

/// The main window fixed to one page, for screenshots.
struct MainShot: View {
    @EnvironmentObject var m: AppModel
    let page: MainPage
    var prepare: (AppModel) -> Void = { _ in }
    var body: some View {
        MainWindowView(v5: m.v5)
            .frame(width: 1040, height: 700)
            .onAppear {
                prepare(m)
                m.v5.page = page
            }
    }
}
