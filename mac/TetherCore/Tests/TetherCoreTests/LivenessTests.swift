import Foundation
import XCTest
@testable import TetherCore

/// 0.3.1: link liveness and Messages payloads as real phones produce them.
final class LivenessTests: XCTestCase {
    let loop = Loop()
    var phone: Side!
    var mac: Side!

    override func setUp() {
        (phone, mac) = pairedSides(loop)
    }

    private func tick(_ seconds: Int, every: (Int) -> Void = { _ in }) {
        for s in 0..<seconds {
            loop.now += 1_000
            phone.controller.tick()
            mac.controller.tick()
            every(s)
            loop.drain()
        }
    }

    private func connectWifi(_ wifi: LoopbackWifi) {
        phone.controller.offerWifi(addrs: ["10.0.0.2"], port: 1)
        loop.drain()
        wifi.connect(token: mac.wifiOffers.last!.token)
        loop.drain()
        XCTAssertTrue(phone.controller.wifiReady && mac.controller.wifiReady)
    }

    static func threads(_ n: Int) -> [SmsThread] {
        let words = ["Running late", "OTP 482913 is your code", "ok 👍", "مرحبا كيف حالك", "Your order has shipped 📦", "", "😂😂😂"]
        return (0..<n).map { i in
            let addrs: [String] = i % 17 == 0 ? [""] : i % 5 == 0 ? ["AX-HDFCBK"]
                : i % 9 == 0 ? ["+9198470\(10000 + i)", "+9194470\(20000 + i)", "+9190000\(30000 + i)"] : ["+9198470\(10000 + i)"]
            let names: [String?] = addrs.indices.map { j in (i + j) % 3 == 0 ? nil : "Contact \(i).\(j) ✓" }
            let snippet = String(String(repeating: words[i % words.count] + " ", count: 1 + i % 12).prefix(160))
            return SmsThread(id: Int64(1_000 + i), addrs: addrs, names: names, snippet: snippet,
                             date: 1_760_000_000_000 - Int64(i) * 60_000, unread: i % 4, outLast: i % 2 == 0)
        }
    }

    func testFragmentsAreClampedToTheGattAttributeLimit() {
        XCTAssertEqual(TetherController.clampFragment(514), 512)
        XCTAssertEqual(TetherController.clampFragment(3), 20)
        let ble = LoopbackBle(loop, phone, mac, mtu: 514)
        ble.connect()
        loop.drain()
        let list = Message.smsThreads(SmsThreadsPayload(threads: Self.threads(60), more: true))
        XCTAssertTrue(mac.controller.send(list))
        XCTAssertTrue(phone.controller.send(list))
        loop.drain()
        XCTAssertEqual(phone.messages.last, list)
        XCTAssertEqual(mac.messages.last, list)
        XCTAssertEqual(ble.maxToMac, Fragmentation.maxBleFragment)
        XCTAssertEqual(ble.maxToPhone, Fragmentation.maxBleFragment)
    }

    func testIdleBluetoothAndWifiStayUpForHours() {
        let ble = LoopbackBle(loop, phone, mac, mtu: 514)
        let wifi = LoopbackWifi(loop, phone, mac)
        ble.connect()
        loop.drain()
        connectWifi(wifi)
        tick(6 * 3600) { s in
            if s % 10 == 0 { self.phone.controller.send(.clip(id: "c\(s)", ts: self.loop.now, text: "clip \(s)")) }
            if s % 600 == 0 { self.phone.controller.send(.smsThreads(SmsThreadsPayload(threads: Self.threads(80)))) }
        }
        XCTAssertTrue(phone.closes.isEmpty, "\(phone.closes)")
        XCTAssertTrue(mac.closes.isEmpty, "\(mac.closes)")
        XCTAssertEqual(mac.state, .connected(peerName: "Pixel", wifi: true))
    }

    func testAnyFragmentCountsAsProofOfLife() throws {
        let ble = LoopbackBle(loop, phone, mac)
        ble.connect()
        loop.drain()
        let parts = try Fragmenter().fragment(Data(repeating: 3, count: 40_000), maxFragment: 185)
        var i = 0
        for s in 0..<90 {
            loop.now += 1_000
            if s % 5 == 0 && i < parts.count - 1 {
                mac.controller.bleFragment(parts[i])
                i += 1
            }
            mac.controller.tick()
            loop.drain()
        }
        XCTAssertTrue(mac.closes.isEmpty)
    }

    func testBluetoothReconnectUnderWifiIsInvisible() {
        let ble = LoopbackBle(loop, phone, mac)
        let wifi = LoopbackWifi(loop, phone, mac)
        ble.connect()
        loop.drain()
        connectWifi(wifi)
        mac.states.removeAll()
        ble.disconnect()
        loop.drain()
        ble.connect()
        loop.drain()
        XCTAssertTrue(mac.states.allSatisfy { if case .connected = $0 { return true } else { return false } }, "\(mac.states)")
    }

