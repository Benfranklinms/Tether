import XCTest
@testable import TetherCore

final class Features4CodecTests: XCTestCase {
    private let entry = CallLogEntry(id: 41, number: "+919847012345", name: "Asha", type: CallLogTypes.missed, date: 1_700_000_000_000,
                                     sub: 2, isNew: true)

    func testRevision4RoundTrips() throws {
        let all: [Message] = [
            .callDial(CallDialPayload(ref: "r1", number: "+919847012345")), .callDial(CallDialPayload(ref: "r2", number: "*121#", sub: 2)),
            .callDialResult(CallDialResultPayload(ref: "r1", ok: true, via: "telecom")),
            .callDialResult(CallDialResultPayload(ref: "r2", ok: false, reason: "needs_permission")),
            .callLive(CallLivePayload(id: "c1", dir: CallDirs.incoming, state: CallLiveStates.ringing, number: "+91", name: "Asha",
                                      canAnswer: true, canEnd: true, ts: 5)),
            .callLive(CallLivePayload(id: "c2", dir: CallDirs.outgoing, state: CallLiveStates.active, number: "121", since: 10,
                                      speaker: true, muted: true, canEnd: true, canSpeaker: true, canMute: true, sub: 1, ts: 11,
                                      reason: "not_allowed")),
            .callLogReq(CallLogReq()), .callLogReq(CallLogReq(limit: 30, before: 9)),
            .callLog(CallLogPayload(calls: [entry, CallLogEntry(id: 42, number: "1", type: CallLogTypes.outgoing, date: 3, dur: 75)],
                                    more: true)),
            .callLog(CallLogPayload(calls: [], needs: "calllog")), .callLog(CallLogPayload(calls: [], before: 99)),
            .contactSearch(ContactSearchPayload(query: "ash", limit: 10)),
            .contacts(ContactsPayload(query: "ash", contacts: [ContactInfo(name: "Asha", numbers: [ContactNumber(n: "+91", label: "Mobile"),
                                                                                                    ContactNumber(n: "0484")])])),
            .contacts(ContactsPayload(query: "x", contacts: [], needs: "contacts")),
            .notifAccess(NotifAccessPayload(redacting: true, sdk: 36, oem: "OnePlus", ts: 7)),
            .notifAccess(NotifAccessPayload(redacting: false, granted: true, sdk: 35, ts: 8)),
            .callAction(CallActionPayload(id: "c1", action: CallActions4.answer, speaker: true)),
            .callAction(CallActionPayload(id: "c1", action: CallActions4.mute, on: true)),
        ]
        for m in all { XCTAssertEqual(try MessageCodec.decode(try MessageCodec.encode(m)), m) }
        XCTAssertEqual(all.map(\.type).filter { $0 != 0xA1 }.sorted(),
                       [0x45, 0x45, 0xA2, 0xA2, 0xA3, 0xA3, 0xA4, 0xA4, 0xA5, 0xA5, 0xA6, 0xA6, 0xA6, 0xA7, 0xA8, 0xA8])
        XCTAssertTrue(Caps.current.contains(Caps4.dial) && Caps.current.contains(Caps4.callControl))
        XCTAssertEqual(Caps4.all, ["dial", "callctl", "calllog", "notifaccess"])
    }

