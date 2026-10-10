import Foundation

// 0.5.1: pure logic behind the bug fixes (clipboard images, typing, calls, setup, galleries),
// kept out of AppKit so it is unit-tested on every platform.

// MARK: - Clipboard: what a pasteboard copy really is

/// What the Mac should send for the current pasteboard. Finder puts a file URL, the file's
/// NAME as plain text and the file's icon as TIFF on the pasteboard, so reading the string
/// first sent the name instead of the picture; the icon TIFF must never be sent either.
public enum PasteboardChoice: Equatable {
    /// A copied image file (Finder, Photos export, screenshots): send the file's bytes.
    case imageFile(String)
    /// Image data on the pasteboard (Preview, browsers, screenshots to clipboard).
    case imageData
    case text
    /// Nothing worth sending (a non-image file, an empty or private copy).
    case skip
}

public enum PasteboardClassifier {
    public static let fileURLType = "public.file-url"
    public static let stringTypes: Set<String> = ["public.utf8-plain-text", "NSStringPboardType", "public.plain-text"]
    public static let imageTypes: Set<String> = ["public.png", "public.tiff", "public.jpeg", "public.heic", "com.compuserve.gif",
                                                 "NeXT TIFF v4.0 pasteboard type", "Apple PNG pasteboard type"]
    public static let imageExtensions: Set<String> = ["png", "jpg", "jpeg", "heic", "heif", "gif", "tiff", "tif", "webp", "bmp"]

    public static func isImagePath(_ path: String) -> Bool {
        imageExtensions.contains((path as NSString).pathExtension.lowercased())
    }

    /// `types` in the order the writer declared them (best first), `fileURLs` = file paths on
    /// the pasteboard, `hasText` = a non-empty string is readable.
    public static func choose(types: [String], filePaths: [String], hasText: Bool) -> PasteboardChoice {
        if types.contains(fileURLType) || !filePaths.isEmpty {
            if let img = filePaths.first(where: isImagePath) { return .imageFile(img) }
            // A copied document or folder: its name is not clipboard text, and its icon is not an image.
            return .skip
        }
        let firstImage = types.firstIndex(where: imageTypes.contains)
        let firstText = types.firstIndex(where: stringTypes.contains)
        switch (firstImage, firstText) {
        case (.some, .none): return .imageData
        case (.none, .some): return hasText ? .text : .skip
        case let (.some(i), .some(t)):
            // Browsers and Preview declare the image first; Word/Pages declare text first and add a picture of it.
            if i < t { return .imageData }
            return hasText ? .text : .imageData
        case (.none, .none): return hasText ? .text : .skip
        }
    }
}

// MARK: - Typing into the phone

/// Status of the keyboard window: every batch is acknowledged by TYPE_RESULT; a missing
/// answer within `timeoutMs` is reported instead of silently doing nothing.
public struct TypeTracker: Equatable {
    public static let timeoutMs: Int64 = 4000
    public private(set) var pending: [Int64: Int64] = [:]
    public private(set) var sentChars = 0
    public init() {}

    public mutating func sent(seq: Int64, chars: Int, now: Int64) {
        pending[seq] = now
        sentChars += chars
    }

    /// Returns true when this answer was for a batch still waiting.
    @discardableResult public mutating func answered(seq: Int64) -> Bool {
        pending.removeValue(forKey: seq) != nil
    }

    /// True when the oldest batch has waited too long (and forgets the waiting batches).
    public mutating func timedOut(now: Int64) -> Bool {
        guard let oldest = pending.values.min(), now - oldest >= Self.timeoutMs else { return false }
        pending.removeAll()
        return true
    }

    public mutating func reset() {
        pending.removeAll()
        sentChars = 0
    }

    /// A sentence for a failed TYPE_RESULT reason.
    public static func describe(_ reason: String?) -> String {
        switch reason {
        case "no_field": return "Tap a text field on your phone first."
        case "no_service", "accessibility": return "Turn on Tether control in your phone's Accessibility settings."
        case "disabled": return "Typing from the Mac is off in Tether on your phone."
        case "timeout": return "Your phone didn't answer. Check that Tether is running there."
        case "failed": return "That field doesn't accept typing from other apps."
        default: return "Couldn't type on your phone (\(reason ?? "unknown"))."
        }
    }
}

