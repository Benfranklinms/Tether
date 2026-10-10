import AppKit
import Combine
import Foundation
import TetherCore

/// One folder listing on the phone (Files page).
struct PhoneFolder: Equatable {
    var root = FsRoots.downloads
    var path = ""
    var entries: [FsEntry] = []
    var total = 0
    var more = false
    var loading = false
    /// "storage" (all-files access missing on the phone), "disabled", or "error:<reason>".
    var needs: String?
    var error: String?
    var ref = ""
}

/// The phone's photo library (Photos page).
struct PhoneLibrary: Equatable {
    var items: [PhotoItem] = []
    var more = false
    var loading = false
    var needs: String?
    var error: String?
    var ref = ""
    var before: Int64?
}

/// The in-app updater's state (Settings › Updates and the setup checklist).
struct UpdateState: Equatable {
    enum Phase: Equatable {
        case idle, checking, upToDate, available, downloading(Double), installing, failed(String)
    }

    var phase: Phase = .idle
    var info: UpdateInfo?
    var lastCheck: Date?
    var tokenSet = false
}

/// A row of the Mac setup checklist.
struct SetupItem: Identifiable, Equatable {
    var id: String
    var title: String
    var detail: String
    var ok: Bool
    var optional = false
    var fix: String?
}

/// Everything 0.5.0 adds to the UI state. Kept in its own observable object (AppModel
/// forwards its changes) so the 0.5 code stays in one place.
final class V5State: ObservableObject {
    @Published var folder = PhoneFolder()
    @Published var library = PhoneLibrary()
    /// Thumbnails by key ("root/path" for files, "photos/<id>" for photos).
    @Published var thumbs: [String: NSImage] = [:]
    @Published var otp: OtpPayload?
    @Published var continueCards = ContinueStore()
    @Published var clips = ClipHistory()
    @Published var phoneDnd: DndStatePayload?
    @Published var phoneStatus: PhoneStatusPayload?
    @Published var typeStatus: String?
    @Published var typeField: String?
    @Published var update = UpdateState()
    @Published var searchQuery = ""
    @Published var searchFocus = 0
    @Published var page: MainPage = MainPage.saved
    @Published var preview: PreviewItem?
    @Published var accessibilityTrusted = false
    @Published var unlockReady = false

    /// THUMB request ref -> thumbnail key.
    var thumbRequests: [String: String] = [:]
    /// FS_GET ref -> what to do when the file arrives.
    var gets: [String: PhoneGet] = [:]
    var typeSeq: Int64 = 0
    var typeBatcher = TypeBatcher()
    var charge = ChargeCompleteMonitor()
    var callMedia = CallMediaPolicy()
    var dndSync = DndSync()
    var unlockPolicy = UnlockPolicy()
    var expireTimer: Timer?
}

/// A file being fetched from the phone and what happens when it arrives.
struct PhoneGet: Equatable {
    var name: String
    var folder: URL?
    var open: Bool
    var preview: Bool
}

/// The preview sheet (Files and Photos pages).
struct PreviewItem: Identifiable, Equatable {
    var id: String
    var root: String
    var path: String
    var name: String
    var size: Int64
    var date: Int64
    var mime: String?
}

/// Sidebar pages of the main window.
enum MainPage: String, CaseIterable, Identifiable {
    case home = "Home"
    case messages = "Messages"
    case calls = "Calls"
    case notifications = "Notifications"
    case files = "Files"
    case photos = "Photos"
    case screen = "Phone screen"
    case clipboard = "Clipboard"
    case settings = "Settings"

    var id: String { rawValue }

    var symbol: String {
        switch self {
        case .home: return "house"
        case .messages: return "message"
        case .calls: return "phone"
        case .notifications: return "bell"
        case .files: return "folder"
        case .photos: return "photo.on.rectangle"
        case .screen: return "iphone.gen2"
        case .clipboard: return "doc.on.clipboard"
        case .settings: return "gearshape"
        }
    }

    static let storeKey = "mainPage"
    static var saved: MainPage { MainPage(rawValue: UserDefaults.standard.string(forKey: storeKey) ?? "") ?? .home }
}
