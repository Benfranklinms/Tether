import XCTest
@testable import TetherCore

final class NoiseTests: XCTestCase {
    private func handshake() throws -> (HandshakeResult, HandshakeResult) {
        let a = HandshakeState(role: .initiator, localStatic: .generate(), prologue: Data())
        let b = HandshakeState(role: .responder, localStatic: .generate(), prologue: Data())
        _ = try b.readMessage(try a.writeMessage())
        _ = try a.readMessage(try b.writeMessage())
        _ = try b.readMessage(try a.writeMessage())
        return (try a.split(), try b.split())
    }

    func testRoundTrip() throws {
        let (a, b) = try handshake()
        for i in 0..<100 {
            let m = Data("message \(i)".utf8)
            XCTAssertEqual(try b.receive.decrypt(try a.send.encrypt(m)), m)
            XCTAssertEqual(try a.receive.decrypt(try b.send.encrypt(m)), m)
        }
    }

    func testTamperRejectedWithoutDesync() throws {
        let (a, b) = try handshake()
        let ct = try a.send.encrypt(Data("secret".utf8))
        var bad = ct
        bad[bad.startIndex] ^= 1
        XCTAssertThrowsError(try b.receive.decrypt(bad))
        XCTAssertEqual(try b.receive.decrypt(ct), Data("secret".utf8))
    }

    func testPrologueMismatchFails() throws {
        let a = HandshakeState(role: .initiator, localStatic: .generate(), prologue: Data("tether/1".utf8))
        let b = HandshakeState(role: .responder, localStatic: .generate(), prologue: Data("tether/1/wifi".utf8))
        _ = try b.readMessage(try a.writeMessage())
        XCTAssertThrowsError(try a.readMessage(try b.writeMessage()))
    }

    func testLowOrderKeyRejected() throws {
        let b = HandshakeState(role: .responder, localStatic: .generate(), prologue: Data())
        _ = try b.readMessage(Data(count: 32))
        XCTAssertThrowsError(try b.writeMessage())
    }

    func testOutOfTurn() {
        let a = HandshakeState(role: .initiator, localStatic: .generate(), prologue: Data())
        XCTAssertThrowsError(try a.readMessage(Data(count: 32)))
    }
}

final class FramingTests: XCTestCase {
    func testRandomRoundTrips() throws {
        let f = Fragmenter()
        let r = Reassembler()
        for i in 0..<200 {
            let rec = randomData(Int.random(in: 0..<20_000), seed: UInt64(i))
            let mtu = Int.random(in: 20...512)
            let frags = try f.fragment(rec, maxFragment: mtu)
            XCTAssertTrue(frags.allSatisfy { $0.count <= mtu })
            for x in frags.dropLast() { XCTAssertNil(r.accept(x)) }
            XCTAssertEqual(r.accept(frags.last!), rec)
        }
        XCTAssertEqual(r.dropped, 0)
    }

    func testOutOfOrderRecovers() throws {
        let f = Fragmenter()
        let r = Reassembler()
        let a = try f.fragment(Data(repeating: 1, count: 100), maxFragment: 30)
        let b = try f.fragment(Data(repeating: 2, count: 50), maxFragment: 30)
        XCTAssertNil(r.accept(a[0]))
        XCTAssertNil(r.accept(a[2]))
        XCTAssertEqual(r.dropped, 1)
        var out: Data?
        for x in b { out = r.accept(x) }
        XCTAssertEqual(out, Data(repeating: 2, count: 50))
    }

    func testMalformedCounted() {
        let r = Reassembler()
        XCTAssertNil(r.accept(Data([1, 2])))
        XCTAssertNil(r.accept(Data([2, 0, 0, 0, 0, 0, 1])))
        XCTAssertNil(r.accept(Data([1, 0, 0, 0, 0, 0, 0])))
        XCTAssertEqual(r.dropped, 3)
    }

