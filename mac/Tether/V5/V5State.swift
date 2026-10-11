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
    /// Thumbnails by key ("root/path" for files, "photos/<id>" for photos). 0.5.1: an NSCache that
    /// notifies only the views showing a key, instead of a published dictionary redrawing everything.
    var thumbs: ThumbCache { ThumbCache.shared }
    // 0.5.1 gallery
    @Published var albums: [AlbumInfo] = []
    @Published var album: String?
    @Published var photoFilter: String?
    @Published var photoZoom: PhotoSections.Zoom = .all
    @Published var photoSelection = GridSelection()
    @Published var viewer: PhotoItem?
    @Published var gallerySize: Double = GallerySize.clamp(UserDefaults.standard.object(forKey: "gallerySize") as? Double
                                                            ?? GallerySize.standard) {
        didSet { UserDefaults.standard.set(gallerySize, forKey: "gallerySize") }
    }
    // 0.5.1 typing, setup, success morphs
    @Published var typeTracker = TypeTracker()
    @Published var typeEcho = ""
    @Published var setupGate = SetupHomeGate.load()
    @Published var succeeded: Set<String> = []
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
    @Published var page: MainPage = MainPage.saved {
        didSet {
            let all = MainPage.allCases
            pageDirection = PageDirection.between(all.firstIndex(of: oldValue) ?? 0, all.firstIndex(of: page) ?? 0)
        }
    }
    /// +1 when the new page is below the old one in the sidebar (content rises), −1 above.
    var pageDirection: Double = 1
    // 0.5.2: video playback, Insert from Phone, Find my Mac, Instant Hotspot, Shelf
    @Published var videoRequests: [String: VideoRequest] = [:]
    @Published var videoErrors: [String: String] = [:]
    @Published var capture: CaptureSession?
    @Published var findMac: FindMacSession?
    @Published var hotspot = HotspotSession()
    @Published var shelf = ShelfState()
    @Published var shelfUndo: ShelfItem?
    @Published var shelfDetail: String?
    @Published var shelfFetching: Set<String> = []
    // 0.5.3: local (encrypted) copies, background fetches, search, rename
    @Published var shelfLocal: Set<String> = []
    var shelfInFlight: [String: Date] = [:]
    var shelfWants: [String: ShelfWant] = [:]
    @Published var shelfQuery = ""
    @Published var shelfSearchFocus = 0
    @Published var shelfRenaming: String?
    // 0.5.4: the folder page that is open (nil = the Shelf), the folder chip a dragged card is over,
    // offline-copy pins (local), space used, and download progress per item.
    @Published var shelfFolder: String?
    @Published var shelfChipHover: String?
    @Published var shelfPins = ShelfOffline.Pins()
    @Published var shelfOfflineUsed: Int64 = 0
    @Published var shelfProgress: [String: Double] = [:]
    @Published var updateSheet = false
    @Published var whatsNew = false
    /// The last app other than Tether that was frontmost (where Insert from Phone pastes).
    var lastOtherApp: pid_t?
    @Published var preview: PreviewItem?
    @Published var accessibilityTrusted = false
    @Published var unlockReady = false

    /// THUMB request ref -> thumbnail key.
    var thumbRequests: [String: String] = [:]
    var albumsRef = ""
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
    /// 0.5.2: the Photos item a viewer playback fetch is for.
    var videoId: String?
}

/// A video the Photos viewer is fetching to play.
struct VideoRequest: Equatable {
    var name: String
    var size: Int64
    var folder: URL
    var since: Int64
}

/// Insert from Phone in progress.
struct CaptureSession: Equatable {
    var ref: String
    var kind: String
    var state: String
    var reason: String?
    var frontmostPid: Int32?
    var started: Int64
}

/// Find my Mac: the alarm is ringing (started by the phone).
struct FindMacSession: Equatable {
    var id: String
    var started: Int64
}

/// Instant Hotspot state shown on the Mac.
struct HotspotSession: Equatable {
    var ref: String?
    var state: String?
    var reason: String?
    var ssid: String?
    var joining = false
    var joined = false
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
    case shelf = "Shelf"
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
        case .shelf: return "tray.full"
        case .screen: return "iphone.gen2"
        case .clipboard: return "doc.on.clipboard"
        case .settings: return "gearshape"
        }
    }

    static let storeKey = "mainPage"
    static var saved: MainPage { MainPage(rawValue: UserDefaults.standard.string(forKey: storeKey) ?? "") ?? .home }
}
