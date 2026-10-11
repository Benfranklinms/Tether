import Foundation
import XCTest
@testable import TetherCore

/// 0.5.4: Shelf folders (protocol revision 8), the 500 limit, paged snapshots and offline copies.
/// Mirrored in android/core ShelfFoldersTest.kt.
final class ShelfFoldersTests: XCTestCase {
    private func item(_ id: String, _ t: Int64, from: String = "mac", folder: String? = nil, file: ShelfFile? = nil) -> ShelfItem {
        ShelfItem(id: id, kind: file == nil ? ShelfKinds.text : ShelfKinds.file, title: "t\(id)", body: "b\(t)", from: from,
                  created: t, updated: t, order: 0, file: file, folderId: folder)
    }

    // MARK: codec

    func testCodecRoundTripWithFolders() throws {
        let p = ShelfSyncPayload(items: [item("a", 1, folder: "f1")], removed: [ShelfTomb(id: "x", ts: 3)], full: true,
                                 folders: [ShelfFolder(id: "f1", name: "Placement", order: 1, updated: 2)],
                                 removedFolders: [ShelfTomb(id: "f0", ts: 4)])
        let m = Message.shelfSync(p)
        XCTAssertEqual(try MessageCodec.decode(try MessageCodec.encode(m)), m)
    }

    func testOldFormatDecodesAsLoose() throws {
        // A 0.5.3 peer's SHELF_SYNC: no folders, no folderId.
        let json = #"{"items":[{"id":"a","kind":"text","title":"t","from":"phone","created":1,"updated":1,"order":0}],"removed":[],"full":true}"#
        let p = try JSONDecoder().decode(ShelfSyncPayload.self, from: Data(json.utf8))
        XCTAssertNil(p.items[0].folderId)
        XCTAssertEqual(p.folders, [])
        XCTAssertEqual(ShelfState().merge(p).items(in: nil).map(\.id), ["a"])
    }

    func testDamagedFolderFieldsNeverFailTheSync() throws {
        let json = #"{"items":[{"id":"a","kind":"text","title":"t","from":"phone","created":1,"updated":1,"order":0,"folderId":7,"file":"x"}],"folders":"bad"}"#
        let p = try JSONDecoder().decode(ShelfSyncPayload.self, from: Data(json.utf8))
        XCTAssertEqual(p.items.count, 1)
        XCTAssertNil(p.items[0].folderId)
        XCTAssertNil(p.items[0].file)
        XCTAssertEqual(p.folders, [])
    }

    func testOldVaultFileAndDamagedEntriesLoad() throws {
        let old = #"{"items":[{"id":"a","kind":"text","title":"t","from":"phone","created":1,"updated":1,"order":0},{"id":5}],"tombs":[]}"#
        let s = try JSONDecoder().decode(ShelfState.self, from: Data(old.utf8))
        XCTAssertEqual(s.items.map(\.id), ["a"])
        XCTAssertEqual(s.folders, [])
        let key = ShelfVault.newKey()
        var full = s.createFolder(id: "f", name: "College", now: 2)!
        full = full.moveTo("a", folder: "f", now: 3)!
        XCTAssertEqual(try ShelfVault.open(try ShelfVault.seal(full, key: key), key: key), full)
    }

    // MARK: model

