//
// MacNotifications.swift — how the Mac shows mirrored phone notifications.
// Pure logic so it is unit-tested; the app maps it onto UserNotifications.
//

import Foundation

/// Settings › Notifications › Panel only (0.4.1): phone alerts, texts and calls stay in the panel and the
/// Mac posts no banners. Stored in UserDefaults so the app's `@AppStorage` toggles and `Notifier` agree.
public enum PanelOnlyAlerts {
    public static let key = "panelOnlyAlerts"

    public static func isOn(_ defaults: UserDefaults = .standard) -> Bool { defaults.bool(forKey: key) }
}

/// Received files deleted or moved away in Finder (0.4.2): finished incoming transfers whose saved file is
/// gone. The Mac drops them from Transfers and the "From your phone" strip instead of showing empty tiles.
public enum ReceivedFiles {
    public static func missing(_ records: [TransferRecord], exists: (String) -> Bool) -> Set<String> {
        var gone = Set<String>()
        for r in records where r.direction == .IN && r.status == .DONE {
            if let path = r.ref, !exists(path) { gone.insert(r.id) }
        }
        return gone
    }
}

public enum MacAppVisibility: String, Codable, CaseIterable { case show, hide }
public enum MacAppDelivery: String, Codable, CaseIterable { case banner, silent }

public struct MacAppPref: Codable, Equatable {
    public var visibility: MacAppVisibility
    public var delivery: MacAppDelivery

    public init(visibility: MacAppVisibility = .show, delivery: MacAppDelivery = .banner) {
        self.visibility = visibility
        self.delivery = delivery
    }
}

public struct MacNotificationSettings: Codable, Equatable {
    public var enabled: Bool = true
    /// Keyed by Android package name.
    public var apps: [String: MacAppPref] = [:]
    /// Show "New notification from <App>" instead of the content.
    public var privacyMode: Bool = false
    /// Number of notifications kept in the menu-bar history.
    public var historyLimit: Int = 50
    /// Play the default sound with banners.
    public var sound: Bool = true
    /// Dismissing on the Mac clears it on the phone, and vice versa.
    public var dismissSync: Bool = true

    public init() {}

    public func pref(for pkg: String) -> MacAppPref { apps[pkg] ?? MacAppPref() }

    public mutating func set(_ pref: MacAppPref, for pkg: String) {
        apps[pkg] = pref == MacAppPref() ? nil : pref
    }

    public static let historyChoices = [20, 50, 100, 250]

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? true
        apps = try c.decodeIfPresent([String: MacAppPref].self, forKey: .apps) ?? [:]
        privacyMode = try c.decodeIfPresent(Bool.self, forKey: .privacyMode) ?? false
        historyLimit = try c.decodeIfPresent(Int.self, forKey: .historyLimit) ?? 50
        sound = try c.decodeIfPresent(Bool.self, forKey: .sound) ?? true
        dismissSync = try c.decodeIfPresent(Bool.self, forKey: .dismissSync) ?? true
    }
}

/// What to do with one incoming notification.
public struct MacPresentation: Equatable {
    public var record: Bool      // add to the menu-bar history
    public var banner: Bool      // post a system banner (macOS Focus still applies)
    public var title: String
    public var body: String
}

public enum MacNotificationPolicy {
    public static func present(_ n: NotifPayload, settings s: MacNotificationSettings) -> MacPresentation {
        let p = s.pref(for: n.pkg)
        let visible = s.enabled && p.visibility == .show
        let title = s.privacyMode ? n.app : (n.title.isEmpty ? n.app : n.title)
        let body = s.privacyMode ? "New notification from \(n.app)" : n.text
        return MacPresentation(record: visible, banner: visible && p.delivery == .banner, title: title, body: body)
    }
}

public struct MirroredNotification: Codable, Equatable, Identifiable {
    public var id: String { key }
    public var key: String
    public var pkg: String
    public var app: String
    public var title: String
    public var text: String
    public var ts: Int64
    /// Buttons from the phone (reply, mark as read...); the live intents stay on the phone.
    public var actions: [NotifActionInfo]

    public init(_ n: NotifPayload) {
        key = n.key
        pkg = n.pkg
        app = n.app
        title = n.title
        text = n.text
        ts = n.ts
        actions = n.actions
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = try c.decode(String.self, forKey: .key)
        pkg = try c.decode(String.self, forKey: .pkg)
        app = try c.decode(String.self, forKey: .app)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        text = try c.decodeIfPresent(String.self, forKey: .text) ?? ""
        ts = try c.decode(Int64.self, forKey: .ts)
        actions = try c.decodeIfPresent([NotifActionInfo].self, forKey: .actions) ?? []
    }

    /// The inline-reply button, if the phone app offers one.
    public var replyAction: NotifActionInfo? { actions.first { $0.reply } }
}

/// Newest-first, de-duplicated by key, capped history.
public struct NotificationHistory: Codable, Equatable {
    public private(set) var items: [MirroredNotification] = []
    /// Apps seen so far (pkg -> label), for the per-app settings list.
    public private(set) var seenApps: [String: String] = [:]

    public init() {}

    public mutating func add(_ n: NotifPayload, limit: Int) {
        items.removeAll { $0.key == n.key }
        items.insert(MirroredNotification(n), at: 0)
        trim(limit)
        noteApp(n)
    }

    public mutating func noteApp(_ n: NotifPayload) {
        seenApps[n.pkg] = n.app
    }

    @discardableResult
    public mutating func remove(key: String) -> Bool {
        let before = items.count
        items.removeAll { $0.key == key }
        return items.count != before
    }

    public mutating func clear() { items.removeAll() }

    public mutating func trim(_ limit: Int) {
        if items.count > max(0, limit) { items.removeLast(items.count - max(0, limit)) }
    }
}
