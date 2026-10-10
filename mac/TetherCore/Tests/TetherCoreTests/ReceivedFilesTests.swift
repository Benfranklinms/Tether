import Foundation
import XCTest
@testable import TetherCore

final class ReceivedFilesTests: XCTestCase {
    private func record(_ id: String, _ dir: Direction, _ status: TransferStatus, ref: String?, kind: String? = "photo") -> TransferRecord {
        TransferRecord(id: id, direction: dir, name: "\(id).jpg", size: 1, sha256: "", mime: "image/jpeg", status: status,
                       createdAt: 0, ref: ref, kind: kind)
    }

    func testOnlyFinishedIncomingFilesThatAreGoneAreDropped() {
        let records = [
            record("kept", .IN, .DONE, ref: "/x/kept.jpg"),
            record("deleted", .IN, .DONE, ref: "/x/deleted.jpg"),
            record("deletedFile", .IN, .DONE, ref: "/x/doc.pdf", kind: nil),
            record("receiving", .IN, .ACTIVE, ref: "/x/partial.jpg"),
            record("sent", .OUT, .DONE, ref: "/x/staged.jpg"),
            record("noRef", .IN, .DONE, ref: nil),
        ]
        let existing: Set<String> = ["/x/kept.jpg"]
        XCTAssertEqual(ReceivedFiles.missing(records) { existing.contains($0) }, ["deleted", "deletedFile"])
        XCTAssertTrue(ReceivedFiles.missing([]) { _ in false }.isEmpty)
    }

    func testRealFileDeletedOnDisk() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("tether-rf-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let a = dir.appendingPathComponent("a.jpg"), b = dir.appendingPathComponent("b.jpg")
        try Data([1]).write(to: a)
        try Data([2]).write(to: b)
        let records = [record("a", .IN, .DONE, ref: a.path), record("b", .IN, .DONE, ref: b.path)]
        let exists: (String) -> Bool = { FileManager.default.fileExists(atPath: $0) }
        XCTAssertTrue(ReceivedFiles.missing(records, exists: exists).isEmpty)
        try FileManager.default.removeItem(at: b)
        XCTAssertEqual(ReceivedFiles.missing(records, exists: exists), ["b"])
    }
}
