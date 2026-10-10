import XCTest
@testable import TetherCore

enum Rev5Samples {
    static let jpeg = Data((0..<9000).map { UInt8($0 * 31 % 251) })

    static var all: [Message] {
        [
            .otp(OtpPayload(code: "482913", app: "HDFC Bank", src: "sms", ts: 1)),
            .fsListReq(FsListReq(ref: "l1", root: FsRoots.downloads, path: "Telegram/Docs", offset: 200, limit: 100)),
            .fsList(FsListPayload(ref: "l1", root: FsRoots.whatsapp, path: "WhatsApp Images",
                                  entries: (0..<150).map { FsEntry(name: "IMG-\($0).jpg — ✓", dir: $0 % 10 == 0, size: Int64($0) * 1000,
                                                                   mtime: 1_700_000_000_000 + Int64($0), mime: "image/jpeg") },
                                  total: 400, more: true)),
            .fsList(FsListPayload(ref: "l2", root: FsRoots.documents, path: "", entries: [], needs: "allfiles")),
            .fsGet(FsGetPayload(ref: "g1", root: FsRoots.photos, path: "1001")),
            .fsGetResult(FsGetResultPayload(ref: "g1", ok: true, transferId: "t-1", name: "IMG_1.jpg", size: 3_100_000)),
            .fsGetResult(FsGetResultPayload(ref: "g2", ok: false, reason: "missing")),
            .thumbReq(ThumbReq(ref: "t1", root: FsRoots.photos, path: "1001")),
            .thumb(ThumbPayload(ref: "t1", ok: true, data: jpeg)),
            .thumb(ThumbPayload(ref: "t2", ok: false, reason: "unsupported")),
            .photosReq(PhotosReq(ref: "p1", limit: 60, before: 1_700_000_000_000)),
            .photos(PhotosPayload(ref: "p1", items: (0..<60).map { PhotoItem(id: "\(1000 + $0)", name: "IMG_\($0).jpg", size: 2_000_000,
                                                                             date: 1_700_000_000_000 - Int64($0), w: 4000, h: 3000) },
                                  more: true)),
            .photos(PhotosPayload(ref: "p2", items: [], needs: "photos")),
            .continueCard(ContinuePayload(id: "tab", kind: ContinueKinds.tab, title: "Placement drive: Armada 2027",
                                          subtitle: "careers.armada.ai/apply", url: "https://careers.armada.ai/apply", app: "Chrome",
                                          ts: 5, ttl: 600), thumb: Data()),
            .continueCard(ContinuePayload(id: "photo-1001", kind: ContinueKinds.photo, title: "IMG_20261010_151502.jpg",
                                          subtitle: "Photo · 3.1 MB", root: FsRoots.photos, path: "1001", size: 3_100_000, ts: 6),
                          thumb: jpeg),
            .openOnPhone(OpenOnPhonePayload(id: "o1", url: "https://maps.google.com/?q=MEC+Kochi")),
            .openOnPhone(OpenOnPhonePayload(id: "o2", text: "Hello from the Mac ✓")),
            .openResult(OpenResultPayload(id: "o1", ok: true, via: "activity")),
            .openResult(OpenResultPayload(id: "o3", ok: false, reason: "no_app")),
            .typeText(TypeTextPayload(seq: 1, text: "Hello, ✓ 😀")), .typeText(TypeTextPayload(seq: 2, key: TypeKeys.backspace)),
            .typeResult(TypeResultPayload(seq: 1, ok: true, field: "Chrome · Search")),
            .typeResult(TypeResultPayload(seq: 2, ok: false, reason: "no_field")),
            .dndState(DndStatePayload(on: true, src: "mac", ts: 7, canSet: false)),
            .dndState(DndStatePayload(on: false, src: "phone", ts: 8, canSet: true)),
            .phoneStatus(PhoneStatusPayload(perms: [PermInfo(id: "notif", label: "Notification access", ok: true, required: true),
                                                    PermInfo(id: "allfiles", label: "All files access", ok: false)], sdk: 36, ts: 9)),
            .phoneStatusReq(ts: 10),
        ]
    }
}

