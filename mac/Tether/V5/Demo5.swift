import AppKit
import SwiftUI
import TetherCore

/// Sample 0.5.0 data for --demo and the CI screenshots.
enum Demo5 {
    static func load(_ m: AppModel) {
        guard m.peer != nil else { return }
        let t = Demo.now()
        let v = m.v5
        m.peerCaps.formUnion(Caps5.all)
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
            photos.append(PhotoItem(id: "\(1000 + i)", name: "IMG_\(1000 + i).jpg", size: 2_000_000, date: date,
                                    w: 3000, h: 4000, mime: video ? "video/mp4" : "image/jpeg", video: video))
        }
        v.library = PhoneLibrary(items: photos, more: true)
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
            PermInfo(id: "notif", label: "Notification access", ok: true, required: true),
            PermInfo(id: "files", label: "All files access", ok: false),
        ], sdk: 35, ts: t)
        v.update.tokenSet = false
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
    var body: some View {
        MainWindowView(v5: m.v5)
            .frame(width: 1040, height: 700)
            .onAppear { m.v5.page = page }
    }
}
