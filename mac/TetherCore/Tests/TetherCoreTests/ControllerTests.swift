import XCTest
@testable import TetherCore

final class ControllerTests: XCTestCase {
    let loop = Loop()

    private func fresh() -> (Side, Side) { (Side("Pixel", .initiator, loop), Side("MacBook", .responder, loop)) }

    func testManualPairing() {
        let (phone, mac) = fresh()
        LoopbackBle(loop, phone, mac).connect(phonePairing: PairingContext(acceptNew: true),
                                              macPairing: PairingContext(acceptNew: true))
        loop.drain()
        guard case .pairing(let a, let name, false) = phone.state, case .pairing(let b, _, _) = mac.state else {
            return XCTFail("not pairing: \(phone.state) \(mac.state)")
        }
        XCTAssertEqual(a, b)
        XCTAssertEqual(a.count, 6)
        XCTAssertEqual(name, "MacBook")
        phone.controller.confirmPairing()
        loop.drain()
        mac.controller.confirmPairing()
        loop.drain()
        XCTAssertEqual(phone.state, .connected(peerName: "MacBook", wifi: false))
        XCTAssertEqual(mac.state, .connected(peerName: "Pixel", wifi: false))
        XCTAssertEqual(mac.identity.peer?.keyBytes, phone.identity.localKey.publicKey)
    }

    func testQrPairing() {
        let (phone, mac) = fresh()
        let token = Data((0..<16).map { UInt8($0) })
        let qr = PairingURI.parse(PairingURI(key: mac.identity.localKey.publicKey, token: token, name: "Mac").encode())!
        LoopbackBle(loop, phone, mac).connect(
            phonePairing: PairingContext(acceptNew: true, expectedRemoteKey: qr.key, token: qr.token),
            macPairing: PairingContext(acceptNew: true, token: token))
        loop.drain()
        XCTAssertEqual(phone.state, .connected(peerName: "MacBook", wifi: false))
        XCTAssertEqual(mac.state, .connected(peerName: "Pixel", wifi: false))
    }

    func testUntrustedRejected() {
        let (phone, mac) = fresh()
        LoopbackBle(loop, phone, mac).connect(phonePairing: PairingContext(acceptNew: true), macPairing: .closed)
        loop.drain()
        XCTAssertEqual(mac.state, .offline)
        XCTAssertTrue(phone.closes.contains { $0.0 == .untrusted && $0.1 })
    }

    func testWrongDevice() {
        let (phone, mac) = fresh()
        LoopbackBle(loop, phone, mac).connect(
            phonePairing: PairingContext(acceptNew: true, expectedRemoteKey: Data(repeating: 5, count: 32), token: Data(count: 16)),
            macPairing: PairingContext(acceptNew: true))
        loop.drain()
        XCTAssertTrue(phone.closes.contains { $0.0 == .wrongDevice })
    }

    func testRejectPairing() {
        let (phone, mac) = fresh()
        LoopbackBle(loop, phone, mac).connect(phonePairing: PairingContext(acceptNew: true),
                                              macPairing: PairingContext(acceptNew: true))
        loop.drain()
        mac.controller.rejectPairing()
        loop.drain()
        XCTAssertTrue(phone.closes.contains { $0.0 == .pairingRejected })
        XCTAssertNil(phone.identity.peer)
    }

    func testClipNotifAndDismiss() {
        let (phone, mac) = pairedSides(loop)
        LoopbackBle(loop, phone, mac, mtu: 23).connect()
        loop.drain()
        let text = String(repeating: "multi-line ✓\n", count: 200)
        XCTAssertTrue(mac.controller.send(.clip(id: "c1", ts: 5, text: text)))
        let n = NotifPayload(key: "k1", pkg: "com.whatsapp", app: "WhatsApp", title: "Asha", text: "hey", ts: 9)
        XCTAssertTrue(phone.controller.send(.notif(n)))
        XCTAssertTrue(mac.controller.send(.notifDismiss(key: "k1")))
        loop.drain()
        XCTAssertEqual(phone.messages.first, .clip(id: "c1", ts: 5, text: text))
        XCTAssertEqual(mac.messages, [.notif(n)])
        XCTAssertEqual(phone.messages.last, .notifDismiss(key: "k1"))
        XCTAssertFalse(mac.controller.send(.clip(id: "x", ts: 1, text: String(repeating: "x", count: TetherProtocol.maxClipBytes + 1))))
    }

