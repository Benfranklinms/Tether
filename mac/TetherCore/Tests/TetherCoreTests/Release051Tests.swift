import XCTest
@testable import TetherCore

final class Release051Tests: XCTestCase {
    // MARK: clipboard

    func testFinderCopiedImageSendsTheFileNotItsName() {
        // Finder: file URL, the name as text, the icon as TIFF.
        let types = ["public.file-url", "public.utf8-plain-text", "public.tiff"]
        let c = PasteboardClassifier.choose(types: types, filePaths: ["/Users/a/Desktop/Shot 1.PNG"], hasText: true)
        XCTAssertEqual(c, .imageFile("/Users/a/Desktop/Shot 1.PNG"))
    }

    func testFinderCopiedDocumentSendsNothing() {
        let types = ["public.file-url", "public.utf8-plain-text", "public.tiff"]
        XCTAssertEqual(PasteboardClassifier.choose(types: types, filePaths: ["/tmp/Report.pdf"], hasText: true), .skip)
    }

    func testImageDataBeatsTextWhenDeclaredFirst() {
        XCTAssertEqual(PasteboardClassifier.choose(types: ["public.tiff", "public.utf8-plain-text"], filePaths: [], hasText: true),
                       .imageData)
        XCTAssertEqual(PasteboardClassifier.choose(types: ["public.png"], filePaths: [], hasText: false), .imageData)
    }

    func testRichTextWithPictureStaysText() {
        let types = ["public.rtf", "public.utf8-plain-text", "public.tiff"]
        XCTAssertEqual(PasteboardClassifier.choose(types: types, filePaths: [], hasText: true), .text)
        XCTAssertEqual(PasteboardClassifier.choose(types: ["public.utf8-plain-text"], filePaths: [], hasText: false), .skip)
    }

    func testImageExtensions() {
        XCTAssertTrue(PasteboardClassifier.isImagePath("/a/b.HEIC"))
        XCTAssertTrue(PasteboardClassifier.isImagePath("x.jpeg"))
        XCTAssertFalse(PasteboardClassifier.isImagePath("x.mov"))
        XCTAssertFalse(PasteboardClassifier.isImagePath("folder"))
    }

    // MARK: typing

    func testTypeTrackerTimesOutWithoutAnswer() {
        var t = TypeTracker()
        t.sent(seq: 1, chars: 3, now: 1000)
        XCTAssertFalse(t.timedOut(now: 2000))
        XCTAssertTrue(t.timedOut(now: 1000 + TypeTracker.timeoutMs))
        XCTAssertTrue(t.pending.isEmpty)
        XCTAssertEqual(t.sentChars, 3)
    }

    func testTypeTrackerAnswered() {
        var t = TypeTracker()
        t.sent(seq: 7, chars: 1, now: 0)
        XCTAssertTrue(t.answered(seq: 7))
        XCTAssertFalse(t.answered(seq: 7))
        XCTAssertFalse(t.timedOut(now: 99_999))
    }

    func testTypeReasonsAreSentences() {
        XCTAssertTrue(TypeTracker.describe("no_service").contains("Accessibility"))
        XCTAssertTrue(TypeTracker.describe("no_field").contains("text field"))
        XCTAssertTrue(TypeTracker.describe("weird").contains("weird"))
    }

    func testTypeAccessFromPhoneStatus() {
        XCTAssertEqual(TypeAccess.from(nil), .unknown)
        XCTAssertEqual(TypeAccess.from([PermInfo(id: "accessibility", label: "A", ok: false)]), .off)
        XCTAssertEqual(TypeAccess.from([PermInfo(id: "accessibility", label: "A", ok: true)]), .on)
        XCTAssertEqual(PhoneSetupLink.url(for: "accessibility"), "tether://setup/accessibility")
    }

    // MARK: calls

