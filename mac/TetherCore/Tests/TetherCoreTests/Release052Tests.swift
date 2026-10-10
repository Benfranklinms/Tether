import XCTest
@testable import TetherCore

final class Release052Tests: XCTestCase {
    private func roundTrip(_ m: Message, file: StaticString = #filePath, line: UInt = #line) throws {
        let back = try MessageCodec.decode(try MessageCodec.encode(m))
        XCTAssertEqual(back, m, file: file, line: line)
    }

    func testRevision6MessagesRoundTrip() throws {
        try roundTrip(.captureReq(CaptureReq(ref: "r", kind: CaptureKinds.photo)))
        try roundTrip(.captureResult(CaptureResultPayload(ref: "r", state: "sending", id: "f", name: "Photo.jpg", pages: 1, via: "wifi")))
        try roundTrip(.findMac(FindMacPayload(id: "f", action: FindActions.ring)))
        try roundTrip(.findMacState(FindMacStatePayload(id: "f", ringing: true)))
        try roundTrip(.hotspotReq(HotspotReq(ref: "h")))
        try roundTrip(.hotspotState(HotspotStatePayload(ref: "h", state: HotspotStates.on, ssid: "Nord", psk: "secret123")))
        let file = ShelfFile(name: "resume.pdf", size: 10, mime: "application/pdf", sha256: "ab", owner: "mac")
        try roundTrip(.shelfSync(ShelfSyncPayload(items: [ShelfItem(id: "1", kind: ShelfKinds.file, title: "resume.pdf", from: "mac",
                                                                   created: 1, updated: 2, order: -1.5, file: file)],
                                                  removed: [ShelfTomb(id: "x", ts: 3)], full: true)))
        try roundTrip(.shelfFileReq(ShelfFileReq(id: "1")))
        XCTAssertTrue(Set(Caps.current).isSuperset(of: Caps6.all))
    }

    func testDecodesKotlinShapedJson() throws {
        // Kotlin encodes defaults; an older/newer peer may omit them. Both must decode.
        let full = #"{"items":[{"id":"a","kind":"text","title":"CGPA","body":"8.91","secret":null,"from":"phone","created":1,"#
            + #""updated":2,"order":-1.0,"file":null}],"removed":[],"full":true}"#
        var d = Data([0xE9]) + withUnsafeBytes(of: UInt32(full.utf8.count).bigEndian) { Data($0) }
        d.append(Data(full.utf8))
        guard case .shelfSync(let p) = try MessageCodec.decode(d) else { return XCTFail("not shelf") }
        XCTAssertEqual(p.items.first?.body, "8.91")
        let sparse = #"{"items":[{"id":"a","kind":"text","title":"x","from":"mac","created":1,"updated":2,"order":0}]}"#
        var e = Data([0xE9]) + withUnsafeBytes(of: UInt32(sparse.utf8.count).bigEndian) { Data($0) }
        e.append(Data(sparse.utf8))
        guard case .shelfSync(let q) = try MessageCodec.decode(e) else { return XCTFail("not shelf") }
        XCTAssertEqual(q.items.first?.body, "")
        XCTAssertFalse(q.full)
    }

    func testHotspotDescriptionHidesPassword() {
        XCTAssertFalse(HotspotStatePayload(state: "on", ssid: "N", psk: "hunter22").description.contains("hunter22"))
    }

    // MARK: Shelf