    func testUnpairAndRotation() {
        let (phone, mac) = pairedSides(loop)
        let link = LoopbackBle(loop, phone, mac)
        link.connect()
        loop.drain()
        let old = mac.identity.localKey.publicKey
        XCTAssertTrue(mac.controller.rotateKey())
        loop.drain()
        XCTAssertEqual(mac.rotated, 1)
        XCTAssertNotEqual(old, mac.identity.localKey.publicKey)
        link.disconnect()
        loop.drain()
        link.connect()
        loop.drain()
        XCTAssertEqual(phone.state, .connected(peerName: "MacBook", wifi: false))
        XCTAssertFalse(phone.identity.peer!.trusts(old))
        phone.controller.unpair()
        loop.drain()
        XCTAssertNil(mac.identity.peer)
        XCTAssertEqual(mac.unpairedByRemote, true)
    }

    func testTimeouts() {
        let (phone, mac) = pairedSides(loop)
        let link = LoopbackBle(loop, phone, mac)
        link.cutAfter = 0
        link.connect()
        loop.drain()
        loop.now += TetherController.handshakeTimeoutMs + 1
        phone.controller.tick()
        loop.drain()
        XCTAssertEqual(phone.state, .offline)
    }
}

final class TransferTests: XCTestCase {
    let loop = Loop()
    var phone: Side!
    var mac: Side!
    var ble: LoopbackBle!
    var wifi: LoopbackWifi!

    override func setUp() {
        (phone, mac) = pairedSides(loop)
        ble = LoopbackBle(loop, phone, mac, mtu: 247)
        wifi = LoopbackWifi(loop, phone, mac)
    }

    private func connect() {
        ble.connect()
        loop.drain()
    }

    func testMacToPhone() {
        connect()
        let bytes = randomData(200_000, seed: 1)
        let rec = mac.files.stage("photo.jpg", bytes, now: loop.now)
        mac.controller.sendFile(rec)
        loop.drain()
        XCTAssertEqual(mac.transfers[rec.id]?.status, .DONE)
        XCTAssertEqual(phone.transfers[rec.id]?.via, .BLE)
        XCTAssertEqual(phone.files.finished[rec.id], bytes)
    }

    func testResumeAfterDrop() {
        connect()
        let bytes = randomData(300_000, seed: 2)
        let rec = mac.files.stage("v.mp4", bytes, now: loop.now)
        ble.cutAfter = 400
        mac.controller.sendFile(rec)
        loop.drain()
        XCTAssertEqual(mac.transfers[rec.id]?.status, .WAITING)
        let partial = phone.files.partialLength(rec.id)
        XCTAssertGreaterThan(partial, 0)
        let before = ble.fragmentsSent
        connect()
        loop.drain()
        XCTAssertEqual(phone.files.finished[rec.id], bytes)
        XCTAssertLessThan(ble.fragmentsSent - before, bytes.count / 240)
    }

    func testCorruptionRetried() {
        connect()
        phone.files.corruptNextFinish = true
        let bytes = randomData(40_000, seed: 3)
        let rec = mac.files.stage("x", bytes, now: loop.now)
        mac.controller.sendFile(rec)
        loop.drain()
        XCTAssertEqual(mac.transfers[rec.id]?.status, .DONE)
        XCTAssertEqual(mac.transfers[rec.id]?.retries, 1)
        XCTAssertEqual(phone.files.finished[rec.id], bytes)
    }

    func testCancelFromReceiver() {
        connect()
        let rec = mac.files.stage("big", randomData(500_000, seed: 4), now: loop.now)
        mac.controller.sendFile(rec)
        loop.step(2_000)
        phone.controller.cancelTransfer(rec.id)
        loop.drain()
        XCTAssertEqual(mac.transfers[rec.id]?.status, .CANCELLED)
        XCTAssertEqual(phone.transfers[rec.id]?.status, .CANCELLED)
    }

    func testFailedThenRetry() {
        connect()
        mac.files.failReads = true
        let bytes = randomData(1000, seed: 5)
        let rec = mac.files.stage("gone", bytes, now: loop.now)
        mac.controller.sendFile(rec)
        loop.drain()
        XCTAssertEqual(mac.transfers[rec.id]?.status, .FAILED)
        mac.files.failReads = false
        mac.controller.retryTransfer(rec.id)
        loop.drain()
        XCTAssertNil(mac.transfers[rec.id])
        let again = mac.transfers.values.first!
        XCTAssertEqual(again.status, .DONE)
        XCTAssertEqual(phone.files.finished[again.id], bytes)
    }