    func testCallCaptionShowsOneLine() {
        let unsupported = "Your phone doesn't let other apps do that during a call."
        XCTAssertNil(CallCaption.line(canMute: true, muteBlocked: false, note: nil, unsupportedNote: unsupported))
        XCTAssertEqual(CallCaption.line(canMute: true, muteBlocked: true, note: unsupported, unsupportedNote: unsupported),
                       CallCaption.muteUnavailable)
        XCTAssertEqual(CallCaption.line(canMute: false, muteBlocked: false, note: nil, unsupportedNote: unsupported),
                       CallCaption.muteUnavailable)
        XCTAssertEqual(CallCaption.line(canMute: true, muteBlocked: false, note: "Ending…", unsupportedNote: unsupported), "Ending…")
    }

    func testCallBannerTopRight() {
        let o = CallBannerPlacement.origin(visible: (0, 0, 1440, 875), size: (360, 150))
        XCTAssertEqual(o.x, 1440 - 360 - 14)
        XCTAssertEqual(o.y, 875 - 150 - 14)
    }

    // MARK: setup

    private func entries(_ missing: [String]) -> [SetupEntry] {
        ["nearby", "notif", "battery", "accessibility", "readlogs", "sensitive"].map {
            SetupPlan.phoneEntry(PermInfo(id: $0, label: $0, ok: !missing.contains($0), required: true))
        }
    }

    func testAdbAndAccessibilityAreOptionalAndGroupedByFeature() {
        let e = entries(["battery", "accessibility", "readlogs", "sensitive"])
        XCTAssertEqual(SetupPlan.missingRequired(e).map(\.id), ["phone-battery"])
        let groups = SetupPlan.optionalByFeature(e)
        XCTAssertEqual(groups.map(\.feature), ["Typing & screen", "Clipboard", "Codes"])
        XCTAssertEqual(SetupPlan.summary(e), "Setup · 1 left")
        XCTAssertEqual(SetupPlan.progress(e), 2.0 / 3.0, accuracy: 0.001)
        XCTAssertTrue(e.first { $0.id == "phone-accessibility" }!.unlocks.contains("Type from Mac"))
        XCTAssertFalse(e.contains { $0.unlocks.contains("Allow it in Tether") })
    }

    func testHomeGateHideAndRevoke() {
        var g = SetupHomeGate()
        XCTAssertTrue(g.shows(missingRequired: ["battery"]))
        g.hide(missingRequired: ["battery"])
        XCTAssertFalse(g.shows(missingRequired: ["battery"]))
        // A different required permission is revoked: the row comes back.
        XCTAssertTrue(g.shows(missingRequired: ["battery", "notif"]))
        // Everything done: hidden and the hide is forgotten.
        g.update(missingRequired: [])
        XCTAssertFalse(g.shows(missingRequired: []))
        XCTAssertNil(g.hiddenMissing)
        XCTAssertTrue(g.shows(missingRequired: ["battery"]))
    }

    // MARK: galleries

    func testGallerySizeClampsAndColumns() {
        XCTAssertEqual(GallerySize.clamp(10), GallerySize.min)
        XCTAssertEqual(GallerySize.clamp(999), GallerySize.max)
        XCTAssertEqual(GallerySize.bigger(120), 140)
        XCTAssertEqual(GallerySize.smaller(100), 90)
        XCTAssertEqual(GallerySize.columns(width: 800, tile: 120, spacing: 6), 6)
        XCTAssertEqual(GallerySize.columns(width: 50, tile: 120, spacing: 6), 1)
    }

    func testFilesViewModeRememberedPerRoot() {
        var store: [String: String] = [:]
        XCTAssertEqual(FilesViewMode.load(root: "downloads") { store[$0] }, .list)
        XCTAssertEqual(FilesViewMode.load(root: "dcim") { store[$0] }, .grid)
        store[FilesViewMode.key(root: "downloads")] = "grid"
        XCTAssertEqual(FilesViewMode.load(root: "downloads") { store[$0] }, .grid)
        XCTAssertEqual(FilesViewMode.load(root: "documents") { store[$0] }, .list)
    }