/// The phone-side permission that typing needs, read from PHONE_STATUS.
public enum TypeAccess: Equatable {
    case unknown, on, off

    public static func from(_ perms: [PermInfo]?) -> TypeAccess {
        guard let p = perms?.first(where: { $0.id == "accessibility" }) else { return .unknown }
        return p.ok ? .on : .off
    }
}

/// The phone opens this to show the fix for one checklist row (OPEN_ON_PHONE).
public enum PhoneSetupLink {
    public static func url(for id: String) -> String { "tether://setup/\(id)" }
}

// MARK: - Calls

/// One clear caption under the live call card (0.5.1): shown only when Mute can't be used.
public enum CallCaption {
    public static let muteUnavailable = "Mute isn't available: your phone keeps call controls to itself."

    /// `note` is the last action's sentence (answering…, not connected…); the unsupported
    /// sentence is folded into the mute line so the card never shows two conflicting lines.
    public static func line(canMute: Bool, muteBlocked: Bool, note: String?, unsupportedNote: String?) -> String? {
        let muteOff = !canMute || muteBlocked
        if let note, note != unsupportedNote { return note }
        if muteOff { return muteUnavailable }
        return nil
    }
}

/// Where the floating call window sits: top-right of the visible frame, like a Continuity banner.
public enum CallBannerPlacement {
    public static func origin(visible: (x: Double, y: Double, w: Double, h: Double), size: (w: Double, h: Double),
                              margin: Double = 14) -> (x: Double, y: Double) {
        (visible.x + visible.w - size.w - margin, visible.y + visible.h - size.h - margin)
    }
}

// MARK: - Setup checklist

/// One checklist row on the Mac (Mac items and the phone's PHONE_STATUS rows).
public struct SetupEntry: Equatable, Identifiable {
    public var id: String
    public var title: String
    /// What it unlocks ("Type from Mac, phone screen control"), never a generic instruction.
    public var unlocks: String
    public var ok: Bool
    public var required: Bool
    /// The feature heading optional rows are listed under.
    public var feature: String
    public var fix: String?

    public init(id: String, title: String, unlocks: String, ok: Bool, required: Bool, feature: String, fix: String? = nil) {
        self.id = id
        self.title = title
        self.unlocks = unlocks
        self.ok = ok
        self.required = required
        self.feature = feature
        self.fix = fix
    }
}

public enum SetupPlan {
    /// Phone rows by PHONE_STATUS id: title, what it unlocks, feature heading, required.
    /// Only nearby / notification permissions / notification access / battery are required;
    /// adb grants, Accessibility and other feature grants are optional.
    public static let phoneRows: [String: (title: String, unlocks: String, feature: String, required: Bool)] = [
        "nearby": ("Nearby devices", "Connecting to this Mac over Bluetooth", "Connection", true),
        "postnotif": ("Notifications", "Link status, files and call alerts on the phone", "Connection", true),
        "notif": ("Notification access", "Mirrored notifications, replies, one-time codes", "Notifications", true),
        "battery": ("Battery unrestricted", "Staying connected in the background", "Connection", true),
        "sms": ("SMS", "Messages on the Mac, codes from texts", "Messages", false),
        "phone": ("Phone", "Call alerts, answering and calling from the Mac", "Calls", false),
        "calllog": ("Call log", "Caller numbers and recent calls", "Calls", false),
        "contacts": ("Contacts", "Names instead of numbers, contact search", "Calls", false),
        "photos": ("Photos and videos", "Photos tab, screenshots and photo cards", "Files & photos", false),
        "allfiles": ("All files access", "Downloads, Documents and WhatsApp in the Files tab", "Files & photos", false),
        "dnd": ("Do Not Disturb access", "Silencing calls, Focus sync", "Calls", false),
        "accessibility": ("Accessibility", "Type from Mac, phone screen control, browser tab", "Typing & screen", false),
        "overlay": ("Display over other apps", "Opening links from the Mac, clipboard auto mode", "Continue", false),
        "install": ("Install updates", "Updating Tether from inside the app", "Updates", false),
        "updates": ("Updates", "Checking for new versions", "Updates", false),
        "readlogs": ("Read logs (adb)", "Sending copies without opening Tether", "Clipboard", false),
        "sensitive": ("Sensitive notifications (adb)", "One-time codes from notifications", "Codes", false),
    ]