    func testWifiSwitchAndFallback() {
        connect()
        let bytes = randomData(2_000_000, seed: 6)
        let rec = mac.files.stage("movie.mov", bytes, now: loop.now)
        mac.controller.sendFile(rec)
        loop.step(4_000)
        XCTAssertGreaterThan(phone.files.partialLength(rec.id), 0)
        XCTAssertTrue(phone.controller.offerWifi(addrs: ["192.168.1.20"], port: 5000))
        loop.step(2_000)
        let offer = mac.wifiOffers.first!
        wifi.cutAfter = 10
        wifi.connect(token: offer.token)
        loop.drain()
        XCTAssertEqual(phone.files.finished[rec.id], bytes)
        XCTAssertEqual(mac.transfers[rec.id]?.via, .BLE)
    }

    func testWifiWholeTransferAndBadToken() {
        connect()
        phone.controller.offerWifi(addrs: ["10.0.0.2"], port: 1)
        loop.drain()
        wifi.connect(token: "AAAAAAAAAAAAAAAAAAAAAA")
        loop.drain()
        XCTAssertFalse(phone.controller.wifiReady)
        phone.controller.offerWifi(addrs: ["10.0.0.2"], port: 1)
        loop.drain()
        wifi.connect(token: mac.wifiOffers.last!.token)
        loop.drain()
        XCTAssertEqual(phone.state, .connected(peerName: "MacBook", wifi: true))
        let bytes = randomData(3_000_000, seed: 7)
        let rec = mac.files.stage("disk.img", bytes, now: loop.now)
        mac.controller.sendFile(rec)
        loop.drain()
        XCTAssertEqual(phone.transfers[rec.id]?.via, .WIFI)
        XCTAssertEqual(phone.files.finished[rec.id], bytes)
        let big = String(repeating: "y", count: TetherProtocol.maxClipBytes * 4)
        XCTAssertTrue(mac.controller.send(.clip(id: "c", ts: 1, text: big)))
        loop.drain()
        XCTAssertEqual(phone.messages.last, .clip(id: "c", ts: 1, text: big))
    }

    func testHugeFileWaitsForWifi() {
        connect()
        var rec = mac.files.stage("huge", Data(), now: loop.now)
        rec.size = TetherProtocol.fileMaxBytes + 1
        mac.controller.sendFile(rec)
        loop.drain()
        XCTAssertEqual(mac.transfers[rec.id]?.status, .WAITING)
        XCTAssertEqual(mac.transfers[rec.id]?.error, TransferEngine.waitingForWifi)
    }

    func testRestoreAfterRestart() {
        connect()
        let bytes = randomData(150_000, seed: 8)
        let rec = mac.files.stage("doc.pdf", bytes, now: loop.now)
        ble.cutAfter = 300
        mac.controller.sendFile(rec)
        loop.drain()
        let restored = Side("MacBook", .responder, loop)
        restored.identity.localKey = mac.identity.localKey
        restored.identity.peer = mac.identity.peer
        restored.files.sources = mac.files.sources
        restored.controller.restoreTransfers(Array(mac.transfers.values))
        LoopbackBle(loop, phone, restored).connect()
        loop.drain()
        XCTAssertEqual(restored.transfers[rec.id]?.status, .DONE)
        XCTAssertEqual(phone.files.finished[rec.id], bytes)
    }
}

// MARK: handshake recovery (first real-device run: the Mac sat on "Linking" forever)

extension ControllerTests {

    private func isPairing(_ s: LinkState) -> Bool {
        if case .pairing = s { return true }
        return false
    }

    func testLostFirstHandshakeMessageIsRetried() {
        let (phone, mac) = fresh()
        let link = LoopbackBle(loop, phone, mac)
        link.dropToMac = 1
        link.connect(phonePairing: PairingContext(acceptNew: true), macPairing: PairingContext(acceptNew: true))
        loop.drain()
        XCTAssertEqual(phone.state, .handshaking)
        loop.now += TetherController.handshakeRetryMs + 1
        phone.controller.tick()
        loop.drain()
        XCTAssertEqual(phone.controller.bleHandshakeRetries, 1)
        XCTAssertTrue(isPairing(phone.state))
        XCTAssertTrue(isPairing(mac.state))
        phone.controller.confirmPairing()
        mac.controller.confirmPairing()
        loop.drain()
        XCTAssertEqual(phone.state, .connected(peerName: "MacBook", wifi: false))
        XCTAssertEqual(mac.state, .connected(peerName: "Pixel", wifi: false))
        XCTAssertTrue(phone.closes.isEmpty)
    }

