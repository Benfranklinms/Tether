import Foundation
import XCTest
@testable import TetherCore

final class Redesign053Tests: XCTestCase {
    private let zone = TimeZone(identifier: "Asia/Kolkata")!

    private func at(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int) -> Int64 {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = zone
        let date = c.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi))!
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    private var now: Int64 { at(2026, 10, 11, 8, 45) }
    private let kb: (Int64) -> String = { "\($0 / 1000) KB" }

    private func item(_ kind: String, _ title: String, body: String = "", created: Int64? = nil, file: ShelfFile? = nil,
                      secret: String? = nil) -> ShelfItem {
        let t = created ?? now
        return ShelfItem(id: UUID().uuidString, kind: kind, title: title, body: body, secret: secret, from: "phone",
                         created: t, updated: t, order: 0, file: file)
    }

    func testTimeLabelsMatchKotlin() {
        XCTAssertEqual(When.short(now - 20_000, now: now, zone: zone), "now")
        XCTAssertEqual(When.short(now - 2 * 60_000, now: now, zone: zone), "2m")
        XCTAssertEqual(When.short(now - 61 * 60_000, now: now, zone: zone), "1h")
        XCTAssertEqual(When.short(at(2026, 10, 10, 23, 0), now: now, zone: zone), "Yesterday")
        XCTAssertEqual(When.short(at(2026, 10, 9, 21, 14), now: now, zone: zone), "Fri")
        XCTAssertEqual(When.short(at(2026, 10, 2, 9, 0), now: now, zone: zone), "Oct 2")
        XCTAssertEqual(When.clock(at(2026, 10, 11, 8, 42), now: now, zone: zone), "08:42")
        XCTAssertEqual(When.section(at(2026, 10, 10, 23, 59), now: now, zone: zone), "Earlier")
    }

    func testBadgesAndKinds() {
        XCTAssertEqual(ShelfMeta.badge(mime: "application/pdf", name: "Set 15.pdf"), "PDF")
        XCTAssertEqual(ShelfMeta.badge(mime: nil, name: "PES_quiz_answer_script.docx"), "DOC")
        XCTAssertEqual(ShelfMeta.badge(mime: nil, name: "tether-v0.5.2-debug.zip"), "ZIP")
        XCTAssertEqual(ShelfMeta.badge(mime: nil, name: "noext"), "FILE")
        XCTAssertEqual(ShelfMeta.kind(mime: nil, name: "a.docx"), "Word")
        XCTAssertEqual(ShelfMeta.kind(mime: nil, name: "IMG_1.jpg"), "JPEG")
        XCTAssertEqual(ShelfMeta.kind(mime: "image/png", name: "s.png"), "PNG")
        XCTAssertEqual(ShelfMeta.kind(mime: nil, name: "x.zip"), "Archive")
        XCTAssertEqual(ShelfMeta.kind(mime: "application/pdf", name: "a.pdf"), "PDF")
    }

    func testRowLines() {
        let pdf = ShelfFile(name: "Set.pdf", size: 454_000, mime: "application/pdf", sha256: "0", owner: "mac")
        XCTAssertEqual(ShelfMeta.lines(item(ShelfKinds.file, "Set.pdf", file: pdf), size: kb).detail, "PDF · 454 KB")
        XCTAssertEqual(ShelfMeta.lines(item(ShelfKinds.file, "Set.pdf", file: pdf), size: kb, missing: true).detail,
                       "PDF · 454 KB · Available when connected")
        XCTAssertEqual(ShelfMeta.lines(item(ShelfKinds.link, "Titan", body: "https://forms.gle/x7Tq2"), size: kb).detail, "forms.gle")
        XCTAssertEqual(ShelfMeta.lines(item(ShelfKinds.wifi, "MEC_Lab_5G", secret: "pw"), size: kb).detail, "Wi-Fi · •••••••••")
        let s = ShelfMeta.lines(item(ShelfKinds.snippet, "adb mdns services", body: "adb mdns services\nadb connect 1.2.3.4"), size: kb)
        XCTAssertEqual(s.title, "adb mdns services")
        XCTAssertEqual(s.detail, "Snippet · 2 lines")
        let a = ShelfMeta.lines(item(ShelfKinds.address, "Govt. Model Engineering College",
                                     body: "Govt. Model Engineering College\nThrikkakara, Kochi"), size: kb)
        XCTAssertEqual(a.detail, "Thrikkakara, Kochi")
        XCTAssertEqual(ShelfMeta.host("https://www.forms.gle/x?y"), "forms.gle")
    }

    func testSections() {
        let t1 = item(ShelfKinds.text, "a", created: now - 60_000)
        let t2 = item(ShelfKinds.text, "b", created: now - 120_000)
        let old = item(ShelfKinds.text, "c", created: now - 2 * 86_400_000)
        XCTAssertEqual(ShelfMeta.sections([t1, t2, old], now: now, zone: zone), [0: "Today", 2: "Earlier"])
        XCTAssertEqual(ShelfMeta.sections([t1, old, t2], now: now, zone: zone), [:])
        XCTAssertEqual(ShelfMeta.sections([], now: now, zone: zone), [:])
    }

    func testMacStatusCodecAndBatteryGate() throws {
        XCTAssertTrue(Caps.current.contains(Caps7.macStatus))
        let s = MacStatusPayload(battery: 82, charging: true,
                                 media: MacMediaPayload(active: true, title: "Nights", artist: "Frank Ocean", app: "Music",
                                                        playing: true, durationMs: 307_000, positionMs: 102_000), ts: 5)
        guard case .macStatus(let back) = try MessageCodec.decode(try MessageCodec.encode(.macStatus(s))) else {
            return XCTFail("type")
        }
        XCTAssertEqual(back, s)
        // What the Kotlin phone sends (defaults encoded) and a bare request both decode.
        let req = try JSONDecoder().decode(MacStatusReq.self, from: Data("{}".utf8))
        XCTAssertFalse(req.media)
        guard case .macMediaCmd(let c) = try MessageCodec.decode(try MessageCodec.encode(.macMediaCmd(MacMediaCmd(cmd: "next")))) else {
            return XCTFail("type")
        }
        XCTAssertEqual(c.cmd, MacMediaCmds.next)
        var gate = MacBatteryGate()
        XCTAssertTrue(gate.shouldSend(level: 82, charging: true))
        XCTAssertFalse(gate.shouldSend(level: 82, charging: true))
        XCTAssertTrue(gate.shouldSend(level: 82, charging: false))
        XCTAssertFalse(gate.shouldSend(level: nil, charging: nil))
        gate.reset()
        XCTAssertTrue(gate.shouldSend(level: 82, charging: false))
    }
}