    func testCreateRenameDeleteAndMove() throws {
        var s = ShelfState().add(item("a", 1)).add(item("b", 2))
        s = try XCTUnwrap(s.createFolder(id: "f1", name: "  Placement \n drives ", now: 5))
        XCTAssertEqual(s.folders.first?.name, "Placement drives")
        XCTAssertNil(s.createFolder(id: "f2", name: "   ", now: 6))
        s = try XCTUnwrap(s.createFolder(id: "f2", name: "College", now: 6))
        XCTAssertEqual(s.orderedFolders.map(\.id), ["f1", "f2"])
        s = try XCTUnwrap(s.moveTo("a", folder: "f1", now: 7))
        XCTAssertNil(s.moveTo("a", folder: "f1", now: 8), "dropping onto the same folder changes nothing")
        XCTAssertNil(s.moveTo("a", folder: "nope", now: 8))
        XCTAssertEqual(s.items(in: "f1").map(\.id), ["a"])
        XCTAssertEqual(s.items(in: nil).map(\.id), ["b"])
        XCTAssertEqual(s.counts(), ["f1": 1])
        s = try XCTUnwrap(s.renameFolder("f1", name: "Placement", now: 9))
        XCTAssertNil(s.renameFolder("f1", name: "Placement", now: 10))
        s = s.deleteFolder("f1", now: 11)
        XCTAssertEqual(s.items.count, 2, "deleting a folder never deletes items")
        XCTAssertEqual(s.items(in: nil).count, 2)
        XCTAssertNil(s.items.first { $0.id == "a" }?.folderId)
        XCTAssertEqual(s.folderTombs.map(\.id), ["f1"])
    }

    func testRapidRepeatedMovesAreIdempotent() throws {
        var s = try XCTUnwrap(ShelfState().add(item("a", 1)).createFolder(id: "f", name: "F", now: 2))
        s = try XCTUnwrap(s.moveTo("a", folder: "f", now: 3))
        for i in 0..<20 { if let n = s.moveTo("a", folder: "f", now: 4 + Int64(i)) { s = n } }
        XCTAssertEqual(s.items[0].updated, 3)
        s = s.deleteFolder("f", now: 30).deleteFolder("f", now: 31)
        XCTAssertEqual(s.folderTombs, [ShelfTomb(id: "f", ts: 30)])
    }

    func testMoveIntoAFolderDeletedOnTheOtherDeviceShowsLoose() throws {
        let base = try XCTUnwrap(ShelfState().add(item("a", 1)).createFolder(id: "f", name: "F", now: 2))
        let mac = try XCTUnwrap(base.moveTo("a", folder: "f", now: 10)) // moved while offline
        let phone = base.deleteFolder("f", now: 5)
        let m = mac.merge(phone.snapshot())
        let p = phone.merge(mac.snapshot())
        XCTAssertEqual(m, p)
        XCTAssertEqual(m.items(in: nil).map(\.id), ["a"])
        XCTAssertTrue(m.folders.isEmpty)
    }

    func testRenameConflictNewestWins() throws {
        let base = try XCTUnwrap(ShelfState().createFolder(id: "f", name: "A", now: 1))
        let mac = try XCTUnwrap(base.renameFolder("f", name: "Mac name", now: 5))
        let phone = try XCTUnwrap(base.renameFolder("f", name: "Phone name", now: 6))
        XCTAssertEqual(mac.merge(phone.snapshot()).folders.first?.name, "Phone name")
        XCTAssertEqual(phone.merge(mac.snapshot()).folders.first?.name, "Phone name")
    }

