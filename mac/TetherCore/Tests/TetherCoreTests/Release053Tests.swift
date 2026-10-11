import Foundation
import XCTest
@testable import TetherCore

final class Release053Tests: XCTestCase {
    private func vectors(_ name: String) throws -> Data {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("../../../../protocol/vectors/\(name)")
        return try Data(contentsOf: url)
    }

    // MARK: friendly kinds

    func testFileKindsMatchSharedVectors() throws {
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: try vectors("file-kinds.json")) as? [String: Any])
        let cases = try XCTUnwrap(root["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 15)
        for c in cases {
            let mime = c["mime"] as? String
            let name = c["name"] as? String
            XCTAssertEqual(FileKinds.describe(mime: mime, name: name), c["kind"] as? String, "\(mime ?? "") / \(name ?? "")")
            XCTAssertEqual(FileKinds.isImage(mime: mime, name: name), c["image"] as? Bool, "\(mime ?? "") / \(name ?? "") image")
        }
        XCTAssertEqual(FileKinds.describe(mime: nil, name: nil), "File")
    }

    // MARK: search

    private func file(_ name: String, _ mime: String, _ size: Int64 = 1000) -> ShelfFile {
        ShelfFile(name: name, size: size, mime: mime, sha256: "ab" + name, owner: "mac")
    }

    private var items: [ShelfItem] {
        [
            ShelfItem(id: "1", kind: ShelfKinds.link, title: "Placement form", body: "forms.gle/x7Tq2", from: "mac", created: 1, updated: 1,
                      order: 0),
            ShelfItem(id: "2", kind: ShelfKinds.wifi, title: "MEC-Library", body: "", secret: "s3cretPass", from: "phone", created: 1,
                      updated: 1, order: 1),
            ShelfItem(id: "3", kind: ShelfKinds.file, title: "PES_quiz_answer_script.docx", from: "mac", created: 1, updated: 1, order: 2,
                      file: file("PES_quiz_answer_script.docx",
                                 "application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
            ShelfItem(id: "4", kind: ShelfKinds.address, title: "Govt. Model Engineering College", body: "Thrikkakara, Kochi 682021",
                      from: "mac", created: 1, updated: 1, order: 3),
            ShelfItem(id: "5", kind: ShelfKinds.snippet, title: "adb shell", body: "adb shell pm grant x", from: "mac", created: 1,
                      updated: 1, order: 4),
            ShelfItem(id: "6", kind: ShelfKinds.file, title: "Café menu", from: "phone", created: 1, updated: 1, order: 5,
                      file: file("menu.jpg", "image/jpeg")),
        ]
    }

    private func ids(_ q: String) -> [String] { ShelfSearch.filter(items, query: q).map(\.id) }

    func testSearchCoversTitleContentFileNameAndType() {
        XCTAssertEqual(ids("placement"), ["1"])
        XCTAssertEqual(ids("forms.gle"), ["1"])
        XCTAssertEqual(ids("word"), ["3"])
        XCTAssertEqual(ids("quiz docx"), ["3"])
        XCTAssertEqual(ids("kochi 682021"), ["4"])
        XCTAssertEqual(ids("wifi"), ["2"])
        XCTAssertEqual(ids("snippet"), ["5"])
        XCTAssertEqual(ids("cafe"), ["6"])
        XCTAssertEqual(ids("JPEG"), ["6"])
        XCTAssertEqual(ids("menu.jpg"), ["6"])
        XCTAssertEqual(ids("  "), items.map(\.id))
        XCTAssertEqual(ids("placement wifi"), [])
        XCTAssertEqual(ids("s3cret"), [], "never the Wi-Fi password")
    }

    // MARK: rename

    func testRenameKeepsExtensionAndSyncs() throws {
        let item = items[2]
        let r = try XCTUnwrap(ShelfRename.rename(item, to: "  Quiz answers \n", now: 10))
        XCTAssertEqual(r.title, "Quiz answers")
        XCTAssertEqual(r.file?.name, "Quiz answers.docx")
        XCTAssertEqual(r.updated, 10)
        XCTAssertEqual(ShelfRename.rename(item, to: "Quiz.docx", now: 10)?.file?.name, "Quiz.docx")
        XCTAssertEqual(ShelfRename.rename(item, to: "a/b", now: 10)?.file?.name, "a_b.docx")
        XCTAssertNil(ShelfRename.rename(item, to: "   ", now: 10))
        XCTAssertNil(ShelfRename.rename(item, to: item.title, now: 10))
        XCTAssertNil(ShelfRename.rename(items[1], to: "Library", now: 10)?.file)
        var later = items[0]
        later.updated = 500
        XCTAssertEqual(ShelfRename.rename(later, to: "New", now: 100)?.updated, 501)
        let base = ShelfState(items: [item])
        let mac = base.merge(ShelfSyncPayload(items: [r]))
        XCTAssertEqual(mac.items.first?.title, "Quiz answers")
        XCTAssertEqual(mac.merge(ShelfSyncPayload(items: [item])).items.first?.title, "Quiz answers")
    }

    // MARK: cache

    func testCachePolicy() {
        let small = file("a.pdf", "application/pdf", 500_000)
        let big = file("b.mov", "video/quicktime", 40 * 1024 * 1024)
        XCTAssertTrue(ShelfCache.shouldFetch(small, haveLocal: false, inFlight: false, connected: true, wifi: false))
        XCTAssertFalse(ShelfCache.shouldFetch(big, haveLocal: false, inFlight: false, connected: true, wifi: false))
        XCTAssertTrue(ShelfCache.shouldFetch(big, haveLocal: false, inFlight: false, connected: true, wifi: true))
        XCTAssertFalse(ShelfCache.shouldFetch(file("c", "x", ShelfCache.maxBytes + 1), haveLocal: false, inFlight: false,
                                              connected: true, wifi: true))
        XCTAssertFalse(ShelfCache.shouldFetch(small, haveLocal: true, inFlight: false, connected: true, wifi: true))
        XCTAssertFalse(ShelfCache.shouldFetch(small, haveLocal: false, inFlight: true, connected: true, wifi: true))
        XCTAssertEqual(ShelfCache.state(haveLocal: false, fetching: false, connected: false), .notDownloaded)
        XCTAssertEqual(ShelfCache.state(haveLocal: false, fetching: false, connected: true), .remote)
        XCTAssertEqual(ShelfCache.state(haveLocal: true, fetching: false, connected: false), .local)
    }

    // MARK: at-rest encryption

    private let key = Data((0..<32).map { UInt8($0) })

    func testChunkSealRoundTripsAroundChunkEdges() throws {
        var rng = SystemRandomNumberGenerator()
        for n in [0, 1, 15, 16, 17, ChunkSeal.chunk - 1, ChunkSeal.chunk, ChunkSeal.chunk + 1, 3 * ChunkSeal.chunk, 200_001] {
            let plain = Data((0..<n).map { _ in UInt8.random(in: 0...255, using: &rng) })
            let blob = try ChunkSeal.encrypt(plain, key: key)
            XCTAssertEqual(try ChunkSeal.decrypt(blob, key: key), plain, "size \(n)")
        }
        let blob = try ChunkSeal.encrypt(Data(count: 5 * ChunkSeal.chunk + 123), key: key, prefix: Data(count: 8))
        XCTAssertEqual(blob.count, 12 + 6 * 16 + 5 * ChunkSeal.chunk + 123)
    }

    func testChunkSealRejectsTamperingTruncationAndWrongKey() throws {
        let plain = Data((0..<(3 * ChunkSeal.chunk + 10)).map { UInt8($0 & 0xff) })
        let blob = try ChunkSeal.encrypt(plain, key: key)
        var flipped = blob
        flipped[100] ^= 1
        XCTAssertThrowsError(try ChunkSeal.decrypt(flipped, key: key))
        XCTAssertThrowsError(try ChunkSeal.decrypt(blob.prefix(12 + 2 * (ChunkSeal.chunk + 16)), key: key))
        XCTAssertThrowsError(try ChunkSeal.decrypt(blob.dropLast(), key: key))
        XCTAssertThrowsError(try ChunkSeal.decrypt(blob + Data([0]), key: key))
        XCTAssertThrowsError(try ChunkSeal.decrypt(blob, key: Data(count: 32)))
        XCTAssertThrowsError(try ChunkSeal.decrypt(Data("nope".utf8), key: key))
    }

    func testChunkSealFilesStream() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("cs-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let src = dir.appendingPathComponent("a.bin")
        let plain = Data((0..<300_000).map { UInt8(($0 * 7) & 0xff) })
        try plain.write(to: src)
        try ChunkSeal.encryptFile(src, to: dir.appendingPathComponent("a.sealed"), key: key)
        try ChunkSeal.decryptFile(dir.appendingPathComponent("a.sealed"), to: dir.appendingPathComponent("b.bin"), key: key)
        XCTAssertEqual(try Data(contentsOf: dir.appendingPathComponent("b.bin")), plain)
        XCTAssertNotEqual(try Data(contentsOf: dir.appendingPathComponent("a.sealed")).prefix(4), plain.prefix(4))
    }

    // MARK: capture

    func testCaptureCardTellsTheUserToTapThePhone() {
        XCTAssertEqual(CaptureText.line(kind: CaptureKinds.photo, state: CaptureStates.open, reason: CaptureReasons.tapPhone),
                       "Tap the notification on your phone to open the camera")
        XCTAssertEqual(CaptureText.line(kind: CaptureKinds.photo, state: CaptureStates.open, reason: nil), "Take the photo on your phone")
        XCTAssertEqual(CaptureText.line(kind: CaptureKinds.scan, state: "asking", reason: nil), "Opening the scanner on your phone…")
    }

    // MARK: what's new

    func testWhatsNewPolicyAndBundledNotes() throws {
        XCTAssertEqual(WhatsNewPolicy.decide(current: "0.5.3", seen: nil, usedBefore: true), .show)
        XCTAssertEqual(WhatsNewPolicy.decide(current: "0.5.3", seen: nil, usedBefore: false), .record)
        XCTAssertEqual(WhatsNewPolicy.decide(current: "0.5.3", seen: "0.5.2", usedBefore: true), .show)
        XCTAssertEqual(WhatsNewPolicy.decide(current: "0.5.3", seen: "0.5.3", usedBefore: true), .none)
        XCTAssertEqual(WhatsNewPolicy.decide(current: "0.5.3-dev", seen: "0.5.3", usedBefore: true), .none)
        let md = "# Changelog\n\nintro\n\n## 0.5.3\n\n- **A.** one\n- B\n\n## 0.5.2\n\n- old\n"
        XCTAssertEqual(WhatsNewPolicy.section(md, version: "0.5.3-dev"), "- **A.** one\n- B")
        XCTAssertEqual(WhatsNewPolicy.section(md, version: "0.5.2"), "- old")
        XCTAssertNil(WhatsNewPolicy.section(md, version: "0.4.0"))
        let real = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../../CHANGELOG.md")
        let text = try String(contentsOf: real, encoding: .utf8)
        XCTAssertNotNil(WhatsNewPolicy.section(text, version: "0.5.3"))
    }

    // MARK: transfers

    private let now: Int64 = 10 * 24 * 3_600_000
    private func t(_ id: String, _ ageH: Double, _ dir: Direction = .OUT, _ status: TransferStatus = .DONE) -> TransferRecord {
        let at = now - Int64(ageH * 3_600_000)
        return TransferRecord(id: id, direction: dir, name: "\(id).jpg", size: 10, sha256: "x", mime: "image/jpeg", status: status,
                              createdAt: at, updatedAt: at)
    }

    func testTransferListHomeAndSections() {
        let list = [t("a", 0.1), t("b", 0.2), t("c", 30), t("d", 2, .OUT, .ACTIVE), t("e", 0.3), t("f", 0.05)]
        XCTAssertEqual(TransferList.home(list, now: now).map(\.id), ["d", "f", "a"])
        XCTAssertEqual(TransferList.home([t("old", 25)], now: now).map(\.id), [])
        XCTAssertEqual(TransferList.home([t("r", 50, .OUT, .WAITING)], now: now).map(\.id), ["r"])
        let midnight = now - 5 * 3_600_000
        let l2 = [t("in", 1, .IN), t("out", 2), t("y", 8), t("old", 40, .IN)]
        let s = TransferList.sections(l2, .all, now: now, startOfToday: midnight)
        XCTAssertEqual(s.map(\.title), ["Today", "Earlier"])
        XCTAssertEqual(s[0].items.map(\.id), ["in", "out"])
        XCTAssertFalse(s[1].collapsed)
        let rec = TransferList.sections(l2, .received, now: now, startOfToday: midnight)
        XCTAssertEqual(rec.map { $0.items.map(\.id) }, [["in"], ["old"]])
        XCTAssertTrue(rec[1].collapsed)
    }

    // MARK: new photos

    func testMediaReadyAndBlankThumbnails() {
        XCTAssertFalse(MediaReady.stable(pending: true, size: 15_000, previousSize: 15_000))
        XCTAssertFalse(MediaReady.stable(pending: false, size: 280_000, previousSize: 15_000))
        XCTAssertTrue(MediaReady.stable(pending: false, size: 280_000, previousSize: 280_000))
        XCTAssertTrue(MediaReady.isBlank(Array(repeating: 0, count: 64)))
        XCTAssertTrue(MediaReady.isBlank((0..<64).map { 3 + $0 % 5 }))
        XCTAssertFalse(MediaReady.isBlank((0..<64).map { $0 * 4 }))
        XCTAssertFalse(MediaReady.isBlank(Array(repeating: 240, count: 64)))
        XCTAssertTrue(MediaReady.isBlank([]))
    }

    func testSnippetCardDoesNotRepeatItsTitle() {
        XCTAssertEqual(ShelfCardText.split(title: "git rebase -i", body: "git rebase -i HEAD~3"),
                       .init(title: "git rebase -i HEAD~3", preview: ""))
        XCTAssertEqual(ShelfCardText.split(title: "Deploy", body: "Deploy\nnpm run build\nnpm run ship"),
                       .init(title: "Deploy", preview: "npm run build\nnpm run ship"))
        XCTAssertEqual(ShelfCardText.split(title: "My cmd", body: "git status"), .init(title: "My cmd", preview: "git status"))
    }
}