final class Features5CodecTests: XCTestCase {
    func testRevision5RoundTrips() throws {
        for m in Rev5Samples.all {
            XCTAssertEqual(try MessageCodec.decode(try MessageCodec.encode(m)), m)
        }
    }

    func testTypeNumbersMatchKotlin() {
        let expected: [UInt8] = [0x32, 0xC0, 0xC1, 0xC1, 0xC2, 0xC3, 0xC3, 0xC4, 0xC5, 0xC5, 0xC6, 0xC7, 0xC7, 0xC8, 0xC8, 0xC9, 0xC9,
                                 0xCA, 0xCA, 0xCB, 0xCB, 0xCC, 0xCC, 0xE0, 0xE0, 0xE1, 0xE2]
        XCTAssertEqual(Rev5Samples.all.map(\.type), expected)
    }

    func testCapsAnnounced() {
        XCTAssertTrue(Set(Caps.current).isSuperset(of: Caps5.all))
        XCTAssertEqual(Caps.current.count, Set(Caps.current).count)
    }

    func testThumbBodiesAreBounded() throws {
        let big = Message.thumb(ThumbPayload(ref: "x", ok: true, data: Data(count: MessageCodec.maxThumbBytes + 1)))
        XCTAssertThrowsError(try MessageCodec.decode(try MessageCodec.encode(big)))
    }

    func testDefaultsForMissingFieldsAndUnknownFieldsIgnored() throws {
        let header = Data(#"{"ref":"l","root":"downloads","entries":[{"name":"a","future":1}],"newField":true}"#.utf8)
        var d = Data([0xC1])
        d.appendU32(UInt32(header.count))
        d.append(header)
        guard case .fsList(let p) = try MessageCodec.decode(d) else { return XCTFail("expected fsList") }
        XCTAssertEqual(p.path, "")
        XCTAssertEqual(p.entries.first?.dir, false)
        XCTAssertNil(p.needs)
    }
}

final class Mac5LogicTests: XCTestCase {
    func testVersions() {
        XCTAssertTrue(Versions.newer("0.5.0", than: "0.4.3"))
        XCTAssertTrue(Versions.newer("v0.5.1", than: "0.5.0"))
        XCTAssertTrue(Versions.newer("0.5.0", than: "0.5.0-dev"))
        XCTAssertTrue(Versions.newer("0.4.3", than: "0.0.0-ci.120"))
        XCTAssertFalse(Versions.newer("0.5.0", than: "0.5.0"))
        XCTAssertFalse(Versions.newer("0.4.10", than: "0.10.0"))
        XCTAssertEqual(Versions.compare("1.0", "1.0.0"), 0)
    }

