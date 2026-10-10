import Foundation
import XCTest
@testable import TetherCore

/// 0.3.1: the ways the link used to get stuck until Tether was quit and reopened.
final class ReconnectTests: XCTestCase {
    let loop = Loop()
    var phone: Side!
    var mac: Side!

    override func setUp() {
        (phone, mac) = pairedSides(loop)
    }

    private func tick(_ seconds: Int) {
        for _ in 0..<seconds {
            loop.now += 1_000
            phone.controller.tick()
            mac.controller.tick()
            loop.drain()
        }
    }

    func testWatchdogBackoff() {
        var w = ReconnectWatchdog()
        var restarts: [Int64] = []
        var t: Int64 = 0
        while t <= 20 * 60_000 {
            if w.observe(now: t, up: false, eligible: true) { restarts.append(t) }
            t += 5_000
        }
        XCTAssertEqual(restarts, [45_000, 135_000, 315_000, 615_000, 915_000])
    }

    func testWatchdogQuietWhenLinkedOrIneligible() {
        var w = ReconnectWatchdog()
        for t in stride(from: Int64(0), through: 600_000, by: 5_000) {
            XCTAssertFalse(w.observe(now: t, up: true, eligible: true))
            XCTAssertFalse(w.observe(now: t, up: false, eligible: false))
        }
        XCTAssertEqual(w.attempts, 0)
    }

    func testPokeAfterWake() {
        var w = ReconnectWatchdog()
        XCTAssertFalse(w.observe(now: 0, up: false, eligible: true))
        w.poke()
        XCTAssertFalse(w.observe(now: 5_000, up: false, eligible: true))
        XCTAssertTrue(w.observe(now: 10_000, up: false, eligible: true))
        XCTAssertFalse(w.observe(now: 20_000, up: false, eligible: true))
    }

    func testStaleSubscriberIsReplaced() {
        let a = UUID(), b = UUID()
        XCTAssertEqual(LinkPolicy.subscriber(current: nil, incoming: a, currentLinkEstablished: false), .accept)
        XCTAssertEqual(LinkPolicy.subscriber(current: a, incoming: b, currentLinkEstablished: false), .replace)
        XCTAssertEqual(LinkPolicy.subscriber(current: a, incoming: b, currentLinkEstablished: true), .ignore)
        XCTAssertEqual(LinkPolicy.subscriber(current: a, incoming: a, currentLinkEstablished: true), .replace)
    }

    func testAlreadyAdvertisingCountsAsAdvertising() {
        XCTAssertEqual(LinkPolicy.advertiseResult(errorCode: nil), .advertising)
        XCTAssertEqual(LinkPolicy.advertiseResult(errorCode: 9), .advertising)
        XCTAssertEqual(LinkPolicy.advertiseResult(errorCode: 1), .retry)
    }

    func testHalfOpenWifiIsDetected() {
        let ble = LoopbackBle(loop, phone, mac)
        let wifi = LoopbackWifi(loop, phone, mac)
        ble.connect()
        loop.drain()
        phone.controller.offerWifi(addrs: ["10.0.0.2"], port: 1)
        loop.drain()
        wifi.connect(token: mac.wifiOffers.last!.token)
        loop.drain()
        XCTAssertTrue(mac.controller.wifiReady)
        wifi.blackhole = true
        tick(70)
        XCTAssertFalse(mac.controller.wifiReady)
        XCTAssertEqual(mac.state, .connected(peerName: "Pixel", wifi: false))
    }

    func testSilentlyDeadBluetoothClosesAndRelinks() {
        let ble = LoopbackBle(loop, phone, mac)
        ble.connect()
        loop.drain()
        ble.dropToMac = Int.max
        ble.dropToPhone = Int.max
        tick(70)
        XCTAssertFalse(mac.controller.bleReady)
        ble.dropToMac = 0
        ble.dropToPhone = 0
        ble.disconnect()
        loop.drain()
        ble.connect()
        loop.drain()
        XCTAssertTrue(mac.controller.bleReady && phone.controller.bleReady)
    }

    func testBluetoothToggleMidHandshake() {
        let ble = LoopbackBle(loop, phone, mac)
        ble.connect()
        loop.step(3)
        ble.disconnect()
        loop.drain()
        tick(30)
        ble.connect()
        loop.drain()
        XCTAssertTrue(mac.controller.bleReady && phone.controller.bleReady)
    }
}