final class NotificationClearTests: XCTestCase {
    private func note(_ key: String, _ app: String = "Messages") -> NotifPayload {
        NotifPayload(key: key, pkg: "com.example." + app.lowercased(), app: app, title: "t", text: "x", ts: 1)
    }

    func testPlanWithdrawsEveryBannerAndDismissesOnPhoneOnlyWhenSynced() {
        var h = NotificationHistory()
        for k in ["a", "b", "c"] { h.add(note(k), limit: 50) }
        let synced = NotificationClear.plan(h, dismissSync: true)
        XCTAssertEqual(synced.withdraw, ["c", "b", "a"])
        XCTAssertEqual(synced.dismissOnPhone, ["c", "b", "a"])
        let local = NotificationClear.plan(h, dismissSync: false)
        XCTAssertEqual(local.withdraw, ["c", "b", "a"])
        XCTAssertTrue(local.dismissOnPhone.isEmpty)
        XCTAssertEqual(NotificationClear.plan(NotificationHistory(), dismissSync: true), .init(withdraw: [], dismissOnPhone: []))
    }

    func testConfirmationOnlyForLargeLists() {
        XCTAssertFalse(NotificationClear.needsConfirmation(3))
        XCTAssertFalse(NotificationClear.needsConfirmation(9))
        XCTAssertTrue(NotificationClear.needsConfirmation(10))
        XCTAssertTrue(NotificationClear.needsConfirmation(120))
    }
}