    func testOfflineFolderEditsConverge() {
        var rng = SystemRandomNumberGeneratorSeeded(seed: 54)
        for round in 0..<150 {
            var mac = ShelfState()
            var phone = ShelfState()
            var clock: Int64 = 1
            for _ in 0..<40 {
                let onMac = rng.next() % 2 == 0
                var s = onMac ? mac : phone
                let ids = s.items.map(\.id)
                let fids = s.folders.map(\.id)
                switch rng.next() % 8 {
                case 0, 1: s = s.add(item("r\(round)x\(rng.next() % 10)", clock, from: onMac ? "mac" : "phone"))
                case 2: if let id = ids.randomElement(using: &rng) { s = s.remove(id, now: clock) }
                case 3: s = s.createFolder(id: "f\(rng.next() % 4)", name: "F\(rng.next() % 3)", now: clock) ?? s
                case 4: if let f = fids.randomElement(using: &rng) { s = s.renameFolder(f, name: "N\(rng.next() % 5)", now: clock) ?? s }
                case 5: if let f = fids.randomElement(using: &rng) { s = s.deleteFolder(f, now: clock) }
                case 6:
                    if let id = ids.randomElement(using: &rng) {
                        s = s.moveTo(id, folder: rng.next() % 3 == 0 ? nil : fids.randomElement(using: &rng), now: clock) ?? s
                    }
                default: if let id = ids.randomElement(using: &rng) { s = s.move(id, to: Int(rng.next() % 5), now: clock) }
                }
                clock += 1
                if onMac { mac = s } else { phone = s }
                if rng.next() % 6 == 0 {
                    let m = mac
                    mac = mac.merge(phone.snapshot())
                    phone = phone.merge(m.snapshot())
                }
            }
            let a = mac.merge(phone.snapshot())
            let b = phone.merge(mac.snapshot())
            XCTAssertEqual(a.ordered, b.ordered)
            XCTAssertEqual(a.orderedFolders, b.orderedFolders)
            XCTAssertEqual(a.items(in: nil).map(\.id), b.items(in: nil).map(\.id))
        }
    }

    // MARK: wire

    func testOldPeersGetNoFolderFields() throws {
        let s = try XCTUnwrap(ShelfState().add(item("a", 1)).createFolder(id: "f", name: "F", now: 2)?.moveTo("a", folder: "f", now: 3))
        let stripped = ShelfFolders.forPeer(s.snapshot(), folders: false)
        XCTAssertNil(stripped.items[0].folderId)
        XCTAssertTrue(stripped.folders.isEmpty)
        let json = String(decoding: try JSONEncoder().encode(stripped.items[0]), as: UTF8.self)
        XCTAssertFalse(json.contains("folderId"))
        XCTAssertEqual(ShelfFolders.forPeer(s.snapshot(), folders: true), s.snapshot())
    }

    func testFiveHundredItemSnapshotIsPagedUnderTheRecordLimit() throws {
        var s = ShelfState()
        let body = String(repeating: "x", count: 3_900)
        for i in 0..<500 {
            s = s.add(ShelfItem(id: "i\(i)", kind: ShelfKinds.text, title: "t", body: body, from: "mac", created: Int64(i),
                                updated: Int64(i), order: 0))
        }
        XCTAssertEqual(s.items.count, 500)
        let pages = ShelfFolders.pages(s.snapshot(), size: ShelfFolders.jsonSize)
        XCTAssertGreaterThan(pages.count, 10)
        for p in pages { XCTAssertLessThan(try MessageCodec.encode(.shelfSync(p)).count, 64 * 1024) }
        var other = ShelfState()
        for p in pages { other = other.merge(p) }
        XCTAssertEqual(other.ordered, s.ordered)
    }

    // MARK: offline copies

    private func e(_ id: String, _ mb: Int64, local: Bool = true, used: Int64, pinned: Bool = false, own: Bool = false) -> ShelfOffline.Entry {
        ShelfOffline.Entry(id: id, size: mb * ShelfOffline.mb, local: local, lastUsed: used, pinned: pinned, own: own)
    }

    func testLRUEvictionDropsLeastRecentlyUsed() {
        let files = [e("old", 40, used: 1), e("mid", 40, used: 2), e("new", 40, used: 3)]
        XCTAssertEqual(ShelfOffline.plan(files, cap: 100 * ShelfOffline.mb).evict, ["old"])
        XCTAssertEqual(ShelfOffline.plan(files, cap: 200 * ShelfOffline.mb).evict, [])
        // Opening "old" makes it the most recent; then "mid" goes.
        let opened = [e("old", 40, used: 9), e("mid", 40, used: 2), e("new", 40, used: 3)]
        XCTAssertEqual(ShelfOffline.plan(opened, cap: 100 * ShelfOffline.mb).evict, ["mid"])
    }