    func testStaggerAndDebounce() {
        XCTAssertEqual(Stagger.delay(index: 0), 0)
        XCTAssertEqual(Stagger.delay(index: 1000), 0.3)
        var d = Debounce(intervalMs: 180)
        XCTAssertFalse(d.shouldFire(now: 0))
        d.input(now: 100)
        XCTAssertFalse(d.shouldFire(now: 200))
        d.input(now: 250)
        XCTAssertTrue(d.shouldFire(now: 430))
        XCTAssertFalse(d.shouldFire(now: 900))
    }
}

final class Release051GalleryTests: XCTestCase {
    private var cal: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Asia/Kolkata")!
        return c
    }

    private func item(_ id: String, _ d: Date) -> PhotoItem { PhotoItem(id: id, name: id, date: Int64(d.timeIntervalSince1970 * 1000)) }

    func testSections() {
        let now = cal.date(from: DateComponents(year: 2026, month: 10, day: 10, hour: 19))!
        let items = [
            item("a", now.addingTimeInterval(-3600)),
            item("b", now.addingTimeInterval(-86_400)),
            item("c", now.addingTimeInterval(-3 * 86_400)),
            item("d", cal.date(from: DateComponents(year: 2026, month: 9, day: 2))!),
            item("e", cal.date(from: DateComponents(year: 2025, month: 1, day: 2))!),
        ]
        let s = PhotoSections.group(items, now: now, calendar: cal)
        XCTAssertEqual(s.map(\.title), ["Today", "Yesterday", "This week", "September 2026", "January 2025"])
        XCTAssertEqual(PhotoSections.group(items, zoom: .years, now: now, calendar: cal).map(\.title), ["2026", "2025"])
        XCTAssertEqual(PhotoSections.group(items, zoom: .months, now: now, calendar: cal).count, 3)
    }

    func testDuration() {
        XCTAssertEqual(PhotoSections.duration(42_000), "0:42")
        XCTAssertEqual(PhotoSections.duration(3_723_000), "1:02:03")
    }

    func testSelection() {
        let order = ["1", "2", "3", "4", "5"]
        var s = GridSelection()
        s.click("2", mode: .plain, order: order)
        s.click("4", mode: .range, order: order)
        XCTAssertEqual(s.ids, ["2", "3", "4"])
        s.click("3", mode: .toggle, order: order)
        XCTAssertEqual(s.ids, ["2", "4"])
        s.drag(covering: ["5"], additive: true)
        XCTAssertEqual(s.ids, ["2", "4", "5"])
        s.drag(covering: ["1"], additive: false)
        XCTAssertEqual(s.ids, ["1"])
        XCTAssertEqual(GridSelection.neighbour(of: "5", in: order, step: 1), "5")
        XCTAssertEqual(GridSelection.neighbour(of: "2", in: order, step: -1), "1")
    }

    func testThumbPixels() {
        XCTAssertEqual(ThumbPixels.forTile(120), 256)
        XCTAssertEqual(ThumbPixels.forTile(40), 128)
        XCTAssertEqual(ThumbPixels.forTile(500), 768)
    }

    func testAlbumsCodecAndOldPhotoItems() throws {
        let album = AlbumInfo(id: "camera", name: "Camera", count: 3, cover: "9", kind: AlbumKinds.camera)
        let m = Message.albums(AlbumsPayload(ref: "r", albums: [album]))
        let back = try MessageCodec.decode(try MessageCodec.encode(m))
        XCTAssertEqual(back, m)
        let req = Message.photosReq(PhotosReq(ref: "x", limit: 10, album: "camera", filter: PhotoFilters.videos))
        XCTAssertEqual(try MessageCodec.decode(try MessageCodec.encode(req)), req)
        // A 0.5.0 phone's PhotoItem (no dur/bucket/fav) still decodes.
        let old = try JSONDecoder().decode(PhotoItem.self, from: Data(#"{"id":"1","name":"a.jpg"}"#.utf8))
        XCTAssertEqual(old.dur, 0)
        XCTAssertFalse(old.fav)
    }
}