    func testFeedAndGitHubParsing() {
        let feed = Data("""
        {"schema":1,"version":"0.5.0","notes":"Big","url":"https://github.com/x/r/releases/tag/v0.5.0",
         "android":{"url":"https://x/a.apk","sha256":"ab","size":1,"versionCode":51},
         "mac":{"url":"https://x/T.zip","sha256":"CD34","size":4000000,"dmg":"https://x/T.dmg"},"future":1}
        """.utf8)
        let u = UpdateFeed.parse(feed)
        XCTAssertEqual(u?.version, "0.5.0")
        XCTAssertEqual(u?.macZip?.sha256, "cd34")
        XCTAssertEqual(u?.macZip?.size, 4_000_000)
        XCTAssertEqual(u?.macDmg?.url, "https://x/T.dmg")
        XCTAssertNil(UpdateFeed.parse(Data(#"{"version":"1.0","mac":{"url":"http://evil/T.zip"}}"#.utf8))?.macZip)
        XCTAssertNil(UpdateFeed.parse(Data("nope".utf8)))
        let gh = Data("""
        {"tag_name":"v0.5.0","draft":false,"prerelease":false,"body":"N","html_url":"https://github.com/r",
         "assets":[{"name":"Tether-0.5.0.zip","size":4000000,"digest":"sha256:AABB","browser_download_url":"https://g/T.zip"},
                   {"name":"Tether-0.5.0.dmg","size":4100000,"browser_download_url":"https://g/T.dmg"}]}
        """.utf8)
        let g = UpdateFeed.parse(gh)
        XCTAssertEqual(g?.version, "0.5.0")
        XCTAssertEqual(g?.macZip?.sha256, "aabb")
        XCTAssertNil(g?.macDmg?.sha256)
        XCTAssertNil(UpdateFeed.parse(Data(#"{"tag_name":"v9","prerelease":true}"#.utf8)))
        XCTAssertNil(UpdateFeed.parse(Data(#"{"message":"Not Found"}"#.utf8)))
    }

    func testClipHistory() {
        var h = ClipHistory(limit: 3)
        for i in 0..<3 { h.add(ClipItem(kind: .text, text: "t\(i)", direction: .fromMac, ts: Int64(i), hash: "h\(i)")) }
        XCTAssertEqual(h.items.map(\.text), ["t2", "t1", "t0"])
        // Same content again moves to the top.
        h.add(ClipItem(kind: .text, text: "t0", direction: .fromPhone, ts: 9, hash: "h0"))
        XCTAssertEqual(h.items.map(\.text), ["t0", "t2", "t1"])
        XCTAssertEqual(h.items.first?.direction, .fromPhone)
        let dropped = h.add(ClipItem(kind: .image, image: "a.png", direction: .fromPhone, ts: 10, hash: "img"))
        XCTAssertEqual(dropped.map(\.text), ["t1"])
        XCTAssertEqual(h.items.count, 3)
        XCTAssertNotNil(h.remove(id: h.items[0].id))
        XCTAssertEqual(h.clear().count, 2)
        XCTAssertTrue(h.items.isEmpty)
    }

    func testContinueStore() {
        var s = ContinueStore(limit: 2)
        let a = ContinuePayload(id: "tab", kind: ContinueKinds.tab, title: "A", url: "https://a", ts: 1, ttl: 60)
        XCTAssertTrue(s.add(a, thumb: Data(), now: 0))
        XCTAssertFalse(s.add(a, thumb: Data(), now: 10)) // refresh of the same thing
        var b = a
        b.url = "https://b"
        XCTAssertTrue(s.add(b, thumb: Data(), now: 20)) // same id, new tab: replaces and is new
        XCTAssertEqual(s.cards.count, 1)
        s.add(ContinuePayload(id: "p", kind: ContinueKinds.photo, title: "P", path: "1", ts: 1, ttl: 600), thumb: Data(), now: 30)
        s.add(ContinuePayload(id: "d", kind: ContinueKinds.download, title: "D", path: "x", ts: 1, ttl: 600), thumb: Data(), now: 40)
        XCTAssertEqual(s.cards.map(\.id), ["d", "p"]) // limit 2
        s.dismiss("d")
        XCTAssertFalse(s.add(ContinuePayload(id: "d", kind: ContinueKinds.download, title: "D", path: "x", ts: 1), thumb: Data(), now: 50))
        XCTAssertEqual(s.cards.map(\.id), ["p"])
        XCTAssertFalse(s.expire(now: 100_000))
        XCTAssertTrue(s.expire(now: 30 + 601_000))
        XCTAssertTrue(s.cards.isEmpty)
    }

    func testLinksAreSafe() {
        XCTAssertNotNil(SafeLinks.url("https://example.com/a?b=1"))
        XCTAssertNil(SafeLinks.url("file:///etc/passwd"))
        XCTAssertNil(SafeLinks.url("javascript:alert(1)"))
        XCTAssertNil(SafeLinks.url(""))
        XCTAssertTrue(SafeLinks.isWebLink(" https://careers.armada.ai/apply "))
        XCTAssertFalse(SafeLinks.isWebLink("see https://x.com"))
        XCTAssertFalse(SafeLinks.isWebLink("mailto:a@b.c"))
        XCTAssertEqual(SongLinks.spotify(title: "Kesariya", artist: "Arijit Singh")?.absoluteString,
                       "spotify:search:Kesariya%20Arijit%20Singh")
        XCTAssertEqual(SongLinks.youTubeMusic(title: "A & B", artist: nil)?.absoluteString,
                       "https://music.youtube.com/search?q=A%20%26%20B")
        XCTAssertFalse(SongLinks.prefersSpotify(app: "YouTube Music"))
        XCTAssertTrue(SongLinks.prefersSpotify(app: "Spotify"))
    }

    func testChargeComplete() {
        var m = ChargeCompleteMonitor()
        XCTAssertFalse(m.update(level: 100, charging: true)) // plugged in already full at launch: not armed
        XCTAssertFalse(m.update(level: 98, charging: false))
        XCTAssertFalse(m.update(level: 99, charging: true))
        XCTAssertTrue(m.update(level: 100, charging: true))
        XCTAssertFalse(m.update(level: 100, charging: true)) // once per charge
        XCTAssertFalse(m.update(level: 94, charging: true)) // trickle re-arms below 95
        XCTAssertTrue(m.update(level: 100, charging: true))
    }

    func testCallMediaPolicy() {
        var p = CallMediaPolicy(duckTo: 0.2)
        XCTAssertNil(p.phone(busy: false))
        XCTAssertEqual(p.phone(busy: true), .quiet(duckTo: 0.2))
        XCTAssertNil(p.phone(busy: true)) // ringing → active: still busy
        XCTAssertEqual(p.phone(busy: false), .restore)
        XCTAssertEqual(CallMediaPolicy.restoreVolume(original: 0.7, ducked: 0.2, now: 0.2), 0.7)
        XCTAssertNil(CallMediaPolicy.restoreVolume(original: 0.7, ducked: 0.2, now: 0.5)) // user changed it
    }

    func testDndSync() {
        let s = DndSync(echoWindowMs: 4000)
        XCTAssertTrue(s.local(false, now: 0))
        XCTAssertFalse(s.local(false, now: 10))
        XCTAssertTrue(s.remote(true, now: 100))
        XCTAssertFalse(s.local(true, now: 200))
        XCTAssertFalse(s.remote(true, now: 300))
        XCTAssertTrue(s.local(false, now: 10_000))
    }

    func testUnlockPolicy() {
        var u = UnlockPolicy()
        XCTAssertFalse(u.evaluate(enabled: true, locked: false, authenticated: true, near: true, now: 0))
        XCTAssertFalse(u.evaluate(enabled: false, locked: true, authenticated: true, near: true, now: 1))
        XCTAssertFalse(u.evaluate(enabled: true, locked: true, authenticated: false, near: true, now: 2))
        XCTAssertTrue(u.evaluate(enabled: true, locked: true, authenticated: true, near: true, now: 3))
        XCTAssertFalse(u.evaluate(enabled: true, locked: true, authenticated: true, near: true, now: 4)) // once per lock
        _ = u.evaluate(enabled: true, locked: false, authenticated: true, near: true, now: 5)
        XCTAssertTrue(u.evaluate(enabled: true, locked: true, authenticated: true, near: true, now: 6))
        _ = u.evaluate(enabled: true, locked: false, authenticated: true, near: true, now: 7)
        // Third lock within 5 minutes: capped.
        XCTAssertFalse(u.evaluate(enabled: true, locked: true, authenticated: true, near: true, now: 8))
        _ = u.evaluate(enabled: true, locked: false, authenticated: true, near: true, now: 9)
        XCTAssertTrue(u.evaluate(enabled: true, locked: true, authenticated: true, near: true, now: 6 * 60_000))
    }

    func testHotkeys() {
        XCTAssertEqual(HotkeySpec.sendClipboard.display, "⌥⌘V")
        XCTAssertEqual(HotkeySpec.sendFile.display, "⌥⌘F")
        XCTAssertEqual(HotkeySpec.openTether.display, "⌥⌘T")
        XCTAssertFalse(HotkeySpec(keyCode: 9, modifiers: HotkeySpec.shift).isValid)
        var s = HotkeySettings()
        XCTAssertFalse(s.conflicts)
        s.sendFile = s.sendClipboard
        XCTAssertTrue(s.conflicts)
        s.sendFile.enabled = false
        XCTAssertFalse(s.conflicts)
        let data = try? JSONEncoder().encode(HotkeySettings())
        XCTAssertEqual(data.flatMap { try? JSONDecoder().decode(HotkeySettings.self, from: $0) }, HotkeySettings())
    }

    func testUniversalSearch() {
        XCTAssertNotNil(UniversalSearch.score(query: "asha", title: "Asha Menon", body: ""))
        XCTAssertNotNil(UniversalSearch.score(query: "café", title: "Cafe Coffee Day", body: ""))
        XCTAssertNotNil(UniversalSearch.score(query: "98470 12345", title: "+919847012345", body: ""))
        XCTAssertNil(UniversalSearch.score(query: "asha otp", title: "Asha", body: "see you"))
        XCTAssertNil(UniversalSearch.score(query: "  ", title: "x", body: "y"))
        let title = UniversalSearch.score(query: "otp", title: "OTP", body: "")!
        let body = UniversalSearch.score(query: "otp", title: "HDFC", body: "your otp is")!
        XCTAssertGreaterThan(title, body)
        let hits = UniversalSearch.rank([
            SearchHit(kind: .notification, id: "n1", title: "a", subtitle: "", ts: 1, score: 8),
            SearchHit(kind: .message, id: "m1", title: "a", subtitle: "", ts: 1, score: 8),
            SearchHit(kind: .message, id: "m2", title: "a", subtitle: "", ts: 2, score: 8),
            SearchHit(kind: .message, id: "m3", title: "a", subtitle: "", ts: 0, score: 30),
        ])
        XCTAssertEqual(hits.map(\.id), ["m3", "m2", "m1", "n1"])
    }

    func testTypeBatcher() {
        var b = TypeBatcher()
        XCTAssertEqual(b.press(keyCode: 4, characters: "h", command: false), [])
        XCTAssertEqual(b.press(keyCode: 34, characters: "i", command: false), [])
        XCTAssertEqual(b.press(keyCode: 51, characters: "\u{7f}", command: false), [.text("hi"), .key(TypeKeys.backspace)])
        XCTAssertEqual(b.press(keyCode: 36, characters: "\r", command: false), [.key(TypeKeys.enter)])
        XCTAssertEqual(b.press(keyCode: 9, characters: "v", command: true), [])
        XCTAssertEqual(b.press(keyCode: 0, characters: "😀", command: false), [])
        XCTAssertEqual(b.paste(" ok"), [.text("😀 ok")])
        XCTAssertEqual(b.press(keyCode: 123, characters: nil, command: false), [])
        for _ in 0..<63 { _ = b.press(keyCode: 0, characters: "a", command: false) }
        XCTAssertEqual(b.press(keyCode: 0, characters: "a", command: false), [.text(String(repeating: "a", count: 64))])
        XCTAssertFalse(b.hasPending)
    }

    func testPresenceAndWidgetSnapshot() {
        XCTAssertTrue(AppPresence.both.showsDock && AppPresence.both.showsMenuBar)
        XCTAssertFalse(AppPresence.menuBar.showsDock)
        XCTAssertFalse(AppPresence.dock.showsMenuBar)
        let s = WidgetSnapshot(phoneName: "Nord", connected: true, battery: 87, charging: true, songTitle: "S", playing: true, updated: 5)
        XCTAssertEqual(WidgetSnapshot.decode(s.encoded()), s)
        XCTAssertNil(WidgetSnapshot.decode(Data("x".utf8)))
    }

    // MARK: 0.5 private GitHub releases

    func testPrivateReleaseAssetsAndAuthPolicy() throws {
        let gh = Data(#"""
        {"tag_name":"v0.5.0","html_url":"https://github.com/Benfranklinms/Tether/releases/tag/v0.5.0","body":"n",
         "assets":[{"name":"Tether-0.5.0.zip","url":"https://api.github.com/repos/Benfranklinms/Tether/releases/assets/11",
         "browser_download_url":"https://github.com/Benfranklinms/Tether/releases/download/v0.5.0/Tether-0.5.0.zip","size":5,
         "digest":"sha256:ABCD"},
         {"name":"Tether-0.5.0.dmg","url":"https://evil.example/repos/Benfranklinms/Tether/releases/assets/12",
         "browser_download_url":"https://github.com/Benfranklinms/Tether/releases/download/v0.5.0/Tether-0.5.0.dmg","size":6}]}
        """#.utf8)
        let u = try XCTUnwrap(UpdateFeed.parse(gh))
        XCTAssertEqual(u.version, "0.5.0")
        XCTAssertEqual(u.macZip?.apiURL, "https://api.github.com/repos/Benfranklinms/Tether/releases/assets/11")
        XCTAssertEqual(u.macZip?.sha256, "abcd")
        XCTAssertNil(u.macDmg?.apiURL, "asset API URL on another host is refused")
        let api = URL(string: "https://api.github.com/repos/Benfranklinms/Tether/releases/latest")!
        let cdn = URL(string: "https://objects.githubusercontent.com/x")!
        XCTAssertEqual(GitHubReleases.headers(for: api, token: "github_pat_x", binary: false)["Authorization"], "Bearer github_pat_x")
        XCTAssertNil(GitHubReleases.headers(for: cdn, token: "github_pat_x", binary: true)["Authorization"])
        XCTAssertEqual(GitHubReleases.headers(for: cdn, token: nil, binary: true)["Accept"], "application/octet-stream")
        XCTAssertNil(GitHubReleases.headers(for: URL(string: "http://api.github.com/x")!, token: "t", binary: false)["Authorization"])
        XCTAssertFalse(GitHubReleases.redirectKeepsAuth(to: cdn))
        XCTAssertTrue(GitHubReleases.redirectKeepsAuth(to: api))
        XCTAssertTrue(GitHubReleases.looksLikeToken("github_pat_11ABCDEFG0123456789_abcdefghijklmnop"))
        XCTAssertTrue(GitHubReleases.looksLikeToken("  ghp_0123456789abcdefghijABCDEFGHIJ0123  "))
        XCTAssertFalse(GitHubReleases.looksLikeToken("hello world token value 123"))
        XCTAssertFalse(GitHubReleases.looksLikeToken("ghp_short"))
        XCTAssertFalse(GitHubReleases.redact("github_pat_secret").contains("secret"))
        XCTAssertTrue(GitHubReleases.explain(status: 401).contains("rejected"))
    }

    func testPhoneKindsOnlyScreenshotsAndPhotosAreMedia() {
        XCTAssertTrue(PhoneKinds.isMedia("screenshot"))
        XCTAssertTrue(PhoneKinds.isMedia("photo"))
        XCTAssertFalse(PhoneKinds.isMedia(FileKinds5.fs))
        XCTAssertFalse(PhoneKinds.isMedia(FileKinds5.clip))
        XCTAssertFalse(PhoneKinds.isMedia(nil))
    }
}