    func testStreamDeframer() throws {
        let d = StreamDeframer()
        let recs = [Data([1]), randomData(70_000, seed: 3), Data([9, 9])]
        var wire = Data()
        for r in recs { wire.append(StreamFraming.frame(r)) }
        var out: [Data] = []
        var i = wire.startIndex
        while i < wire.endIndex {
            let j = min(wire.endIndex, i + 1000)
            out += try d.feed(wire.subdata(in: i..<j))
            i = j
        }
        XCTAssertEqual(out, recs)
        XCTAssertThrowsError(try StreamDeframer().feed(Data([0x7f, 0, 0, 0])))
    }

    func testCodecRoundTripAllTypes() throws {
        let all: [Message] = [
            .ping(t: 1), .pong(t: 2), .pairConfirm(token: "t"), .pairConfirm(token: nil), .pairReject(reason: "user"),
            .unpair(reason: "user"), .keyRotate(key: "k"), .keyRotateAck(key: "k"), .clip(id: "i", ts: 3, text: ""),
            .clip(id: "i", ts: 3, text: "日本語 🎉"), .notif(NotifPayload(key: "k", pkg: "p", app: "A", title: "T", text: "x", ts: 4)),
            .notifRemoved(key: "k"), .notifDismiss(key: "k"),
            .fileOffer(FileOfferPayload(id: "i", name: "n", size: 0, sha256: String(repeating: "b", count: 64))),
            .fileAccept(id: "i", offset: 7), .fileReject(id: "i", reason: "space"), .fileChunk(id: "i", offset: 9, data: Data()),
            .fileChunk(id: "i", offset: 9, data: Data(repeating: 3, count: 1000)), .fileAck(id: "i", offset: 10),
            .fileDone(id: "i"), .fileComplete(id: "i", ok: true, reason: nil), .fileCancel(id: "i", reason: "user"),
            .wifiOffer(WifiOfferPayload(addrs: [], port: 0, token: "t")), .wifiRevoke(reason: "network"), .wifiBind(token: "t"),
        ]
        for m in all { XCTAssertEqual(try MessageCodec.decode(try MessageCodec.encode(m)), m) }
        XCTAssertThrowsError(try MessageCodec.decode(Data([0x10, 0, 0])))
        XCTAssertThrowsError(try MessageCodec.decode(Data([0x10, 0, 0, 0, 50, 0x7b])))
        XCTAssertThrowsError(try MessageCodec.decode(Data([0x10, 0, 0, 0, 2]) + Data("{}".utf8)))
    }
}

final class MiscTests: XCTestCase {
    func testBackoff() {
        var b = Backoff(baseMs: 1000, maxMs: 60_000, jitter: 0)
        XCTAssertEqual((0..<8).map { _ in b.next() }, [1000, 2000, 4000, 8000, 16000, 32000, 60000, 60000])
        b.reset()
        XCTAssertEqual(b.next(), 1000)
    }

    func testHeartbeat() {
        var h = Heartbeat(intervalMs: 100, timeoutMs: 50)
        h.reset(0)
        XCTAssertEqual(h.tick(50), .none)
        XCTAssertEqual(h.tick(100), .sendPing)
        XCTAssertEqual(h.tick(151), .dead)
    }

    func testPeerRecordRotation() {
        let a = KeyPair.generate().publicKey
        let b = KeyPair.generate().publicKey
        var p = PeerRecord.paired(name: "P", platform: "android", key: a, now: 1)
        XCTAssertTrue(p.trusts(a))
        p = p.withPendingKey(b)
        XCTAssertTrue(p.trusts(a) && p.trusts(b))
        p = p.afterHandshake(b, remoteName: "P2")
        XCTAssertFalse(p.trusts(a))
        XCTAssertNil(p.pendingKey)
    }

    func testPeerRecordJsonMatchesKotlinKeys() throws {
        let json = #"{"name":"Pixel","platform":"android","key":"AAAA","pairedAt":5}"#
        let p = try JSONDecoder().decode(PeerRecord.self, from: Data(json.utf8))
        XCTAssertEqual(p.name, "Pixel")
        XCTAssertNil(p.pendingKey)
    }

