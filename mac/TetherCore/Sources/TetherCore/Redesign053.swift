//
// Redesign053.swift — the 0.5.3 redesign's labelling rules for Shelf rows, mirrored rule for
// rule from Kotlin (android/core/.../features/Redesign053.kt: ShelfMeta, When) so a Shelf item
// reads the same on both devices: "PDF · 454 KB", "forms.gle", "Snippet · 2 lines", "Phone · 12m".
//

import Foundation

public enum When {
    public static let today = "Today"
    public static let earlier = "Earlier"

    private static func calendar(_ zone: TimeZone) -> Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = zone
        c.locale = Locale(identifier: "en_US_POSIX")
        return c
    }

    private static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: TimeInterval(ms) / 1000) }

    /// "now", "2m", "1h", then "Yesterday", a weekday ("Fri") within a week, else "Oct 9".
    public static func short(_ at: Int64, now: Int64, zone: TimeZone = .current) -> String {
        let s = Swift.max(0, (now - at) / 1000)
        let cal = calendar(zone)
        let sameDay = cal.isDate(date(at), inSameDayAs: date(now))
        if s < 60 { return "now" }
        if s < 3600 { return "\(s / 60)m" }
        if sameDay { return "\(s / 3600)h" }
        return dayLabel(at, now: now, cal: cal)
    }

    /// "08:42" today, else as `short`'s day labels.
    public static func clock(_ at: Int64, now: Int64, zone: TimeZone = .current) -> String {
        let cal = calendar(zone)
        if cal.isDate(date(at), inSameDayAs: date(now)) {
            let c = cal.dateComponents([.hour, .minute], from: date(at))
            return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
        }
        return dayLabel(at, now: now, cal: cal)
    }

    public static func section(_ at: Int64, now: Int64, zone: TimeZone = .current) -> String {
        calendar(zone).isDate(date(at), inSameDayAs: date(now)) ? today : earlier
    }

    private static func dayLabel(_ at: Int64, now: Int64, cal: Calendar) -> String {
        let day = cal.startOfDay(for: date(at))
        let today = cal.startOfDay(for: date(now))
        let diff = cal.dateComponents([.day], from: day, to: today).day ?? 0
        let f = DateFormatter()
        f.calendar = cal
        f.timeZone = cal.timeZone
        f.locale = Locale(identifier: "en_US_POSIX")
        switch diff {
        case 1: return "Yesterday"
        case 2..<7:
            f.dateFormat = "EEE"
            return f.string(from: day)
        default:
            f.dateFormat = "MMM d"
            return f.string(from: day)
        }
    }
}

public enum ShelfMeta {
    public static let secretDots = 9
    private static let archives: Set<String> = ["zip", "rar", "7z", "gz", "tar", "tgz"]

    /// The short word on a file's thumbnail ("PDF", "DOC", "ZIP").
    public static func badge(mime: String?, name: String?) -> String {
        let e = FileKinds.ext(name)
        switch e {
        case "doc", "docx", "dot", "dotx", "odt", "rtf", "pages": return "DOC"
        case "xls", "xlsx", "ods", "numbers", "csv": return "XLS"
        case "ppt", "pptx", "odp", "key": return "PPT"
        case "zip", "rar", "7z", "gz", "tar", "tgz": return "ZIP"
        case "txt", "md": return "TXT"
        case "":
            if mime == "application/pdf" { return "PDF" }
            if mime?.hasPrefix("text/") == true { return "TXT" }
            return "FILE"
        default: return String(e.uppercased().prefix(4))
        }
    }

    /// One calm word for a file's kind ("PDF", "Word", "JPEG", "Archive").
    public static func kind(mime: String?, name: String?) -> String {
        let e = FileKinds.ext(name)
        let long = FileKinds.describe(mime: mime, name: name)
        if archives.contains(e) || long.hasSuffix("archive") { return "Archive" }
        if long.hasPrefix("Word") && long.hasSuffix(" document") { return "Word" }
        if long.hasPrefix("Excel") { return "Excel" }
        if long.hasPrefix("PowerPoint") { return "PowerPoint" }
        if long.hasSuffix(" image") { return String(long.dropLast(" image".count)) }
        if long.hasSuffix(" video") { return "Video" }
        if long.hasSuffix(" audio") { return "Audio" }
        if ["Android app", "Disk image", "PDF"].contains(long) { return long }
        if long.hasSuffix(" spreadsheet") || long.hasSuffix(" presentation") || long.hasSuffix(" document"),
           let space = long.lastIndex(of: " ") {
            return String(long[..<space])
        }
        return long
    }

