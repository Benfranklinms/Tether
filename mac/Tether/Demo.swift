import Foundation
import TetherCore

/// Sample data for `--demo` (try the UI without a phone) and `--screenshots <dir>`.
enum Demo {
    static let phone = "Pixel 8"
    static let pairingURI = PairingURI(
        key: Data(repeating: 7, count: KeyPair.keyLength), token: Data(repeating: 3, count: PairingURI.tokenLength), name: "MacBook Air"
    ).encode()

    enum Shot { case unpaired, connected, qr, code, done, linking, failed, glass, screen, call, liveCall, calls, codes }

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
        m.peerCaps = Set(Caps.current)
        m.battery = BatteryPayload(level: 76, charging: false, net: "cellular", gen: "5G", signal: 3, ts: t)
        if scene == .glass { m.settings.appearance = .glass }
        if scene == .screen {
            m.screen.phase = .consent
        }
        loadFeatures3(m, scene, t)
        loadFeatures4(m, scene, t)
        let notes = [
            NotifPayload(key: "d1", pkg: "com.google.android.gm", app: "Gmail", title: "Jordan",
                         text: "Tickets for Saturday are booked", ts: t - 31 * 60_000),
            NotifPayload(key: "d2", pkg: "org.telegram.messenger", app: "Telegram", title: "Project group",
                         text: "Pushed the new build, can you test?", ts: t - 9 * 60_000),
            NotifPayload(key: "d3", pkg: "com.whatsapp", app: "WhatsApp", title: "Alex", text: "Call when you're free",
                         ts: t - 2 * 60_000, actions: [NotifActionInfo(id: "reply0", title: "Reply", reply: true),
                                                  NotifActionInfo(id: "a1", title: "Mark as read")]),
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

    private static func loadFeatures3(_ m: AppModel, _ scene: Shot, _ t: Int64) {
        m.nowPlaying = NowPlayingPayload(active: true, pkg: "com.spotify.music", app: "Spotify", title: "Kesariya",
                                         artist: "Arijit Singh", album: "Brahmastra", durationMs: 268_000, positionMs: 94_000,
                                         playing: true, speed: 1, canSeek: true, canNext: true, canPrev: true, volume: 0.6, ts: t)
        if scene == .call {
            m.call = CallStatePayload(id: "c1", state: CallStates.ringing, number: "+1 415-555-0132", name: "Alex", ts: t)
        }
        m.sms.sims = [SimInfo(id: 1, slot: 0, name: "Jio"), SimInfo(id: 2, slot: 1, name: "Airtel")]
        m.sms.threads = [
            SmsThread(id: 1, addrs: ["+14155550132"], names: ["Alex"], snippet: "Reached? Call when free",
                      date: t - 4 * 60_000, unread: 1),
            SmsThread(id: 2, addrs: ["BANK-ALERT"], names: [nil], snippet: "OTP 482913 for your transaction. Do not share.",
                      date: t - 52 * 60_000),
            SmsThread(id: 3, addrs: ["+14155550187"], names: ["Sam"], snippet: "Design review moved to Monday",
                      date: t - 3 * 3_600_000, outLast: true),
            SmsThread(id: 4, addrs: ["+14155550111"], names: ["Jordan"], snippet: "Tickets for Saturday are booked",
                      date: t - 26 * 3_600_000),
        ]
        m.sms.selected = 1
        m.sms.conversations[1] = [
            SmsMessage(id: "s1", thread: 1, addr: "+14155550132", name: "Alex", body: "Did you eat?", date: t - 75 * 60_000, sub: 1),
            SmsMessage(id: "s2", thread: 1, addr: "+14155550132", body: "Yes, just grabbing lunch. Leaving at 5.",
                       date: t - 70 * 60_000, out: true, status: SmsStatus.sent, sub: 1),
            SmsMessage(id: "s3", thread: 1, addr: "+14155550132", name: "Alex", body: "Reached? Call when free",
                       date: t - 4 * 60_000, sub: 1, read: false),
        ]
    }

    private static func loadFeatures4(_ m: AppModel, _ scene: Shot, _ t: Int64) {
        m.calls.seenUntil = t - 50 * 60_000
        m.calls.log = [
            CallLogEntry(id: 9, number: "+1 415-555-0132", name: "Alex", type: CallLogTypes.missed, date: t - 6 * 60_000, sub: 1,
                         isNew: true),
            CallLogEntry(id: 8, number: "+1 415-555-0187", name: "Sam", type: CallLogTypes.outgoing, date: t - 47 * 60_000,
                         dur: 312, sub: 2),
            CallLogEntry(id: 7, number: "+1 415-555-0111", name: "Jordan", type: CallLogTypes.incoming, date: t - 5 * 3_600_000,
                         dur: 95, sub: 1),
            CallLogEntry(id: 6, number: "1-800-555-0199", type: CallLogTypes.outgoing, date: t - 26 * 3_600_000, dur: 64, sub: 1),
            CallLogEntry(id: 5, number: "+1 212-555-0148", type: CallLogTypes.rejected, date: t - 30 * 3_600_000, sub: 2),
        ]
        m.calls.more = true
        switch scene {
        case .liveCall:
            m.settings.panel.compactPlayer = true
            m.liveCall = CallLivePayload(id: "c2", dir: CallDirs.outgoing, state: CallLiveStates.active, number: "+1 415-555-0132",
                                         name: "Alex", since: t - 83_000, speaker: true, canEnd: true, canSpeaker: true,
                                         canMute: true, sub: 1, ts: t)
        case .calls:
            m.settings.panel.moreExpanded = true
            m.calls.query = "ra"
            m.calls.contacts = [ContactInfo(name: "Sam", numbers: [ContactNumber(n: "+1 415-555-0187", label: "Mobile")])]
        case .codes:
            m.notifAccess = NotifAccessPayload(redacting: true, sdk: 36, oem: "OnePlus", ts: t)
        default:
            break
        }
    }

    static func finishPairing(_ m: AppModel) {
        m.peer = PeerRecord.paired(name: phone, platform: "android", key: Data(repeating: 5, count: 32), now: now())
        m.link = .connected(peerName: phone, wifi: false)
        m.pairingURI = nil
        m.justPaired = true
    }
}