    func testShelfDetectMatchesSharedVectors() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("../../../../protocol/vectors/shelf-detect.json")
        let cases = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [[String: String]] ?? []
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for c in cases {
            let text = c["text"] ?? ""
            let g = ShelfDetect.classify(text)
            XCTAssertEqual(g.kind, c["kind"], text)
            XCTAssertEqual(g.title, c["title"], text)
            XCTAssertEqual(g.body, c["body"], text)
            if let s = c["secret"] { XCTAssertEqual(g.secret, s, text) }
        }
    }

    private func item(_ id: String, _ t: Int64, from: String = "mac") -> ShelfItem {
        ShelfItem(id: id, kind: ShelfKinds.text, title: "t\(id)", body: "b\(t)", from: from, created: t, updated: t, order: 0)
    }

    func testShelfAddMoveRemoveRestoreAndLimit() {
        var s = ShelfState()
        for i in 1...55 { s = s.add(item("i\(i)", Int64(i))) }
        XCTAssertEqual(s.items.count, 50)
        XCTAssertEqual(s.ordered.first?.id, "i55")
        s = ShelfState().add(item("a", 1)).add(item("b", 2)).add(item("c", 3))
        XCTAssertEqual(s.ordered.map(\.id), ["c", "b", "a"])
        s = s.move("a", to: 0, now: 10)
        XCTAssertEqual(s.ordered.map(\.id), ["a", "c", "b"])
        let b = s.items.first { $0.id == "b" }!
        s = s.remove("b", now: 20)
        XCTAssertEqual(s.ordered.map(\.id), ["a", "c"])
        s = s.restore(b, now: 21)
        XCTAssertEqual(Set(s.ordered.map(\.id)), ["a", "b", "c"])
        XCTAssertTrue(s.tombs.isEmpty)
    }

    func testShelfMergeTombstonesAndTimestamps() {
        let mac = ShelfState().add(item("a", 5)).add(item("b", 6))
        let phone = ShelfState(items: [item("b", 4, from: "phone")], tombs: [ShelfTomb(id: "a", ts: 7)])
        let merged = mac.merge(phone.snapshot())
        XCTAssertEqual(merged.items.map(\.id), ["b"])
        XCTAssertEqual(merged.items.first?.body, "b6")
        let back = merged.merge(ShelfSyncPayload(items: [item("a", 9)]))
        XCTAssertTrue(back.items.contains { $0.id == "a" })
    }

    func testOfflineEditsConverge() {
        var rng = SystemRandomNumberGenerator()
        for round in 0..<150 {
            var mac = ShelfState()
            var phone = ShelfState()
            var clock: Int64 = 1
            for _ in 0..<30 {
                let onMac = Bool.random(using: &rng)
                var s = onMac ? mac : phone
                let ids = s.items.map(\.id)
                switch Int.random(in: 0..<4, using: &rng) {
                case 0, 1: s = s.add(item("r\(round)x\(Int.random(in: 0..<12, using: &rng))", clock, from: onMac ? "mac" : "phone"))
                case 2: if let id = ids.randomElement(using: &rng) { s = s.remove(id, now: clock) }
                default:
                    if let id = ids.randomElement(using: &rng) {
                        s = s.move(id, to: Int.random(in: 0..<ids.count, using: &rng), now: clock)
                    }
                }
                clock += 1
                if onMac { mac = s } else { phone = s }
                if Int.random(in: 0..<5, using: &rng) == 0 {
                    let m = mac
                    mac = mac.merge(phone.snapshot())
                    phone = phone.merge(m.snapshot())
                }
            }
            XCTAssertEqual(mac.merge(phone.snapshot()).ordered, phone.merge(mac.snapshot()).ordered)
        }
    }

    func testShelfVaultRoundTripAndTamper() throws {
        let key = ShelfVault.newKey()
        let s = ShelfState().add(ShelfState.item(from: "WIFI:S:Lab;T:WPA;P:abcdefgh;;", id: "w", from: "mac", now: 1))
        var sealed = try ShelfVault.seal(s, key: key)
        XCTAssertNil(String(data: sealed, encoding: .utf8)?.range(of: "abcdefgh"))
        XCTAssertEqual(try ShelfVault.open(sealed, key: key), s)
        sealed[sealed.count - 1] ^= 1
        XCTAssertThrowsError(try ShelfVault.open(sealed, key: key))
        XCTAssertThrowsError(try ShelfVault.open(try ShelfVault.seal(s, key: key), key: ShelfVault.newKey()))
    }

    func testShelfActionsAgeAndUrls() {
        XCTAssertEqual(ShelfDetect.action(ShelfKinds.address), "Map")
        XCTAssertEqual(ShelfDetect.action(ShelfKinds.snippet), "Copy")
        XCTAssertEqual(ShelfAge.label(from: "phone", updatedMs: 0, nowMs: 120_000), "From phone · 2 min")
        XCTAssertEqual(ShelfAge.label(from: "mac", updatedMs: 0, nowMs: 3 * 3_600_000), "From Mac · 3 h")
        let link = ShelfState.item(from: "forms.gle/x7Tq2", id: "l", from: "mac", now: 1)
        XCTAssertEqual(ShelfDetect.linkURL(link)?.absoluteString, "https://forms.gle/x7Tq2")
        let addr = ShelfState.item(from: "Govt. Model Engineering College\nThrikkakara, Kochi 682021", id: "a", from: "mac", now: 1)
        XCTAssertEqual(ShelfDetect.mapsURL(addr)?.host, "maps.apple.com")
    }

    // MARK: Marquee, video, alarm, direction

    func testMarqueeHitsInOrderAndThreshold() {
        let frames = ["a": Box(x: 0, y: 0, w: 10, h: 10), "b": Box(x: 20, y: 0, w: 10, h: 10), "c": Box(x: 0, y: 20, w: 10, h: 10)]
        let r = MarqueeSelection.rect(from: (25, 5), to: (5, 25))
        XCTAssertEqual(MarqueeSelection.hits(r, frames: frames, order: ["a", "b", "c"]), ["a", "b", "c"])
        let small = MarqueeSelection.rect(from: (12, 12), to: (18, 18))
        XCTAssertEqual(MarqueeSelection.hits(small, frames: frames, order: ["a", "b", "c"]), [])
        XCTAssertFalse(MarqueeSelection.isDrag(from: (0, 0), to: (2, 2)))
        XCTAssertTrue(MarqueeSelection.isDrag(from: (0, 0), to: (5, 0)))
        XCTAssertTrue(MarqueeSelection.startsOnItem(5, 5, frames: frames))
        XCTAssertFalse(MarqueeSelection.startsOnItem(15, 15, frames: frames))
    }

    private func rec(_ status: TransferStatus, _ done: Int64, via: Transport? = .WIFI, error: String? = nil,
                     ref: String? = nil) -> TransferRecord {
        TransferRecord(id: "t", direction: .IN, name: "v.mp4", size: 200, sha256: "", mime: "video/mp4", status: status,
                       transferred: done, error: error, createdAt: 0, ref: ref, via: via, kind: FileKinds5.fs)
    }

    func testVideoPlaybackStates() {
        // The 0.5.1 bug: the viewer only ever showed the still thumbnail. Now a video goes
        // idle → requesting → fetching (Wi-Fi or Bluetooth) → ready(path) and plays.
        XCTAssertEqual(VideoPlayback.state(cachedPath: nil,
                                           record: nil, requested: false), .idle)
        XCTAssertEqual(VideoPlayback.state(cachedPath: nil,
                                           record: nil, requested: true), .requesting)
        XCTAssertEqual(VideoPlayback.state(cachedPath: nil,
                                           record: rec(.ACTIVE, 50), requested: true), .fetching(fraction: 0.25, wifi: true))
        XCTAssertEqual(VideoPlayback.state(cachedPath: nil,
                                           record: rec(.ACTIVE, 100, via: .BLE), requested: true),
                       .fetching(fraction: 0.5, wifi: false))
        XCTAssertEqual(VideoPlayback.state(cachedPath: nil,
                                           record: rec(.WAITING, 0, via: nil, error: "Waiting for Wi-Fi"), requested: true),
                       .waitingForWifi)
        XCTAssertEqual(VideoPlayback.state(cachedPath: nil,
                                           record: rec(.DONE, 200, ref: "/c/v.mp4"), requested: true), .ready(path: "/c/v.mp4"))
        XCTAssertEqual(VideoPlayback.state(cachedPath: "/c/old.mp4", record: nil, requested: false), .ready(path: "/c/old.mp4"))
        XCTAssertEqual(VideoPlayback.state(cachedPath: nil,
                                           record: rec(.FAILED, 0, error: "checksum"), requested: true), .failed("checksum"))
        XCTAssertEqual(VideoPlayback.fetching(fraction: 0.425, wifi: true).label, "43% · Wi-Fi")
        XCTAssertEqual(VideoPlayback.folderName(for: "123/45"), "v-123_45")
        XCTAssertEqual(VideoPlayback.cachedFile(in: [(".DS_Store", 6), ("v.mp4", 199), ("v 2.mp4", 200)], expectedSize: 200), "v 2.mp4")
        XCTAssertNil(VideoPlayback.cachedFile(in: [("v.mp4", 199)], expectedSize: 200))
    }

    func testAlarmToneIsAValidWav() {
        let d = AlarmTone.wav(seconds: 1)
        XCTAssertEqual(String(data: d.prefix(4), encoding: .ascii), "RIFF")
        XCTAssertEqual(String(data: d.subdata(in: 8..<12), encoding: .ascii), "WAVE")
        XCTAssertEqual(d.count, 44 + AlarmTone.sampleRate * 2)
        XCTAssertTrue(d.dropFirst(44).contains { $0 != 0 })
    }

    func testPageDirectionAndTexts() {
        XCTAssertEqual(PageDirection.between(1, 4), 1)
        XCTAssertEqual(PageDirection.between(4, 1), -1)
        XCTAssertEqual(CaptureText.insertPlan(accessibility: true, frontmostIsTether: false), .paste)
        XCTAssertEqual(CaptureText.insertPlan(accessibility: false, frontmostIsTether: false), .desktop)
        XCTAssertEqual(CaptureText.describe(CaptureReasons.cancelled), "Cancelled on the phone")
        XCTAssertTrue(HotspotText.describe(HotspotReasons.noService).contains("Tether control"))
        XCTAssertEqual(HotspotText.status(HotspotStates.on, reason: nil, joining: true), "Joining…")
    }

    func testFastPathRetryUpgradesOnNetworkChange() {
        var r = FastPathRetry()
        XCTAssertFalse(r.due(now: 0, bleReady: true, wifiReady: false))
        r.linkUp(now: 0)
        XCTAssertTrue(r.due(now: 0, bleReady: true, wifiReady: false))
        r.attempted(now: 0)
        XCTAssertEqual(r.nextAt, 4_000)
        r.attempted(now: 4_000)
        XCTAssertEqual(r.nextAt, 12_000)
        for _ in 0..<10 { r.attempted(now: 20_000) }
        XCTAssertEqual(r.nextAt, 80_000)
        // The Mac joins the phone's hotspot (NWPathMonitor fires) at 30 s.
        r.networkChanged(now: 30_000)
        XCTAssertFalse(r.due(now: 30_500, bleReady: true, wifiReady: false))
        XCTAssertTrue(r.due(now: 31_200, bleReady: true, wifiReady: false))
        r.connected()
        XCTAssertFalse(r.due(now: 99_000, bleReady: true, wifiReady: true))
        r.wifiLost(now: 100_000)
        XCTAssertTrue(r.due(now: 101_200, bleReady: true, wifiReady: false))
        r.linkDown()
        XCTAssertFalse(r.due(now: 200_000, bleReady: false, wifiReady: false))
        XCTAssertEqual(FastPathRetry.rank(["192.168.29.142", "10.183.42.1"], local: ["10.183.42.77"]), ["10.183.42.1", "192.168.29.142"])
    }

    func testUpdatePopupPolicy() {
        let now: Int64 = 1_000_000_000
        XCTAssertTrue(UpdatePrompt.shouldShow(latest: "0.5.3", current: "0.5.2", skipped: nil, snoozedUntil: 0, now: now))
        XCTAssertFalse(UpdatePrompt.shouldShow(latest: "0.5.2", current: "0.5.2", skipped: nil, snoozedUntil: 0, now: now))
        XCTAssertFalse(UpdatePrompt.shouldShow(latest: "0.5.3", current: "0.5.2", skipped: "0.5.3", snoozedUntil: 0, now: now))
        XCTAssertFalse(UpdatePrompt.shouldShow(latest: "0.5.3", current: "0.5.2", skipped: nil, snoozedUntil: now + 1, now: now))
        XCTAssertTrue(UpdatePrompt.shouldCheck(lastCheck: now, now: now, launch: true, foreground: false))
        XCTAssertFalse(UpdatePrompt.shouldCheck(lastCheck: now - 5 * 3_600_000, now: now, launch: false, foreground: true))
        XCTAssertTrue(UpdatePrompt.shouldCheck(lastCheck: now - 6 * 3_600_000, now: now, launch: false, foreground: true))
        XCTAssertTrue(UpdatePrompt.showWhatsNew(current: "0.5.2", seen: "0.5.1"))
        XCTAssertFalse(UpdatePrompt.showWhatsNew(current: "0.5.2", seen: nil))
        let notes = "## What's new in 0.5.2\n\n- Motion everywhere\n- Shelf\n\n### Mac\n- Videos play\n\n## Downloads\n- Tether-0.5.2.apk"
        XCTAssertEqual(UpdatePrompt.changelog(notes), "- Motion everywhere\n- Shelf\n\n### Mac\n- Videos play")
        XCTAssertEqual(UpdatePrompt.changelog("Fixes\n\n## Downloads\n- x"), "Fixes")
    }
}
