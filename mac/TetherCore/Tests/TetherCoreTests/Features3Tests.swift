import XCTest
@testable import TetherCore

final class Features3CodecTests: XCTestCase {
    private let msg = SmsMessage(id: "s42", thread: 7, addr: "+919847012345", name: "Asha", body: "Running late — 10 min 🚲",
                                 date: 1_700_000_000_000, sub: 2, att: [SmsAttachment(id: "p1", mime: "image/jpeg", w: 640, h: 480)],
                                 read: false)

    func testRevision3RoundTrips() throws {
        let all: [Message] = [
            .smsThreadsReq(SmsThreadsReq()), .smsThreadsReq(SmsThreadsReq(limit: 20, before: 5, query: "asha")),
            .smsThreads(SmsThreadsPayload(threads: [SmsThread(id: 7, addrs: ["1", "2", "3"], names: ["A", nil, "C"], snippet: "hi",
                                                              date: 1, unread: 2, outLast: true)], more: true)),
            .smsThreads(SmsThreadsPayload(threads: [], needs: "sms")),
            .smsThreadReq(SmsThreadReq(thread: 7, limit: 30, before: 9)),
            .smsMessages(SmsMessagesPayload(thread: 7, msgs: [msg], more: true)),
            .smsMessages(SmsMessagesPayload(thread: -1, msgs: [msg], search: "late")),
            .smsSend(SmsSendPayload(ref: "r1", to: ["+91", "12345"], body: "On my way", thread: 7, sub: 1)),
            .smsSendResult(SmsSendResultPayload(ref: "r1", ok: false, reason: "no_service")),
            .smsNew(msg), .smsSearch(SmsSearchPayload(query: "late", limit: 25)),
            .sims([SimInfo(id: 1, slot: 0, name: "Jio", number: "+91"), SimInfo(id: 2, slot: 1, name: "Airtel")]),
            .callState(CallStatePayload(id: "c1", state: CallStates.ringing, number: "+91", name: "Asha", ts: 5)),
            .callState(CallStatePayload(id: "c1", state: CallStates.missed, ts: 6, reason: "not_allowed")),
            .callAction(CallActionPayload(id: "c1", action: CallActions.decline)),
            .nowPlaying(NowPlayingPayload(active: true, pkg: "com.spotify.music", app: "Spotify", title: "Song", artist: "A",
                                          album: "B", durationMs: 200_000, positionMs: 12_000, playing: true, speed: 1,
                                          canSeek: true, canNext: true, volume: 0.5, art: "abcd", ts: 9)),
            .nowPlaying(NowPlayingPayload(active: false, ts: 1)),
            .mediaCmd(MediaCmdPayload(cmd: MediaCmds.seek, pos: 61_000)), .mediaCmd(MediaCmdPayload(cmd: MediaCmds.volume, vol: 0.75)),
            .nowPlayingArt(hash: "abcd", data: Data((0..<4000).map { UInt8($0 % 251) })),
            .rssi(rssi: -63, ts: 10), .proximityCfg(ProximityCfgPayload(on: true, intervalMs: 1500)),
        ]
        for m in all { XCTAssertEqual(try MessageCodec.decode(try MessageCodec.encode(m)), m) }
        XCTAssertEqual(Message.sims([]).type, 0x98)
        XCTAssertEqual(Message.rssi(rssi: 0, ts: 0).type, 0xD0)
    }

    func testKotlinShapedHeadersDecode() throws {
        // kotlinx.serialization with encodeDefaults writes every default; Swift must also accept them missing.
        let kotlin = #"{"msg":{"id":"s1","thread":3,"addr":"123","name":null,"body":"hi","date":5,"out":true,"#
            + #""status":"sent","att":[],"read":true}}"#
        guard case .smsNew(let m) = try MessageCodec.decode(frame(0x96, kotlin)) else { return XCTFail("type") }
        XCTAssertTrue(m.out)
        XCTAssertEqual(m.status, "sent")
        guard case .smsNew(let n) = try MessageCodec.decode(frame(0x96, #"{"msg":{"id":"s1","thread":3,"addr":"1","date":5}}"#)) else {
            return XCTFail("type")
        }
        XCTAssertEqual(n.body, "")
        XCTAssertTrue(n.read)
        let np = #"{"active":true,"pkg":"p","app":"A","title":"T","artist":"","album":"","durationMs":1000,"positionMs":10,"#
            + #""playing":true,"speed":1.0,"canSeek":true,"canNext":false,"canPrev":false,"volume":0.4,"ts":3,"future":1}"#
        guard case .nowPlaying(let p) = try MessageCodec.decode(frame(0xB0, np)) else { return XCTFail("type") }
        XCTAssertEqual(p.volume ?? 0, 0.4, accuracy: 0.001)
        XCTAssertNil(p.art)
    }

    func testOversizedArtworkIsRefused() throws {
        let big = try MessageCodec.encode(.nowPlayingArt(hash: "h", data: Data(count: MessageCodec.maxArtBytes + 1)))
        XCTAssertThrowsError(try MessageCodec.decode(big))
    }

    func testCapsAndHelpers() {
        XCTAssertTrue(Set(Caps.current).isSuperset(of: Caps3.all))
        XCTAssertEqual(SmsThread(id: 1, addrs: ["1", "2", "3"], names: ["A", nil], date: 0).title, "A, 2 +1")
        XCTAssertEqual(SmsThread(id: 1, addrs: ["+91"], date: 0).title, "+91")
        XCTAssertEqual(SmsAttachment(id: "x", mime: "video/mp4").label, "Video")
        XCTAssertEqual(CallStatePayload(id: "c", state: "ringing", ts: 0).caller, "Unknown caller")
        let p = NowPlayingPayload(active: true, durationMs: 10_000, positionMs: 1000, playing: true, speed: 1, ts: 100)
        XCTAssertEqual(p.position(at: 2100), 3000)
        XCTAssertEqual(p.position(at: 99_000), 10_000)
        var paused = p
        paused.playing = false
        XCTAssertEqual(paused.position(at: 5000), 1000)
    }

    private func frame(_ type: UInt8, _ header: String) -> Data {
        let h = Data(header.utf8)
        var d = Data([type])
        d.appendU32(UInt32(h.count))
        d.append(h)
        return d
    }
}

final class ProximityTests: XCTestCase {
    private func monitor(_ d: ProximityDistance = .medium, away: Int = 10) -> ProximityMonitor {
        ProximityMonitor(config: ProximityConfig(distance: d, awaySeconds: away, hysteresis: 6, alpha: 0.5))
    }