    public static func hasPicture(_ item: ShelfItem) -> Bool {
        guard let f = item.file else { return false }
        return FileKinds.isImage(mime: f.mime, name: f.name)
    }

    /// "Mac" or "Phone".
    public static func source(_ item: ShelfItem) -> String { item.from == "mac" ? "Mac" : "Phone" }

    /// "forms.gle" from a link.
    public static func host(_ url: String) -> String {
        var s = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if let r = s.range(of: "://") { s = String(s[r.upperBound...]) }
        for sep in ["/", "?", "#"] { if let r = s.range(of: sep) { s = String(s[..<r.lowerBound]) } }
        if let at = s.lastIndex(of: "@") { s = String(s[s.index(after: at)...]) }
        if s.hasPrefix("www.") { s = String(s.dropFirst(4)) }
        return s.isEmpty ? url.trimmingCharacters(in: .whitespacesAndNewlines) : s
    }

    /// Title and the calm second line of a Shelf row; the title never repeats (ShelfCardText.split).
    public static func lines(_ item: ShelfItem, size: (Int64) -> String, missing: Bool = false) -> (title: String, detail: String) {
        switch item.kind {
        case ShelfKinds.file:
            guard let f = item.file else { return (item.title, "File") }
            let parts = [kind(mime: f.mime, name: f.name), size(f.size), missing ? "Not downloaded" : nil]
            return (item.title, parts.compactMap { $0 }.joined(separator: " · "))
        case ShelfKinds.wifi:
            let secret = item.secret ?? ""
            return (item.title, secret.isEmpty ? "Wi-Fi · Open" : "Wi-Fi · " + String(repeating: "•", count: secretDots))
        case ShelfKinds.link:
            let p = ShelfCardText.split(title: item.title, body: item.body)
            let lines = item.body.components(separatedBy: "\n")
            let url = lines.first { $0.contains("://") || $0.hasPrefix("www.") }?.trimmingCharacters(in: .whitespaces)
                ?? item.body.trimmingCharacters(in: .whitespacesAndNewlines)
            return (p.title, host(url))
        case ShelfKinds.snippet:
            let p = ShelfCardText.split(title: item.title, body: item.body)
            let n = Swift.max(1, item.body.components(separatedBy: "\n").filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.count)
            return (p.title, "Snippet · \(n) " + (n == 1 ? "line" : "lines"))
        default:
            let p = ShelfCardText.split(title: item.title, body: item.body)
            let first = p.preview.components(separatedBy: "\n").first { !$0.trimmingCharacters(in: .whitespaces).isEmpty } ?? ""
            return (p.title, first.trimmingCharacters(in: .whitespaces))
        }
    }

    /// Index → "Today"/"Earlier" header, only while the list is newest-first by section.
    public static func sections(_ items: [ShelfItem], now: Int64, zone: TimeZone = .current) -> [Int: String] {
        guard !items.isEmpty else { return [:] }
        let names = items.map { When.section($0.created, now: now, zone: zone) }
        let firstEarlier = names.firstIndex(of: When.earlier)
        if let fe = firstEarlier, names[fe...].contains(When.today) { return [:] }
        var out: [Int: String] = [:]
        if names[0] == When.today { out[0] = When.today }
        if let fe = firstEarlier { out[fe] = When.earlier }
        return out
    }
}

/// "Clear all" for mirrored notifications (0.5.3): empties the history, withdraws exactly those
/// delivered banners (calls, transfers and other Tether banners stay), and — only with
/// "Dismiss on both" on — dismisses each one on the phone too (NOTIF_DISMISS per key).
public enum NotificationClear {
    /// Ask first only when this many or more would go at once.
    public static let confirmAt = 10

    public struct Plan: Equatable {
        public let withdraw: [String]
        public let dismissOnPhone: [String]
    }

    public static func plan(_ history: NotificationHistory, dismissSync: Bool) -> Plan {
        var seen = Set<String>()
        let keys = history.items.map(\.key).filter { seen.insert($0).inserted }
        return Plan(withdraw: keys, dismissOnPhone: dismissSync ? keys : [])
    }

    public static func needsConfirmation(_ count: Int) -> Bool { count >= confirmAt }
}