    func testRevision3CallActionHeaderIsUnchanged() throws {
        // A 0.3 phone only knows {id, action}: nil fields must not appear on the wire.
        let d = try MessageCodec.encode(.callAction(CallActionPayload(id: "c1", action: "decline")))
        XCTAssertEqual(String(data: d.dropFirst(5), encoding: .utf8), #"{"action":"decline","id":"c1"}"#)
    }

    func testKotlinShapedAndMinimalHeadersDecode() throws {
        let kotlin = #"{"id":"c","dir":"out","state":"active","number":"1","since":5,"speaker":true,"muted":false,"held":false,"#
            + #""canAnswer":false,"canEnd":true,"canSpeaker":true,"canMute":false,"canHold":false,"ts":9}"#
        guard case .callLive(let l) = try MessageCodec.decode(frame(0xA4, kotlin)) else { return XCTFail("type") }
        XCTAssertTrue(l.speaker && l.canEnd && l.isOffhook)
        XCTAssertEqual(l.caller, "1")
        guard case .callLive(let m) = try MessageCodec.decode(frame(0xA4, #"{"id":"c","state":"ringing","ts":1}"#)) else {
            return XCTFail("type")
        }
        XCTAssertEqual(m.dir, CallDirs.incoming)
        XCTAssertFalse(m.canAnswer || m.canHold)
        XCTAssertEqual(m.caller, "Unknown caller")
        guard case .callLog(let log) = try MessageCodec.decode(frame(0xA6, #"{"calls":[{"id":1,"date":2}],"extra":true}"#)) else {
            return XCTFail("type")
        }
        XCTAssertEqual(log.calls.first?.type, CallLogTypes.other)
        XCTAssertFalse(log.more)
        guard case .notifAccess(let a) = try MessageCodec.decode(frame(0x45, #"{"redacting":true,"sdk":35,"ts":1}"#)) else {
            return XCTFail("type")
        }
        XCTAssertNil(a.granted)
        XCTAssertEqual(a.oem, "")
    }

    func testFutureTypesStayUnknown() throws {
        XCTAssertEqual(try MessageCodec.decode(frame(0xA9, "{}")), .unknown(type: 0xA9))
        XCTAssertEqual(try MessageCodec.decode(frame(0x46, "{}")), .unknown(type: 0x46))
    }

    private func frame(_ type: UInt8, _ header: String) -> Data {
        let h = Data(header.utf8)
        var d = Data([type])
        d.appendU32(UInt32(h.count))
        d.append(h)
        return d
    }
}

final class CallingLogicTests: XCTestCase {
    func testDialable() {
        XCTAssertEqual(Dialable.clean(" +91 98470-12345 "), "+919847012345")
        XCTAssertEqual(Dialable.clean("(0484) 234.5678"), "04842345678")
        XCTAssertEqual(Dialable.clean("*121#"), "*121#")
        XCTAssertEqual(Dialable.clean("1800 1234 p1w2"), "18001234,1;2")
        XCTAssertNil(Dialable.clean(""))
        XCTAssertNil(Dialable.clean("call me"))
        XCTAssertNil(Dialable.clean("1+1"))
        XCTAssertNil(Dialable.clean("++1"))
        XCTAssertNil(Dialable.clean(",,,"))
        XCTAssertNil(Dialable.clean("١٢٣"))
        XCTAssertNil(Dialable.clean(String(repeating: "1", count: 41)))
        XCTAssertEqual(Dialable.digits("+91 98470-12345"), "919847012345")
    }

    func testTelURLs() {
        XCTAssertEqual(TelURL.number(from: "tel:+91%2098470%2012345"), "+919847012345")
        XCTAssertEqual(TelURL.number(from: "TEL:+1-201-555-0123"), "+12015550123")
        XCTAssertEqual(TelURL.number(from: "tel://9847012345"), "9847012345")
        XCTAssertEqual(TelURL.number(from: "tel:863-1234;phone-context=+1-914-555"), "8631234")
        XCTAssertEqual(TelURL.number(from: "tel:+1-201-555-0123;ext=42"), "+12015550123,42")
        XCTAssertEqual(TelURL.number(from: "callto:0484 2345678"), "04842345678")
        XCTAssertEqual(TelURL.number(from: "telprompt:121"), "121")
        XCTAssertEqual(TelURL.number(from: "tel:*121%23"), "*121#")
        XCTAssertNil(TelURL.number(from: "mailto:a@b.c"))
        XCTAssertNil(TelURL.number(from: "tel:"))
        XCTAssertNil(TelURL.number(from: "tel:hello"))
        XCTAssertNil(TelURL.number(from: "no-scheme"))
    }

    func testMissedBadge() {
        let log = [
            CallLogEntry(id: 1, number: "1", type: CallLogTypes.missed, date: 100),
            CallLogEntry(id: 2, number: "2", type: CallLogTypes.incoming, date: 200),
            CallLogEntry(id: 3, number: "3", type: CallLogTypes.missed, date: 300),
        ]
        XCTAssertEqual(MissedCalls.badge(log: log, liveMissed: [], seenUntil: 0), 2)
        XCTAssertEqual(MissedCalls.badge(log: log, liveMissed: [], seenUntil: 150), 1)
        // A missed-call event after the last refresh counts until the log catches up…
        XCTAssertEqual(MissedCalls.badge(log: log, liveMissed: [400], seenUntil: 150), 2)
        // …and is not double-counted once the log has it.
        let refreshed = log + [CallLogEntry(id: 4, number: "4", type: CallLogTypes.missed, date: 400)]
        XCTAssertEqual(MissedCalls.badge(log: refreshed, liveMissed: [400], seenUntil: 150), 2)
        let seen = MissedCalls.seen(log: refreshed, liveMissed: [400], previous: 150)
        XCTAssertEqual(seen, 400)
        XCTAssertEqual(MissedCalls.badge(log: refreshed, liveMissed: [400], seenUntil: seen), 0)
        XCTAssertEqual(MissedCalls.seen(log: [], liveMissed: [], previous: 7), 7)
    }

    func testClock() {
        XCTAssertEqual(CallClock.elapsed(since: 0, phoneNow: 99), "")
        XCTAssertEqual(CallClock.elapsed(since: 1000, phoneNow: 8000), "0:07")
        XCTAssertEqual(CallClock.elapsed(since: 1000, phoneNow: 755_000), "12:34")
        XCTAssertEqual(CallClock.elapsed(since: 1000, phoneNow: 3_724_000), "1:02:03")
        XCTAssertEqual(CallClock.elapsed(since: 5000, phoneNow: 1000), "0:00")
        XCTAssertEqual(CallClock.duration(0), "")
        XCTAssertEqual(CallClock.duration(45), "45 s")
        XCTAssertEqual(CallClock.duration(75), "1 min 15 s")
        XCTAssertEqual(CallClock.duration(120), "2 min")
        XCTAssertEqual(CallClock.duration(3720), "1 h 2 min")
    }

    func testSearch() {
        XCTAssertTrue(CallSearch.matches("", name: nil, numbers: []))
        XCTAssertTrue(CallSearch.matches("asha", name: "Ásha Menon", numbers: []))
        XCTAssertTrue(CallSearch.matches("98470", name: nil, numbers: ["+91 98470 12345"]))
        XCTAssertFalse(CallSearch.matches("98", name: nil, numbers: ["+91 98470 12345"]))
        XCTAssertFalse(CallSearch.matches("zz", name: "Asha", numbers: ["1"]))
    }
}

final class PanelLayoutTests: XCTestCase {
    func testDefaultsAndMore() {
        let l = PanelLayout()
        XCTAssertEqual(l.pinned, [.clipboard, .file, .messages])
        XCTAssertEqual(l.more, [.downloads, .findPhone, .screen])
        XCTAssertFalse(l.moreExpanded)
        XCTAssertFalse(l.compactPlayer)
    }

    func testPinSwapsAndReorders() {
        var l = PanelLayout()
        l.pin(.screen, at: 1)
        XCTAssertEqual(l.pinned, [.clipboard, .screen, .messages])
        XCTAssertEqual(l.more, [.file, .downloads, .findPhone])
        l.pin(.messages, at: 0) // already pinned: swaps
        XCTAssertEqual(l.pinned, [.messages, .screen, .clipboard])
        l.move(from: 2, to: 0)
        XCTAssertEqual(l.pinned, [.clipboard, .messages, .screen])
        l.pin(.findPhone, at: 7) // out of range: ignored
        l.move(from: 0, to: 9)
        XCTAssertEqual(l.pinned, [.clipboard, .messages, .screen])
        l.reset()
        XCTAssertEqual(l.pinned, PanelLayout.defaultPinned)
    }

    func testNormalizesBadInput() {
        XCTAssertEqual(PanelLayout(pinned: [.screen, .screen]).pinned, [.screen, .clipboard, .file])
        XCTAssertEqual(PanelLayout(pinned: []).pinned, PanelLayout.defaultPinned)
        XCTAssertEqual(PanelLayout(pinned: PanelTile.allCases).pinned, [.clipboard, .file, .messages])
    }

    func testPersistsAndToleratesUnknownTiles() throws {
        var l = PanelLayout()
        l.pin(.findPhone, at: 2)
        l.moreExpanded = true
        l.compactPlayer = true
        let back = try JSONDecoder().decode(PanelLayout.self, from: JSONEncoder().encode(l))
        XCTAssertEqual(back, l)
        let future = Data(#"{"pinned":["hologram","screen"],"moreExpanded":true}"#.utf8)
        let f = try JSONDecoder().decode(PanelLayout.self, from: future)
        XCTAssertEqual(f.pinned, [.screen, .clipboard, .file])
        XCTAssertTrue(f.moreExpanded)
        XCTAssertFalse(f.compactPlayer)
        XCTAssertEqual(try JSONDecoder().decode(PanelLayout.self, from: Data("{}".utf8)), PanelLayout())
    }
}