    func testBigListsPreferWifi() {
        let ble = LoopbackBle(loop, phone, mac)
        let wifi = LoopbackWifi(loop, phone, mac)
        ble.connect()
        loop.drain()
        connectWifi(wifi)
        let before = ble.fragmentsSent
        XCTAssertTrue(mac.controller.send(.smsMessages(SmsMessagesPayload(thread: 1, msgs: []))))
        loop.drain()
        XCTAssertEqual(ble.fragmentsSent, before)
    }

    func testUndecodableMessageIsDroppedNotFatal() throws {
        let ble = LoopbackBle(loop, phone, mac)
        ble.connect()
        loop.drain()
        // SMS_THREADS (0x91) whose header is not valid JSON for the payload.
        var plain = Data([0x91])
        let header = Data("{\"threads\":[{\"id\":\"seven\"}]}".utf8)
        plain.appendU32(UInt32(header.count))
        plain.append(header)
        try XCTUnwrap(phone.controller.bleSessionForTesting).sendRawForTesting(plain)
        loop.drain()
        XCTAssertEqual(mac.undecodable.first?.0, 0x91)
        XCTAssertTrue(mac.closes.isEmpty)
        XCTAssertTrue(phone.controller.send(.clip(id: "after", ts: 1, text: "still linked")))
        loop.drain()
        XCTAssertEqual(mac.messages.last, .clip(id: "after", ts: 1, text: "still linked"))
    }

    // MARK: decoding what real phones send

    private func decodeThreads(_ json: String) throws -> SmsThreadsPayload {
        try JSONDecoder().decode(SmsThreadsPayload.self, from: Data(json.utf8))
    }

    func testThreadsDecodeEdgeCases() throws {
        let p = try decodeThreads("""
        {"threads":[
          {"id":1,"addrs":[""],"names":[null],"snippet":"","date":0},
          {"id":2,"addrs":["+919847012345","AX-HDFCBK"],"names":[null,"HDFC 🏦"],"snippet":"\(String(repeating: "é😀", count: 400))",
           "date":1760000000000,"unread":3,"outLast":true},
          {"id":3,"date":5},
          {"id":4,"addrs":["1234"],"names":[],"snippet":"مرحبا","date":-1,"unread":0,"futureField":{"x":1}}
        ],"more":true,"needs":"error:SQLiteException"}
        """)
        XCTAssertEqual(p.threads.count, 4)
        XCTAssertEqual(p.threads[0].title, "")
        XCTAssertEqual(p.threads[1].title, "+919847012345, HDFC 🏦")
        XCTAssertEqual(p.threads[2].addrs, [])
        XCTAssertEqual(p.threads[2].title, "Unknown")
        XCTAssertEqual(p.threads[3].title, "1234")
        XCTAssertEqual(p.needs, "error:SQLiteException")
        XCTAssertTrue(p.more)
        XCTAssertEqual(try decodeThreads("{}").threads, [])
    }

    func testMessagesDecodeEdgeCases() throws {
        let json = """
        {"thread":7,"msgs":[
          {"id":"s1","thread":7,"addr":"","date":1,"sub":-1},
          {"id":"m2","thread":7,"date":2,"out":true,"status":"failed","att":[{"id":"p1"},{"id":"p2","mime":"video/mp4","w":0,"h":0}]},
          {"id":"s3","thread":7,"addr":"+91 98470 12345","name":null,"body":"\\u0000 zero byte and 🚲","date":3,"read":false}
        ],"more":false,"error":"error:timeout"}
        """
        let p = try JSONDecoder().decode(SmsMessagesPayload.self, from: Data(json.utf8))
        XCTAssertEqual(p.msgs.count, 3)
        XCTAssertEqual(p.msgs[0].sub, -1)
        XCTAssertEqual(p.msgs[1].addr, "")
        XCTAssertEqual(p.msgs[1].att.map(\.label), ["Attachment", "Video"])
        XCTAssertFalse(p.msgs[2].read)
        XCTAssertEqual(p.error, "error:timeout")
    }

    func testLargeListRoundTripsThroughTheCodec() throws {
        let list = Message.smsThreads(SmsThreadsPayload(threads: Self.threads(200), more: true, needs: nil))
        let bytes = try MessageCodec.encode(list)
        XCTAssertEqual(try MessageCodec.decode(bytes), list)
        let frags = try Fragmenter().fragment(bytes, maxFragment: Fragmentation.maxBleFragment)
        XCTAssertTrue(frags.allSatisfy { $0.count <= 512 })
        let r = Reassembler()
        var out: Data?
        for f in frags { out = r.accept(f) ?? out }
        XCTAssertEqual(out, bytes)
    }
}
