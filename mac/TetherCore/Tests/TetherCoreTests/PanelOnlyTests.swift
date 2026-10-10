import XCTest
@testable import TetherCore

final class PanelOnlyTests: XCTestCase {
    func testPanelOnlyDefaultsOffAndFollowsTheStoredKey() throws {
        let suite = "tether.tests.panelOnly.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertEqual(PanelOnlyAlerts.key, "panelOnlyAlerts")
        XCTAssertFalse(PanelOnlyAlerts.isOn(defaults))
        defaults.set(true, forKey: "panelOnlyAlerts")
        XCTAssertTrue(PanelOnlyAlerts.isOn(defaults))
        defaults.set(false, forKey: "panelOnlyAlerts")
        XCTAssertFalse(PanelOnlyAlerts.isOn(defaults))
    }
}