    func testPinsAndOwnFilesAreNeverEvicted() {
        let files = [e("pin", 80, used: 0, pinned: true), e("own", 80, used: 0, own: true), e("other", 10, used: 5)]
        let p = ShelfOffline.plan(files, cap: 50 * ShelfOffline.mb)
        XCTAssertEqual(p.evict, ["other"])
        XCTAssertEqual(ShelfOffline.plan(files, cap: 0).evict, ["other"], "Off keeps pinned and own copies")
    }

    func testFetchPlanRespectsCapAnd100MB() {
        let files = [e("a", 30, local: false, used: 3), e("b", 30, local: false, used: 2), e("big", 150, local: false, used: 9),
                     e("pin", 20, local: false, used: 0, pinned: true)]
        XCTAssertEqual(Set(ShelfOffline.plan(files, cap: 64 * ShelfOffline.mb).fetch), ["a", "pin"])
        XCTAssertEqual(ShelfOffline.plan(files, cap: 0).fetch, ["pin"])
    }

    func testFolderPinAndLabels() throws {
        let s = try XCTUnwrap(ShelfState().add(item("a", 1, file: ShelfFile(name: "a.pdf", size: 5, mime: "", sha256: "", owner: "phone")))
            .createFolder(id: "f", name: "F", now: 2)?.moveTo("a", folder: "f", now: 3))
        let pins = ShelfOffline.Pins().togglingFolder("f")
        XCTAssertTrue(ShelfOffline.entries(s, local: [], pins: pins, me: "mac")[0].pinned)
        XCTAssertFalse(ShelfOffline.entries(s, local: [], pins: pins.togglingFolder("f"), me: "mac")[0].pinned)
        XCTAssertEqual(ShelfOffline.usageLabel(used: 312 * ShelfOffline.mb, cap: ShelfOffline.gb), "312 MB of 1 GB")
        XCTAssertEqual(ShelfOffline.usageLabel(used: 0, cap: 0), "0 MB · Off")
        XCTAssertEqual(ShelfOffline.capOptions.map(ShelfOffline.capLabel), ["500 MB", "1 GB", "2 GB", "5 GB", "Off"])
        XCTAssertEqual(ShelfOffline.macDefault, 2 * ShelfOffline.gb)
    }
}

/// Deterministic generator for the convergence test (SplitMix64).
struct SystemRandomNumberGeneratorSeeded: RandomNumberGenerator {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// 0.5.4 bug: a snippet's detail showed its title, then the same line again as the body's first line.
final class ShelfDetailTextTests: XCTestCase {
    func testSnippetDetailNeverRepeatsTheTitle() {
        let g = ShelfDetect.classify("adb mdns services\nadb connect 192.168.1.4:5555")
        let item = ShelfState.item(from: "adb mdns services\nadb connect 192.168.1.4:5555", id: "s", from: "mac", now: 1)
        let d = ShelfCardText.detail(item)
        XCTAssertEqual(g.kind, ShelfKinds.snippet)
        XCTAssertEqual(d.title, "adb mdns services")
        XCTAssertFalse(d.preview.hasPrefix("adb mdns services"), d.preview)
        XCTAssertTrue(d.preview.contains("adb connect"))
        let wifi = ShelfItem(id: "w", kind: ShelfKinds.wifi, title: "Lab", body: "Lab", secret: "pw", from: "mac", created: 1, updated: 1, order: 0)
        XCTAssertEqual(ShelfCardText.detail(wifi).title, "Lab")
        let renamed = ShelfItem(id: "r", kind: ShelfKinds.snippet, title: "Deploy", body: "git push\nfly deploy", from: "mac", created: 1,
                                updated: 1, order: 0)
        XCTAssertEqual(ShelfCardText.detail(renamed), ShelfCardText.Parts(title: "Deploy", preview: "git push\nfly deploy"))
    }
}
