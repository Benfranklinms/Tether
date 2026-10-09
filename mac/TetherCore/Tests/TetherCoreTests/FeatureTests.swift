import XCTest
@testable import TetherCore

final class FeatureCodecTests: XCTestCase {
    func testRevision2RoundTrips() throws {
        let all: [Message] = [
            .notifAction(NotifActionPayload(key: "k", action: "reply", text: "On my way 👍")),
            .notifAction(NotifActionPayload(key: "k", action: "1")),
            .notifActionResult(NotifActionResultPayload(key: "k", action: "reply", ok: false, reason: "gone")),
            .battery(BatteryPayload(level: 81, charging: true, plug: "usb", net: "wifi", gen: "5G", signal: 3, ts: 1700)),
            .battery(BatteryPayload(level: 5, charging: false, ts: 9)),
            .ring(timeoutS: 60), .ringStop(reason: "user"), .ringState(ringing: true, reason: nil),
            .ringState(ringing: false, reason: "timeout"),
            .screenStart(ScreenStartPayload()), .screenStart(ScreenStartPayload(maxSize: 720, fps: 30, bitrate: 2_000_000)),
            .screenState(ScreenStatePayload(state: "live", w: 720, h: 1600, rotation: 90, control: true)),
            .screenStop(reason: "phone"),
            .videoFrame(VideoFramePayload(src: "screen", seq: 7, pts: 33_000, key: true, config: true, data: Data(repeating: 9, count: 5000))),
            .screenInput(ScreenInputPayload(k: "swipe", x: 0.1, y: 0.2, x2: 0.3, y2: 0.4, dur: 250, rot: 90)),
            .screenInput(ScreenInputPayload(k: "text", text: "héllo")),
            .videoFeedback(VideoFeedbackPayload(kbps: 4000, lagMs: 40, dropped: 2, keyframe: true)),
            .notif(NotifPayload(key: "k", pkg: "com.whatsapp", app: "WhatsApp", title: "A", text: "B", ts: 1,
                                actions: [NotifActionInfo(id: "reply", title: "Reply", reply: true), NotifActionInfo(id: "1", title: "Mark as read")])),
            .fileOffer(FileOfferPayload(id: "i", name: "Screenshot.png", size: 10, sha256: String(repeating: "c", count: 64),
                                        mime: "image/png", kind: "screenshot")),
        ]
        for m in all { XCTAssertEqual(try MessageCodec.decode(try MessageCodec.encode(m)), m) }
    }