    func testNearStaysNearAndNoiseInTheBandDoesNotFlap() {
        var m = monitor()
        m.reading(-60, now: 0)
        XCTAssertEqual(m.state, .near)
        // Dips into the hysteresis band (-72...-78 for medium) keep the previous state.
        for t in 1...20 { m.reading(t.isMultiple(of: 2) ? -74 : -70, now: Int64(t) * 1000) }
        XCTAssertEqual(m.state, .near)
    }

    func testWalkingAwayLocksOnlyAfterTheDwellTime() {
        var m = monitor(away: 10)
        m.reading(-60, now: 0)
        var t: Int64 = 1000
        while m.state == .near { m.reading(-90, now: t); t += 1000 }
        XCTAssertEqual(m.state, .leaving)
        let leftAt = t - 1000
        m.tick(now: leftAt + 9000)
        XCTAssertEqual(m.state, .leaving)
        m.tick(now: leftAt + 10_000)
        XCTAssertEqual(m.state, .away)
        // Coming back above the threshold is near again.
        for i in 0..<6 { m.reading(-55, now: leftAt + 11_000 + Int64(i) * 1000) }
        XCTAssertEqual(m.state, .near)
    }

    func testBriefDropDoesNotLock() {
        var m = monitor(away: 10)
        m.reading(-60, now: 0)
        for i in 1...4 { m.reading(-95, now: Int64(i) * 1000) }
        XCTAssertEqual(m.state, .leaving)
        for i in 5...10 { m.reading(-55, now: Int64(i) * 1000) }
        XCTAssertEqual(m.state, .near)
        m.tick(now: 60_000)
        XCTAssertEqual(m.state, .near)
    }

    func testLinkLossCountsAsAwayAndBogusReadingsAreIgnored() {
        var m = monitor(away: 5)
        m.reading(0, now: 0)
        m.reading(5, now: 0)
        XCTAssertEqual(m.state, .unknown)
        XCTAssertNil(m.smoothed)
        m.reading(-58, now: 0)
        m.linkLost(now: 1000)
        XCTAssertEqual(m.state, .leaving)
        m.tick(now: 6000)
        XCTAssertEqual(m.state, .away)
        m.reset()
        XCTAssertEqual(m.state, .unknown)
    }

    func testFartherDistanceToleratesWeakerSignal() {
        var near = monitor(.near, away: 0)
        var far = monitor(.far, away: 0)
        for i in 0..<10 {
            near.reading(-75, now: Int64(i) * 1000)
            far.reading(-75, now: Int64(i) * 1000)
        }
        XCTAssertEqual(near.state, .away)
        XCTAssertEqual(far.state, .near)
    }

    func testLockPolicy() {
        var p = LockPolicy()
        XCTAssertFalse(p.shouldLock(state: .away, enabled: false, screenSharing: false))
        XCTAssertFalse(p.shouldLock(state: .away, enabled: true, screenSharing: true))
        XCTAssertTrue(p.shouldLock(state: .away, enabled: true, screenSharing: false))
        XCTAssertFalse(p.shouldLock(state: .away, enabled: true, screenSharing: false), "once per absence")
        XCTAssertFalse(p.shouldLock(state: .leaving, enabled: true, screenSharing: false))
        XCTAssertFalse(p.shouldLock(state: .near, enabled: true, screenSharing: false))
        XCTAssertTrue(p.shouldLock(state: .away, enabled: true, screenSharing: false), "re-armed after near")
    }
}