    public static func phoneEntry(_ p: PermInfo) -> SetupEntry {
        let row = phoneRows[p.id]
        return SetupEntry(id: "phone-\(p.id)", title: "Phone: \(row?.title ?? p.label)", unlocks: row?.unlocks ?? p.label,
                          ok: p.ok, required: row?.required ?? p.required, feature: row?.feature ?? "Phone", fix: "phone:\(p.id)")
    }

    public static func required(_ e: [SetupEntry]) -> [SetupEntry] { e.filter(\.required) }
    public static func missingRequired(_ e: [SetupEntry]) -> [SetupEntry] { e.filter { $0.required && !$0.ok } }

    /// Optional rows grouped by the feature they unlock, features in first-seen order.
    public static func optionalByFeature(_ e: [SetupEntry]) -> [(feature: String, entries: [SetupEntry])] {
        var order: [String] = []
        var map: [String: [SetupEntry]] = [:]
        for x in e where !x.required {
            if map[x.feature] == nil { order.append(x.feature) }
            map[x.feature, default: []].append(x)
        }
        return order.map { ($0, map[$0] ?? []) }
    }

    /// "Setup · 2 left" for the Home row.
    public static func summary(_ e: [SetupEntry]) -> String {
        let n = missingRequired(e).count
        return n == 0 ? "Setup complete" : "Setup · \(n) left"
    }

    /// Done fraction of the required rows (for the progress ring).
    public static func progress(_ e: [SetupEntry]) -> Double {
        let r = required(e)
        guard !r.isEmpty else { return 1 }
        return Double(r.filter(\.ok).count) / Double(r.count)
    }
}

/// Whether the Home "Setup · n left" row shows. Hidden by the user, it comes back only when a
/// required row that was fine when it was hidden goes missing again (a revoked permission).
public struct SetupHomeGate: Codable, Equatable {
    /// Required ids that were missing when the user pressed Hide.
    public var hiddenMissing: Set<String>?

    public init(hiddenMissing: Set<String>? = nil) { self.hiddenMissing = hiddenMissing }

    public func shows(missingRequired ids: Set<String>) -> Bool {
        guard !ids.isEmpty else { return false }
        guard let hidden = hiddenMissing else { return true }
        return !ids.isSubset(of: hidden)
    }

    public mutating func hide(missingRequired ids: Set<String>) { hiddenMissing = ids }

    /// Forget a hide once everything required is done, so a later revocation shows the row.
    public mutating func update(missingRequired ids: Set<String>) {
        if ids.isEmpty { hiddenMissing = nil }
    }
}

// MARK: - Galleries

/// Photos grid thumbnail size (⌘+ / ⌘− / slider), clamped to 90–200 pt in 10 pt steps.
public enum GallerySize {
    public static let min: Double = 90
    public static let max: Double = 200
    public static let standard: Double = 120
    public static let step: Double = 10

    public static func clamp(_ v: Double) -> Double { Swift.min(max, Swift.max(min, (v / step).rounded() * step)) }
    public static func bigger(_ v: Double) -> Double { clamp(v + step * 2) }
    public static func smaller(_ v: Double) -> Double { clamp(v - step * 2) }

    /// Columns that fit `width` with `spacing` (at least 1).
    public static func columns(width: Double, tile: Double, spacing: Double) -> Int {
        Swift.max(1, Int((width + spacing) / (tile + spacing)))
    }
}

/// Grid or list, remembered per Files root.
public enum FilesViewMode: String, Codable, CaseIterable {
    case list, grid

    public static func key(root: String) -> String { "filesView.\(root)" }

    public static func load(root: String, from get: (String) -> String?) -> FilesViewMode {
        get(key(root: root)).flatMap(FilesViewMode.init(rawValue:)) ?? (root == "dcim" || root == "pictures" ? .grid : .list)
    }
}

/// Stagger delay for the n-th item appearing (capped so long lists don't wait).
public enum Stagger {
    public static func delay(index: Int, step: Double = 0.025, cap: Double = 0.3) -> Double {
        Swift.min(cap, Double(Swift.max(0, index)) * step)
    }
}