    func testLostMacReplyRestartsBothSides() {
        let (phone, mac) = fresh()
        let link = LoopbackBle(loop, phone, mac)
        link.dropToPhone = 1
        link.connect(phonePairing: PairingContext(acceptNew: true), macPairing: PairingContext(acceptNew: true))
        loop.drain()
        loop.now += TetherController.handshakeRetryMs + 1
        phone.controller.tick()
        loop.drain()
        XCTAssertEqual(mac.controller.bleHandshakeRetries, 1)
        XCTAssertTrue(isPairing(phone.state))
        XCTAssertTrue(isPairing(mac.state))
    }

    func testMacSessionThatTimedOutRevivesWhenPhoneRetries() {
        let (phone, mac) = fresh()
        let link = LoopbackBle(loop, phone, mac)
        link.dropToMac = 1_000
        link.dropToPhone = 1_000
        link.connect(phonePairing: PairingContext(acceptNew: true), macPairing: PairingContext(acceptNew: true))
        loop.drain()
        loop.now += TetherController.handshakeTimeoutMs + 1
        mac.controller.tick()
        loop.drain()
        XCTAssertEqual(mac.state, .offline)
        link.dropToMac = 0
        link.dropToPhone = 0
        phone.controller.tick()
        loop.drain()
        XCTAssertTrue(isPairing(mac.state))
        XCTAssertTrue(isPairing(phone.state))
    }

    func testPairingOpenedAfterConnectAppliesToRetry() {
        let (phone, mac) = fresh()
        let link = LoopbackBle(loop, phone, mac)
        link.dropToMac = 1
        link.connect(phonePairing: PairingContext(acceptNew: true), macPairing: .closed)
        loop.drain()
        mac.controller.setPairing(PairingContext(acceptNew: true))
        loop.now += TetherController.handshakeRetryMs + 1
        phone.controller.tick()
        loop.drain()
        XCTAssertTrue(isPairing(mac.state))
    }

    /// 0.5.4 (Ben's log: "link.closed PROTOCOL_ERROR remote=true" while handshaking): the phone
    /// restarts its handshake while the Mac still holds the old, established session on the same
    /// link. The Mac must start over, not close.
    func testStaleEstablishedSessionRestartsWhenPhoneHandshakesAgain() {
        let (phone, mac) = pairedSides(loop)
        let link = LoopbackBle(loop, phone, mac, mtu: 185)
        link.connect()
        loop.drain()
        XCTAssertEqual(mac.state, .connected(peerName: "Pixel", wifi: false))
        link.phoneRestartsHandshake()
        loop.drain()
        XCTAssertEqual(phone.state, .connected(peerName: "MacBook", wifi: false))
        XCTAssertEqual(mac.state, .connected(peerName: "Pixel", wifi: false))
        XCTAssertEqual(mac.controller.staleHandshakeRestarts, 1)
        XCTAssertFalse(phone.closes.contains { $0.0 == .protocolError }, "\(phone.closes)")
        XCTAssertFalse(mac.closes.contains { $0.0 == .protocolError }, "\(mac.closes)")
        // And the restarted session carries traffic.
        _ = phone.controller.send(.clip(id: "c1", ts: 1, text: "hello"))
        loop.drain()
        XCTAssertTrue(mac.messages.contains(.clip(id: "c1", ts: 1, text: "hello")))
    }

    /// Two restarts in a row (a flapping phone) still end connected, never PROTOCOL_ERROR.
    func testRepeatedStaleHandshakesConverge() {
        let (phone, mac) = pairedSides(loop)
        let link = LoopbackBle(loop, phone, mac, mtu: 185)
        link.connect()
        loop.drain()
        link.phoneRestartsHandshake()
        loop.step(3)
        link.phoneRestartsHandshake()
        loop.drain()
        XCTAssertEqual(phone.state, .connected(peerName: "MacBook", wifi: false))
        XCTAssertEqual(mac.state, .connected(peerName: "Pixel", wifi: false))
        XCTAssertFalse(phone.closes.contains { $0.0 == .protocolError })
    }
}
