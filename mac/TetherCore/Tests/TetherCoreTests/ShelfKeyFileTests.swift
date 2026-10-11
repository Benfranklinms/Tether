import Foundation
import XCTest
@testable import TetherCore

/// 0.5.4: the Shelf key moved from the Keychain (prompted after every ad-hoc-signed update) to a 0600 file.
final class ShelfKeyFileTests: XCTestCase {
    private var dir: URL!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("shelfkey-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() { try? FileManager.default.removeItem(at: dir) }

    private var url: URL { dir.appendingPathComponent("Tether/shelf.key") }

    private func mode(_ u: URL) -> Int {
        ((try? FileManager.default.attributesOfItem(atPath: u.path))?[.posixPermissions] as? NSNumber)?.intValue ?? -1
    }

    func testMigratesTheLegacyKeyOnceThenReadsTheFile() throws {
        let old = Data(repeating: 7, count: 32)
        var asked = 0
        let (k, src) = try ShelfKeyFile.resolve(url: url, hadOldData: true, legacy: { asked += 1; return old }, newKey: { Data(count: 32) })
        XCTAssertEqual(k, old)
        XCTAssertEqual(src, .migrated)
        XCTAssertEqual(mode(url) & 0o777, 0o600)
        let (k2, src2) = try ShelfKeyFile.resolve(url: url, hadOldData: true, legacy: { asked += 1; return nil },
                                                  newKey: { Data(count: 32) })
        XCTAssertEqual(k2, old)
        XCTAssertEqual(src2, .file)
        XCTAssertEqual(asked, 1, "the Keychain is never asked again once the file exists")
    }

    func testDeniedMigrationFallsBackToAFreshKey() throws {
        let fresh = Data(repeating: 9, count: 32)
        let (k, src) = try ShelfKeyFile.resolve(url: url, hadOldData: true, legacy: { nil }, newKey: { fresh })
        XCTAssertEqual(k, fresh)
        XCTAssertEqual(src, .fresh(lostOld: true))
        XCTAssertEqual(ShelfKeyFile.load(url), fresh)
        let (_, first) = try ShelfKeyFile.resolve(url: dir.appendingPathComponent("b/shelf.key"), hadOldData: false, legacy: { nil },
                                                  newKey: { fresh })
        XCTAssertEqual(first, .fresh(lostOld: false))
    }

    func testDamagedFileIsIgnoredAndLoosePermissionsAreTightened() throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data([1, 2, 3]).write(to: url)
        XCTAssertNil(ShelfKeyFile.load(url))
        try Data(repeating: 1, count: 32).write(to: url)
        try FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: url.path)
        XCTAssertNotNil(ShelfKeyFile.load(url))
        XCTAssertEqual(mode(url) & 0o077, 0)
    }

    func testVaultOpensWithTheMigratedKey() throws {
        let old = ShelfVault.newKey()
        let sealed = try ShelfVault.seal(ShelfState().add(ShelfItem(id: "a", kind: ShelfKinds.text, title: "t", from: "mac", created: 1,
                                                                    updated: 1, order: 0)), key: old)
        let (k, _) = try ShelfKeyFile.resolve(url: url, hadOldData: true, legacy: { old }, newKey: { ShelfVault.newKey() })
        XCTAssertEqual(try ShelfVault.open(sealed, key: k).items.map(\.id), ["a"])
    }
}