/// Trailing-edge debounce for search fields (pure clock in, decision out).
public struct Debounce: Equatable {
    public let intervalMs: Int64
    public private(set) var lastInput: Int64?
    public private(set) var fired = true

    public init(intervalMs: Int64 = 180) { self.intervalMs = intervalMs }

    public mutating func input(now: Int64) {
        lastInput = now
        fired = false
    }

    /// True once, when the input has been quiet for the interval.
    public mutating func shouldFire(now: Int64) -> Bool {
        guard !fired, let t = lastInput, now - t >= intervalMs else { return false }
        fired = true
        return true
    }
}

// MARK: - Photos gallery

/// Date sections like Photos.app: Today, Yesterday, This week, then "October 2026".
public enum PhotoSections {
    public enum Zoom: String, CaseIterable { case years = "Years", months = "Months", all = "All Photos" }

    public static func title(for date: Date, now: Date, calendar: Calendar = .current) -> String {
        if calendar.isDate(date, inSameDayAs: now) { return "Today" }
        if let y = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(date, inSameDayAs: y) { return "Yesterday" }
        if let weekAgo = calendar.date(byAdding: .day, value: -6, to: calendar.startOfDay(for: now)), date >= weekAgo, date <= now {
            return "This week"
        }
        return monthTitle(date, calendar: calendar)
    }

    public static func monthTitle(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month], from: date)
        let names = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
                     "November", "December"]
        return "\(names[max(0, min(11, (c.month ?? 1) - 1))]) \(c.year ?? 0)"
    }

    /// Groups newest-first items (date = ms) into sections, keeping order.
    public static func group(_ items: [PhotoItem], zoom: Zoom = .all, now: Date, calendar: Calendar = .current)
        -> [(title: String, items: [PhotoItem])] {
        var out: [(title: String, items: [PhotoItem])] = []
        for p in items {
            let d = Date(timeIntervalSince1970: TimeInterval(p.date) / 1000)
            let t: String
            switch zoom {
            case .all: t = title(for: d, now: now, calendar: calendar)
            case .months: t = monthTitle(d, calendar: calendar)
            case .years: t = String(calendar.component(.year, from: d))
            }
            if out.last?.title == t { out[out.count - 1].items.append(p) } else { out.append((t, [p])) }
        }
        return out
    }

    /// "0:42", "12:05", "1:02:03".
    public static func duration(_ ms: Int64) -> String {
        let s = Int(ms / 1000)
        let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }
}

/// Multi-select like Finder: click selects one, ⌘-click toggles, shift-click extends a range
/// from the anchor, drag-select adds what the rectangle covers.
public struct GridSelection: Equatable {
    public private(set) var ids: Set<String> = []
    public private(set) var anchor: String?
    public init() {}

    public enum Click { case plain, toggle, range }

    public mutating func click(_ id: String, mode: Click, order: [String]) {
        switch mode {
        case .plain:
            ids = [id]
            anchor = id
        case .toggle:
            if ids.contains(id) { ids.remove(id) } else { ids.insert(id) }
            anchor = id
        case .range:
            guard let a = anchor, let i = order.firstIndex(of: a), let j = order.firstIndex(of: id) else {
                ids = [id]
                anchor = id
                return
            }
            ids.formUnion(order[min(i, j)...max(i, j)])
        }
    }

    public mutating func drag(covering covered: [String], additive: Bool) {
        if !additive { ids = [] }
        ids.formUnion(covered)
        if anchor == nil { anchor = covered.first }
    }

    public mutating func clear() {
        ids = []
        anchor = nil
    }

    /// Arrow-key navigation in the viewer: the neighbour of `id` in `order` (clamped).
    public static func neighbour(of id: String, in order: [String], step: Int) -> String? {
        guard let i = order.firstIndex(of: id), !order.isEmpty else { return nil }
        return order[max(0, min(order.count - 1, i + step))]
    }
}

/// Thumbnail pixel size to request for a tile: about 2× the tile (Retina), in 64 px steps, 128–768.
public enum ThumbPixels {
    public static func forTile(_ points: Double, scale: Double = 2) -> Int {
        let px = Int((points * scale / 64).rounded(.up)) * 64
        return min(768, max(128, px))
    }
}