    func testKotlinShapedHeadersDecode() throws {
        // Exactly what kotlinx.serialization emits (encodeDefaults, explicitNulls=false).
        func msg(_ type: UInt8, _ json: String, body: Data = Data()) -> Data {
            var d = Data([type])
            d.appendU32(UInt32(json.utf8.count))
            d.append(Data(json.utf8))
            d.append(body)
            return d
        }
        XCTAssertEqual(try MessageCodec.decode(msg(0x70, #"{"level":42,"charging":true,"plug":"ac","ts":7}"#)),
                       .battery(BatteryPayload(level: 42, charging: true, plug: "ac", ts: 7)))
        XCTAssertEqual(try MessageCodec.decode(msg(0x83, #"{"src":"screen","seq":1,"pts":2,"key":true,"config":false,"part":0,"parts":1}"#,
                                                    body: Data([0, 0, 0, 1, 0x65]))),
                       .videoFrame(VideoFramePayload(src: "screen", seq: 1, pts: 2, key: true, config: false, data: Data([0, 0, 0, 1, 0x65]))))
        let n = try MessageCodec.decode(msg(0x40, #"{"key":"k","pkg":"p","app":"A","title":"t","text":"x","ts":1,"actions":[{"id":"reply","title":"Reply","reply":true}]}"#))
        guard case .notif(let p) = n else { return XCTFail() }
        XCTAssertEqual(p.actions, [NotifActionInfo(id: "reply", title: "Reply", reply: true)])
        // 0.1.x NOTIF without actions
        guard case .notif(let old) = try MessageCodec.decode(msg(0x40, #"{"key":"k","pkg":"p","app":"A","ts":1}"#)) else { return XCTFail() }
        XCTAssertTrue(old.actions.isEmpty)
        XCTAssertThrowsError(try MessageCodec.decode(msg(0x83, #"{"src":"screen","seq":1,"pts":2,"part":2,"parts":2}"#)))
    }

    func testHelloCaps() throws {
        let old = try MessageCodec.decodeHello(Data(#"{"v":1,"name":"P","platform":"android","app":"0.1.1"}"#.utf8))
        XCTAssertEqual(old.caps, Caps.v1)
        let new = Hello(name: "M", platform: "macos", app: "0.2.0", caps: Caps.current)
        XCTAssertTrue(try MessageCodec.decodeHello(MessageCodec.encodeHello(new)).caps.contains("screen"))
    }

    func testOldTransfersJsonStillLoads() throws {
        let json = #"[{"id":"a","direction":"IN","name":"x.pdf","size":3,"sha256":"aa","mime":"application/pdf","status":"DONE","transferred":3,"createdAt":1,"updatedAt":2,"retries":0}]"#
        let recs = try JSONDecoder().decode([TransferRecord].self, from: Data(json.utf8))
        XCTAssertEqual(recs.first?.wifiOnly, false)
        XCTAssertNil(recs.first?.kind)
        var r = recs[0]
        r.kind = "photo"
        r.wifiOnly = true
        let back = try JSONDecoder().decode(TransferRecord.self, from: JSONEncoder().encode(r))
        XCTAssertEqual(back, r)
    }
}

final class FeatureHelperTests: XCTestCase {
    let sps = Data([0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f])
    let pps = Data([0, 0, 0, 1, 0x68, 0xCE, 0x3C, 0x80])
    let idr = Data([0, 0, 1, 0x65, 0x88, 0x84, 0, 0, 3, 1])

    func testAnnexB() {
        let au = sps + pps + idr
        XCTAssertEqual(AnnexB.nalTypes(au), [7, 8, 5])
        XCTAssertTrue(AnnexB.isKeyframe(au))
        let ps = AnnexB.parameterSets(au)
        XCTAssertEqual(ps?.sps, Data([0x67, 0x42, 0x00, 0x1f]))
        XCTAssertEqual(ps?.pps, Data([0x68, 0xCE, 0x3C, 0x80]))
        XCTAssertEqual(AnnexB.toAvcc(sps + idr), Data([0, 0, 0, 4, 0x67, 0x42, 0x00, 0x1f, 0, 0, 0, 7, 0x65, 0x88, 0x84, 0, 0, 3, 1]))
        XCTAssertEqual(AnnexB.toAvcc(au, dropConfig: true), Data([0, 0, 0, 7, 0x65, 0x88, 0x84, 0, 0, 3, 1]))
        XCTAssertNil(AnnexB.parameterSets(idr))
    }

    func testSplitAndAssemble() {
        let data = Data((0..<2_500_123).map { UInt8(truncatingIfNeeded: $0 * 31) })
        let parts = FrameSplitter.split(src: "screen", seq: 42, pts: 1, key: true, config: false, data: data)
        XCTAssertEqual(parts.count, 3)
        let a = FrameAssembler()
        XCTAssertNil(a.accept(parts[0]))
        XCTAssertNil(a.accept(parts[1]))
        XCTAssertEqual(a.accept(parts[2])?.data, data)
        let b = FrameAssembler()
        let tiny = FrameSplitter.split(src: "screen", seq: 1, pts: 0, key: false, config: false, data: Data(count: 30), maxPart: 10)
        XCTAssertNil(b.accept(tiny[0]))
        XCTAssertNotNil(b.accept(VideoFramePayload(src: "screen", seq: 2, pts: 0, key: false, config: false, data: Data([1]))))
        XCTAssertEqual(b.dropped, 1)
        XCTAssertNil(b.accept(tiny[2]))
        XCTAssertEqual(b.dropped, 2)
    }

    func testGeometryLetterboxAndOrigin() {
        // 1080x2400 portrait video in a 600x600 view: fitted 270x600 centred at x=165.
        let g = VideoGeometry(view: CGSizeLite(600, 600), video: CGSizeLite(1080, 2400))
        let f = g.fitted
        XCTAssertEqual(f.x, 165, accuracy: 0.001)
        XCTAssertEqual(f.w, 270, accuracy: 0.001)
        XCTAssertNil(g.normalized(x: 100, y: 300)) // letterbox
        let c = g.normalized(x: 300, y: 300)!
        XCTAssertEqual(c.x, 0.5, accuracy: 1e-9)
        XCTAssertEqual(c.y, 0.5, accuracy: 1e-9)
        // AppKit (bottom-left origin): y = 450 is a quarter from the top.
        let q = g.normalized(x: 165, y: 450, bottomLeftOrigin: true)!
        XCTAssertEqual(q.x, 0, accuracy: 1e-9)
        XCTAssertEqual(q.y, 0.25, accuracy: 1e-9)
        XCTAssertEqual(g.normalized(x: 0, y: 0, clamp: true)?.x, 0)
        // Landscape video in a portrait-ish view letterboxes top/bottom.
        let l = VideoGeometry(view: CGSizeLite(400, 800), video: CGSizeLite(2400, 1080))
        XCTAssertNil(l.normalized(x: 200, y: 100))
        XCTAssertEqual(l.normalized(x: 200, y: 400)!.y, 0.5, accuracy: 1e-9)
    }

    func testGestureClassifier() {
        let tap = GestureClassifier.classify(down: (0.5, 0.5), up: (0.501, 0.5), durationMs: 90)
        XCTAssertEqual(tap.k, InputKinds.tap)
        let long = GestureClassifier.classify(down: (0.5, 0.5), up: (0.5, 0.5), durationMs: 700)
        XCTAssertEqual(long.k, InputKinds.longPress)
        XCTAssertEqual(long.dur, 700)
        let swipe = GestureClassifier.classify(down: (0.5, 0.8), up: (0.5, 0.2), durationMs: 10_000, aspect: 0.45, rotation: 0)
        XCTAssertEqual(swipe.k, InputKinds.swipe)
        XCTAssertEqual(swipe.dur, GestureClassifier.maxSwipeMs)
        XCTAssertEqual(swipe.y2, 0.2)
        XCTAssertEqual(swipe.rot, 0)
        let s = GestureClassifier.scroll(at: (0.5, 0.95), dx: 0, dy: 0.2)!
        XCTAssertEqual(s.k, InputKinds.scroll)
        XCTAssertEqual(s.y2, 0.98) // clamped on screen
        XCTAssertNil(GestureClassifier.scroll(at: (0.5, 0.5), dx: 0, dy: 0))
    }

    func testLowBatteryHysteresis() {
        var m = LowBatteryMonitor(threshold: 20, hysteresis: 5)
        XCTAssertFalse(m.update(level: 50, charging: false))
        XCTAssertTrue(m.update(level: 20, charging: false)) // alert once
        XCTAssertFalse(m.update(level: 19, charging: false))
        XCTAssertFalse(m.update(level: 22, charging: false)) // not re-armed yet (needs > 25)
        XCTAssertFalse(m.update(level: 18, charging: false))
        XCTAssertFalse(m.update(level: 18, charging: true)) // charging re-arms
        XCTAssertTrue(m.update(level: 17, charging: false))
        XCTAssertFalse(m.update(level: 26, charging: false))
        XCTAssertTrue(m.update(level: 20, charging: false))
    }

    func testVideoStats() {
        var s = VideoStats()
        XCTAssertNil(s.feedback(now: 0, lagMs: 0, needKeyframe: false))
        s.received(bytes: 250_000, now: 1_000)
        s.drop()
        XCTAssertNil(s.feedback(now: 1_500, lagMs: 10, needKeyframe: false))
        let fb = s.feedback(now: 2_000, lagMs: 30, needKeyframe: false)!
        XCTAssertEqual(fb.kbps, 2000)
        XCTAssertEqual(fb.dropped, 1)
        XCTAssertEqual(fb.lagMs, 30)
        XCTAssertTrue(s.feedback(now: 2_100, lagMs: 0, needKeyframe: true)!.keyframe)
    }
}

final class ControllerFeatureTests: XCTestCase {
    func testCapsAndRouting() {
        let loop = Loop()
        let (phone, mac) = pairedSides(loop)
        let ble = LoopbackBle(loop, phone, mac)
        let wifi = LoopbackWifi(loop, phone, mac)
        XCTAssertTrue(mac.controller.peerCaps.isEmpty)
        ble.connect()
        loop.drain()
        XCTAssertTrue(mac.controller.peerSupports(Caps.screen))
        let frame = Message.videoFrame(VideoFramePayload(src: "screen", seq: 1, pts: 0, key: true, config: true, data: Data(count: 70_000)))
        XCTAssertFalse(phone.controller.send(frame))
        XCTAssertTrue(mac.controller.send(.ring(timeoutS: 60)))
        loop.drain()
        XCTAssertEqual(phone.messages.last, .ring(timeoutS: 60))
        XCTAssertTrue(phone.controller.offerWifi(addrs: ["10.0.0.2"], port: 1))
        loop.drain()
        wifi.connect(token: mac.wifiOffers.last!.token)
        loop.drain()
        XCTAssertTrue(phone.controller.send(frame))
        XCTAssertTrue(mac.controller.send(.screenInput(ScreenInputPayload(k: "tap", x: 0.5, y: 0.5))))
        loop.drain()
        XCTAssertEqual(mac.messages.last, frame)
        XCTAssertEqual(phone.messages.last, .screenInput(ScreenInputPayload(k: "tap", x: 0.5, y: 0.5)))
    }

    func testOldPhoneHidesFeatures() {
        let loop = Loop()
        let (phone, mac) = pairedSides(loop)
        phone.caps = Caps.v1
        let ble = LoopbackBle(loop, phone, mac)
        ble.connect()
        loop.drain()
        XCTAssertFalse(mac.controller.peerSupports(Caps.ring))
        XCTAssertTrue(mac.controller.peerSupports(Caps.clip))
    }

    func testWifiOnlyMediaWaits() {
        let loop = Loop()
        let (phone, mac) = pairedSides(loop)
        let ble = LoopbackBle(loop, phone, mac)
        let wifi = LoopbackWifi(loop, phone, mac)
        ble.connect()
        loop.drain()
        var rec = phone.files.stage("IMG.jpg", randomData(200_000, seed: 3), now: loop.now)
        rec.kind = "photo"
        rec.wifiOnly = true
        phone.controller.sendFile(rec)
        loop.drain()
        XCTAssertEqual(phone.transfers[rec.id]?.status, .WAITING)
        XCTAssertEqual(phone.transfers[rec.id]?.error, TransferEngine.waitingForWifiOnly)
        XCTAssertTrue(phone.controller.transfers.hasOutgoing(sha256: rec.sha256))
        phone.controller.offerWifi(addrs: ["10.0.0.2"], port: 1)
        loop.drain()
        wifi.connect(token: mac.wifiOffers.last!.token)
        loop.drain()
        XCTAssertEqual(mac.transfers[rec.id]?.status, .DONE)
        XCTAssertEqual(mac.transfers[rec.id]?.kind, "photo")
        XCTAssertEqual(mac.transfers[rec.id]?.via, .WIFI)
    }
}
