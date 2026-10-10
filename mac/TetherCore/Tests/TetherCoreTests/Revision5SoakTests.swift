import Foundation
import XCTest
@testable import TetherCore

/// 0.5.0: hours of simulated file browsing, photo pages, Continue cards, codes, typing and DND
/// traffic on the real Swift controllers, Bluetooth only and Wi-Fi with idle Bluetooth.
/// Nothing lost, nothing reordered, no link closes.
final class Revision5SoakTests: XCTestCase {
    let loop = Loop()
    var phone: Side!
    var mac: Side!
    let jpeg = Data((0..<24_000).map { UInt8($0 * 13 % 251) })

    override func setUp() {
        (phone, mac) = pairedSides(loop)
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
        for s in 0..<(4 * 3600) {
            loop.now += 1_000
            phone.controller.tick()
            mac.controller.tick()
            if s % 300 == 0 {
                m(.fsListReq(FsListReq(ref: "l\(s)", root: FsRoots.whatsapp, path: "WhatsApp Images")))
                p(.fsList(FsListPayload(ref: "l\(s)", root: FsRoots.whatsapp, path: "WhatsApp Images",
                                        entries: (0..<120).map { FsEntry(name: "IMG-WA\($0).jpg", dir: $0 % 12 == 0, size: 150_000,
                                                                         mtime: loop.now, mime: "image/jpeg") })))
                for i in 0..<4 {
                    m(.thumbReq(ThumbReq(ref: "t\(s)-\(i)", root: FsRoots.whatsapp, path: "WhatsApp Images/IMG-\(i).jpg")))
                    p(.thumb(ThumbPayload(ref: "t\(s)-\(i)", ok: true, data: jpeg)))
                }
            }
            if s % 600 == 10 {
                m(.photosReq(PhotosReq(ref: "p\(s)")))
                let items = (0..<60).map { PhotoItem(id: "\(s + $0)", name: "IMG_\($0).jpg") }
                p(.photos(PhotosPayload(ref: "p\(s)", items: items, more: true)))
            }
            if s % 450 == 20 {
                p(.continueCard(ContinuePayload(id: "photo-\(s)", kind: ContinueKinds.photo, title: "IMG_\(s).jpg", root: FsRoots.photos,
                                                path: "\(s)", ts: loop.now), thumb: jpeg))
                p(.continueCard(ContinuePayload(id: "tab", kind: ContinueKinds.tab, title: "Tab", url: "https://example.com/\(s)",
                                                ts: loop.now), thumb: Data()))
            }
            if s % 777 == 5 { p(.otp(OtpPayload(code: "\(100_000 + s)", app: "HDFC Bank", src: "sms", ts: loop.now))) }
            if s % 120 == 30 {
                for k in 0..<10 { m(.typeText(TypeTextPayload(seq: Int64(s * 10 + k), text: "hello \(k)"))) }
                p(.typeResult(TypeResultPayload(seq: Int64(s * 10 + 9), ok: true)))
            }
            if s % 1800 == 40 {
                m(.dndState(DndStatePayload(on: s % 3600 == 40, src: "mac", ts: loop.now)))
                p(.dndState(DndStatePayload(on: s % 3600 == 40, src: "phone", ts: loop.now, canSet: true)))
                m(.phoneStatusReq(ts: loop.now))
                p(.phoneStatus(PhoneStatusPayload(perms: [PermInfo(id: "notif", label: "Notification access", ok: true)], sdk: 36,
                                                  ts: loop.now)))
            }
            loop.drain()
        }
        XCTAssertTrue(phone.closes.isEmpty && mac.closes.isEmpty, "closes: \(phone.closes) \(mac.closes)")
        let rev5: (Message) -> Bool = { $0.type == 0x32 || (0xC0...0xCC).contains($0.type) || (0xE0...0xE2).contains($0.type) }
        XCTAssertEqual(mac.messages.filter(rev5), fromPhone)
        XCTAssertEqual(phone.messages.filter(rev5), fromMac)
        XCTAssertLessThanOrEqual(ble.maxToMac, Fragmentation.maxBleFragment)
        XCTAssertGreaterThan(fromPhone.count, 300)
    }

    func testFourHoursOfRevision5OverBluetoothOnly() { soak(wifi: false) }

    func testFourHoursOfRevision5OverWifiWithIdleBluetooth() { soak(wifi: true) }
}