    func testPairingURI() {
        let k = KeyPair.generate().publicKey
        let t = Data((0..<16).map { UInt8($0 * 3) })
        let u = PairingURI(key: k, token: t, name: "Alex’s MacBook Air & co")
        let s = u.encode()
        XCTAssertTrue(s.hasPrefix("tether://pair?v=1&k="))
        XCTAssertFalse(s.contains(" "))
        XCTAssertEqual(PairingURI.parse(s), u)
        XCTAssertNil(PairingURI.parse("https://x"))
        XCTAssertNil(PairingURI.parse("tether://pair?v=1&k=short&t=\(B64.encode(t))"))
    }

    func testB64AndFingerprint() {
        let d = Data((0..<32).map { UInt8($0) })
        XCTAssertEqual(B64.decode(B64.encode(d)), d)
        XCTAssertNil(B64.decode("!!!"))
        XCTAssertEqual(fingerprint(d).count, 14)
    }
}

final class MacNotificationTests: XCTestCase {
    private let n = NotifPayload(key: "k", pkg: "com.whatsapp", app: "WhatsApp", title: "Asha", text: "Lunch?", ts: 1)

    func testDefaultsShowBanner() {
        let p = MacNotificationPolicy.present(n, settings: MacNotificationSettings())
        XCTAssertEqual(p, MacPresentation(record: true, banner: true, title: "Asha", body: "Lunch?"))
    }

    func testPerAppHideAndSilent() {
        var s = MacNotificationSettings()
        s.set(MacAppPref(visibility: .show, delivery: .silent), for: "com.whatsapp")
        XCTAssertEqual(MacNotificationPolicy.present(n, settings: s).banner, false)
        XCTAssertEqual(MacNotificationPolicy.present(n, settings: s).record, true)
        s.set(MacAppPref(visibility: .hide, delivery: .banner), for: "com.whatsapp")
        XCTAssertEqual(MacNotificationPolicy.present(n, settings: s).record, false)
        s.set(MacAppPref(), for: "com.whatsapp")
        XCTAssertTrue(s.apps.isEmpty)
    }

    func testPrivacyMode() {
        var s = MacNotificationSettings()
        s.privacyMode = true
        let p = MacNotificationPolicy.present(n, settings: s)
        XCTAssertEqual(p.title, "WhatsApp")
        XCTAssertEqual(p.body, "New notification from WhatsApp")
    }

    func testHistoryDedupesCapsAndClears() {
        var h = NotificationHistory()
        for i in 0..<10 { h.add(NotifPayload(key: "k\(i)", pkg: "p", app: "A", ts: Int64(i)), limit: 5) }
        XCTAssertEqual(h.items.map(\.key), ["k9", "k8", "k7", "k6", "k5"])
        h.add(NotifPayload(key: "k7", pkg: "p", app: "A", title: "new", ts: 99), limit: 5)
        XCTAssertEqual(h.items.first?.title, "new")
        XCTAssertEqual(h.items.count, 5)
        XCTAssertTrue(h.remove(key: "k7"))
        XCTAssertFalse(h.remove(key: "zzz"))
        h.trim(2)
        XCTAssertEqual(h.items.count, 2)
        h.clear()
        XCTAssertTrue(h.items.isEmpty)
        XCTAssertEqual(h.seenApps["p"], "A")
    }

    func testSettingsPersistAndTolerateMissingKeys() throws {
        var s = MacNotificationSettings()
        s.privacyMode = true
        s.historyLimit = 100
        s.set(MacAppPref(visibility: .hide, delivery: .silent), for: "x")
        let back = try JSONDecoder().decode(MacNotificationSettings.self, from: try JSONEncoder().encode(s))
        XCTAssertEqual(back, s)
        XCTAssertEqual(try JSONDecoder().decode(MacNotificationSettings.self, from: Data("{}".utf8)), MacNotificationSettings())
    }
}
