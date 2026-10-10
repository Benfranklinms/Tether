import Foundation
import XCTest
@testable import TetherCore

/// 0.4.0: hours of simulated call traffic on the real Swift controllers, Bluetooth only and
/// Wi-Fi with idle Bluetooth. Nothing lost, nothing reordered, no link closes.
final class Revision4SoakTests: XCTestCase {
    let loop = Loop()
    var phone: Side!
    var mac: Side!

    override func setUp() {
        (phone, mac) = pairedSides(loop)
    }

    private func log(_ n: Int) -> Message {
        let types = [CallLogTypes.incoming, CallLogTypes.outgoing, CallLogTypes.missed]
        var calls: [CallLogEntry] = []
        for i in 0..<n {
            let name: String? = i % 3 == 0 ? nil : "Contact \(i) ✓"
            let date: Int64 = loop.now - Int64(i) * 60_000
            calls.append(CallLogEntry(id: Int64(10_000 - i), number: "+91 98470 \(10_000 + i)", name: name, type: types[i % 3],
                                      date: date, dur: Int64(i % 400), sub: i % 2 + 1, isNew: i % 7 == 0))
        }
        return .callLog(CallLogPayload(calls: calls, more: true))
    }

    private func soak(wifi wifiUp: Bool) {
        let ble = LoopbackBle(loop, phone, mac, mtu: 514)
        ble.connect()
        loop.drain()
        if wifiUp {
            let wifi = LoopbackWifi(loop, phone, mac)
            phone.controller.offerWifi(addrs: ["10.0.0.2"], port: 1)
            loop.drain()
            wifi.connect(token: mac.wifiOffers.last!.token)
            loop.drain()
            XCTAssertTrue(mac.controller.wifiReady)
        }
        var fromPhone: [Message] = []
        var fromMac: [Message] = []
        func p(_ m: Message) { XCTAssertTrue(phone.controller.send(m)); fromPhone.append(m) }
        func m(_ x: Message) { XCTAssertTrue(mac.controller.send(x)); fromMac.append(x) }
        for s in 0..<(6 * 3600) {
            loop.now += 1_000
            phone.controller.tick()
            mac.controller.tick()
            if s % 600 == 0 {
                let id = "c\(s)"
                m(.callDial(CallDialPayload(ref: "r\(s)", number: "+919847012345", sub: 2)))
                p(.callDialResult(CallDialResultPayload(ref: "r\(s)", ok: true, via: "telecom")))
                p(.callLive(CallLivePayload(id: id, dir: CallDirs.outgoing, state: CallLiveStates.dialing, since: loop.now,
                                            canEnd: true, canSpeaker: true, canMute: true, ts: loop.now)))
                m(.callAction(CallActionPayload(id: id, action: CallActions4.speaker, on: true)))
                m(.callAction(CallActionPayload(id: id, action: CallActions4.end)))
                p(.callLive(CallLivePayload(id: id, dir: CallDirs.outgoing, state: CallLiveStates.ended, ts: loop.now)))
            }
            if s % 1800 == 0 {
                m(.callLogReq(CallLogReq(limit: 100)))
                p(log(100))
            }
            if s % 900 == 300 {
                m(.contactSearch(ContactSearchPayload(query: "as\(s)", limit: 12)))
                p(.contacts(ContactsPayload(query: "as\(s)", contacts: [ContactInfo(name: "Asha", numbers: [ContactNumber(n: "+91")])])))
            }
            if s % 3600 == 1 { p(.notifAccess(NotifAccessPayload(redacting: true, sdk: 36, oem: "OnePlus", ts: loop.now))) }
            if wifiUp && s % 20 == 0 { _ = mac.controller.send(.clip(id: "k\(s)", ts: loop.now, text: "clip \(s)")) }
            loop.drain()
        }
        XCTAssertTrue(phone.closes.isEmpty && mac.closes.isEmpty, "closes: \(phone.closes) \(mac.closes)")
        let rev4: (Message) -> Bool = { (0xA0...0xA8).contains($0.type) || $0.type == 0x45 }
        XCTAssertEqual(mac.messages.filter(rev4), fromPhone)
        XCTAssertEqual(phone.messages.filter(rev4), fromMac)
        XCTAssertLessThanOrEqual(ble.maxToMac, Fragmentation.maxBleFragment)
        XCTAssertGreaterThan(fromPhone.count, 100)
    }

    func testSixHoursOfCallsOverBluetoothOnly() { soak(wifi: false) }

    func testSixHoursOfCallsOverWifiWithIdleBluetooth() { soak(wifi: true) }
}
