import Foundation
import TetherCore

/// Sample data for `--demo` (try the UI without a phone) and `--screenshots <dir>`.
enum Demo {
    static let phone = "Pixel 8"
    static let pairingURI = PairingURI(
        key: Data(repeating: 7, count: KeyPair.keyLength), token: Data(repeating: 3, count: PairingURI.tokenLength), name: "MacBook Air"
    ).encode()

    enum Shot { case unpaired, connected, qr, code, done, linking, failed }

    static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    static func load(_ m: AppModel, _ scene: Shot) {
        let t = now()
        m.myFingerprint = fingerprint(Data(repeating: 9, count: 32))
        guard scene != .unpaired else { return }
        m.bluetooth = .on
        m.radio = RadioDiag(advertising: true, serviceReady: true)
        if scene == .linking {
            m.pairingURI = pairingURI
            m.radio.phoneSeen = true
            m.radio.subscribed = true
            m.link = .handshaking
            return
        }
        if scene == .failed {
            m.pairingURI = pairingURI
            m.pairFailure = "Your phone connected, but the secure link didn't finish. "
                + "Try again with both devices close. If it keeps happening, export diagnostics on both."
            return
        }
        if scene == .qr {
            m.pairingURI = pairingURI
            return
        }
        if scene == .code {
            m.pairingURI = pairingURI
            m.link = .pairing(sas: "482913", peerName: phone, auto: false)
            return
        }
        m.peer = PeerRecord.paired(name: phone, platform: "android", key: Data(repeating: 5, count: 32), now: t - 86_400_000 * 3)
        if scene == .done {
            m.link = .connected(peerName: phone, wifi: false)
            m.justPaired = true
            return
        }
        m.link = .connected(peerName: phone, wifi: true)
        m.bluetooth = .on
        let notes = [
            NotifPayload(key: "d1", pkg: "com.google.android.gm", app: "Gmail", title: "Placement Cell",
                         text: "Drive registration closes at 5 PM", ts: t - 31 * 60_000),
            NotifPayload(key: "d2", pkg: "org.telegram.messenger", app: "Telegram", title: "Project group",
                         text: "Pushed the new build, can you test?", ts: t - 9 * 60_000),
            NotifPayload(key: "d3", pkg: "com.whatsapp", app: "WhatsApp", title: "Amma", text: "Call when you're free", ts: t - 2 * 60_000),
        ]
        var history = NotificationHistory()
        for n in notes { history.add(n, limit: 50) }
        history.noteApp(NotifPayload(key: "x", pkg: "com.instagram.android", app: "Instagram", ts: t))
        m.history = history
        m.settings.notifications.apps["com.instagram.android"] = MacAppPref(visibility: .hide, delivery: .silent)
        m.settings.notifications.apps["org.telegram.messenger"] = MacAppPref(visibility: .show, delivery: .silent)
        m.transfers = [
            TransferRecord(id: "a", direction: .OUT, name: "Resume-2026.pdf", size: 2_400_000, sha256: "", mime: "application/pdf",
                           status: .ACTIVE, transferred: 1_550_000, createdAt: t, via: .WIFI),
            TransferRecord(id: "b", direction: .IN, name: "IMG_2041.HEIC", size: 4_800_000, sha256: "", mime: "image/heic",
                           status: .DONE, transferred: 4_800_000, createdAt: t - 600_000, via: .WIFI),
            TransferRecord(id: "c", direction: .IN, name: "Lecture-notes.pdf", size: 1_200_000, sha256: "", mime: "application/pdf",
                           status: .DONE, transferred: 1_200_000, createdAt: t - 3_600_000, via: .BLE),
        ]
    }

    static func finishPairing(_ m: AppModel) {
        m.peer = PeerRecord.paired(name: phone, platform: "android", key: Data(repeating: 5, count: 32), now: now())
        m.link = .connected(peerName: phone, wifi: false)
        m.pairingURI = nil
        m.justPaired = true
    }
}
