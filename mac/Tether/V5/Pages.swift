import AppKit
import SwiftUI
import TetherCore
import UniformTypeIdentifiers

// MARK: - Home

struct HomePage: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var v5: V5State
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        PageScroll {
            PageTitle(title: m.peer?.name ?? "Tether", detail: homeLine)
            SetupHomeRow(v5: v5)
            if m.peer == nil {
                Card {
                    Row(title: "Pair your phone", detail: "Install Tether on Android, then scan the code.", divider: false) {
                        GhostButton(title: "Pair…") { m.windows?.show(.pair) }
                    }
                }
            }
            if let otp = v5.otp { OtpCard(otp: otp).transition(Motion.row(reduce)) }
            if !v5.continueCards.cards.isEmpty {
                SectionLabel(text: "Continue on Mac")
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(Array(v5.continueCards.cards.enumerated()), id: \.element.id) { i, c in
                            ContinueCardView(state: c).staggerIn(i).transition(Motion.listRow(reduce))
                        }
                    }
                    .padding(.vertical, 8)
                    .animation(Motion.bouncy(reduce), value: v5.continueCards.cards.map(\.id))
                }
            }
            if let np = m.nowPlaying, np.active { NowPlayingCard(np: np) }
            SectionLabel(text: "Quick actions")
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
                ActionTile(title: "Take Photo", symbol: "camera", enabled: m.isConnected && m.supports(Caps6.capture)) {
                    m.startCapture(CaptureKinds.photo)
                }
                ActionTile(title: "Scan Document", symbol: "doc.viewfinder", enabled: m.isConnected && m.supports(Caps6.capture)) {
                    m.startCapture(CaptureKinds.scan)
                }
                ActionTile(title: m.v5.hotspot.joined ? "On hotspot" : "Hotspot", symbol: "personalhotspot",
                           enabled: m.isConnected && m.supports(Caps6.hotspot), done: m.v5.hotspot.joined) { m.startHotspot() }
                ActionTile(title: "Send clipboard", symbol: "doc.on.clipboard", enabled: m.isConnected,
                           done: v5.succeeded.contains("clipboard")) { m.sendClipboardNow() }
                ActionTile(title: "Send file", symbol: "arrow.up.doc", enabled: m.isConnected) { m.chooseFiles() }
                ActionTile(title: m.ringRequested ? "Stop ringing" : "Ring phone", symbol: "bell.and.waves.left.and.right",
                           enabled: m.isConnected) { m.ringRequested ? m.stopRing() : m.ring() }
                ActionTile(title: "Keyboard", symbol: "keyboard", enabled: m.isConnected) { m.windows?.show(.keyboard) }
            }
            if m.supports(Caps5.dnd) || v5.phoneDnd != nil {
                Card {
                    ToggleRow(title: "Phone Do Not Disturb",
                              detail: v5.phoneDnd?.canSet == false ? "Allow Do Not Disturb access in Tether on your phone."
                                  : "Synced with your Mac's Focus when set up in Settings › Mac.",
                              isOn: Binding(get: { v5.phoneDnd?.on ?? false }, set: { m.setPhoneDnd($0) }), divider: false)
                }
            }
            if !m.history.items.isEmpty {
                HStack {
                    SectionLabel(text: "Latest notifications")
                    Spacer()
                    Button("See all") { v5.page = .notifications }.buttonStyle(.plain).font(Typo.sans(11,
                        .medium)).foregroundColor(Mono.muted)
                }
                VStack(spacing: 0) {
                        ForEach(m.history.items.prefix(4)) { n in
                        NotificationRow(n: n, privacy: m.settings.notifications.privacyMode, onDismiss: { m.dismiss(n.key) })
                            .swipeToRemove { m.dismiss(n.key) }
                            .transition(Motion.listRow(reduce))
                    }
                }
                .tetherCard()
                .animation(Motion.spring(reduce), value: m.history.items.prefix(4).map(\.key))
            }
        }
    }

    private var homeLine: String {
        guard m.peer != nil else { return "Not paired" }
        guard m.isConnected else { return "Waiting for your phone" }
        var parts = [m.onWifi ? "Connected · Wi-Fi" : "Connected · Bluetooth"]
        if let b = m.battery { parts.append("\(b.level)%\(b.charging ? " charging" : "")") }
        return parts.joined(separator: " · ")
    }
}

struct OtpCard: View {
    @EnvironmentObject var m: AppModel
    let otp: OtpPayload
    var body: some View {
        Card {
            HStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("CODE FROM \(otp.app.uppercased())").font(Typo.mono(10, .medium)).tracking(0.8).foregroundColor(Mono.muted)
                    Text(otp.code).font(Typo.mono(26, .semibold)).foregroundColor(Mono.text).textSelection(.enabled)
                }
                Spacer()
                GhostButton(title: m.v5.succeeded.contains("otp") ? "Copied ✓" : (m.settings.otpAutoCopy ? "Copy again" : "Copy")) {
                    m.copyOtp()
                    m.flashSuccess("otp")
                }
            }
            .padding(14)
        }
    }
}

/// A "Continue on Mac" card: thumbnail, what it is, and one main action (see the mockup).
struct ContinueCardView: View {
    @EnvironmentObject var m: AppModel
    let state: ContinueCardState
    @State private var hover = false
    /// 0.5.3: the card's own thumbnail when it shows a picture, else a fresh one asked from the phone.
    @State private var thumb: NSImage?
    @State private var refreshKey: String?

    private var c: ContinuePayload { state.card }

    private var label: String {
        switch c.kind {
        case ContinueKinds.photo: return "PHOTO"
        case ContinueKinds.download: return "DOWNLOAD"
        case ContinueKinds.link: return "COPIED LINK"
        case ContinueKinds.share: return "SHARED"
        case ContinueKinds.song: return "LISTENING"
        case ContinueKinds.tab: return "BROWSER TAB"
        default: return c.kind.uppercased()
        }
    }

    private var action: String {
        switch c.kind {
        case ContinueKinds.photo, ContinueKinds.download: return "Open on Mac"
        case ContinueKinds.song: return "Play on Mac"
        default: return c.url != nil ? "Open" : "Copy"
        }
    }

    private var symbol: String {
        switch c.kind {
        case ContinueKinds.photo: return "photo"
        case ContinueKinds.download: return "arrow.down.doc"
        case ContinueKinds.song: return "music.note"
        case ContinueKinds.tab: return "safari"
        default: return "link"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 10).fill(Mono.surfaceHigh)
                if let img = thumb {
                    Image(nsImage: img).resizable().scaledToFill()
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .transition(.opacity)
                } else {
                    Image(systemName: symbol).font(.system(size: 22, weight: .light)).foregroundColor(Mono.muted)
                }
            }
            .frame(width: 196, height: 110)
            .clipped()
            .animation(.easeOut(duration: 0.2), value: thumb != nil)
            .onAppear(perform: loadThumb)
            .onChange(of: state.thumb) { _ in loadThumb() }
            .onReceive(NotificationCenter.default.publisher(for: .tetherThumbReady)) { n in
                guard let k = n.object as? String, k == refreshKey, let img = ThumbCache.shared[k], !ThumbCheck.isBlank(img) else { return }
                thumb = img
            }
            Text(label).font(Typo.mono(9, .medium)).tracking(0.8).foregroundColor(Mono.muted)
            Text(c.title).font(Typo.sans(13, .medium)).foregroundColor(Mono.text).lineLimit(2)
            if let s = c.subtitle { Text(s).font(Typo.sans(11)).foregroundColor(Mono.muted).lineLimit(1) }
            HStack {
                GhostButton(title: action) { m.performContinue(c) }
                Spacer()
                Button { m.dismissContinue(c.id) } label: {
                    Image(systemName: "xmark").font(.system(size: 10, weight: .medium)).foregroundColor(Mono.muted)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Dismiss")
            }
        }
        .padding(12)
        .frame(width: 220)
        .tetherCard(highlighted: hover)
        .hoverLift()
        .onHover { hover = $0 }
    }
}

extension ContinueCardView {
    /// A solid-black thumbnail (made before the screenshot was written, or a FLAG_SECURE screen)
    /// is never shown: the placeholder icon stays, and for phone photos a fresh thumbnail is asked
    /// for once the file has settled.
    fileprivate func loadThumb() {
        if let img = NSImage(data: state.thumb), !ThumbCheck.isBlank(img) {
            thumb = img
            return
        }
        thumb = nil
        guard c.kind == ContinueKinds.photo, let root = c.root, let path = c.path else { return }
        let key = m.thumbKey(root: root, path: path) + "@384"
        refreshKey = key
        if let hit = ThumbCache.shared[key], !ThumbCheck.isBlank(hit) {
            thumb = hit
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { m.requestThumb(root: root, path: path, size: 384) }
    }
}

/// Whether a thumbnail is one flat dark colour (TetherCore MediaReady.isBlank on an 8×8 luma grid).
enum ThumbCheck {
    static func isBlank(_ image: NSImage) -> Bool {
        guard let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else { return true }
        let n = 8
        var px = [UInt8](repeating: 0, count: n * n * 4)
        let drawn: Bool = px.withUnsafeMutableBytes { buf in
            guard let ctx = CGContext(data: buf.baseAddress, width: n, height: n, bitsPerComponent: 8, bytesPerRow: n * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.interpolationQuality = .medium
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: n, height: n))
            return true
        }
        guard drawn else { return true }
        let luma = (0..<(n * n)).map { i in (Int(px[i * 4]) * 299 + Int(px[i * 4 + 1]) * 587 + Int(px[i * 4 + 2]) * 114) / 1000 }
        return MediaReady.isBlank(luma)
    }
}

// MARK: - Files

struct FilesPage: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var v5: V5State

    @State private var mode: FilesViewMode = .list
    @Environment(\.accessibilityReduceMotion) private var reduce
    /// 0.5.2: rectangle / ⌘-click selection of files.
    @State private var selection = GridSelection()
    @State private var frames = FrameRegistry()

    private var files: [FsEntry] { v5.folder.entries.filter { !$0.dir } }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                PageTitle(title: "Files", detail: selection.ids.isEmpty ? "Browse your phone. Drag a file to Finder, or download it."
                            : "\(selection.ids.count) selected")
                Spacer()
                if !selection.ids.isEmpty {
                    GhostButton(title: "Download \(selection.ids.count)") { downloadSelected() }
                        .transition(.scale(scale: 0.85).combined(with: .opacity))
                    GhostButton(title: "Clear") { withAnimation(Motion.bouncy(reduce)) { selection.clear() } }
                        .transition(.scale(scale: 0.85).combined(with: .opacity))
                }
                ViewModeToggle(mode: Binding(get: { mode }, set: { new in
                    mode = new
                    UserDefaults.standard.set(new.rawValue, forKey: FilesViewMode.key(root: v5.folder.root))
                }))
            }
            ScrollView(.horizontal, showsIndicators: false) { roots }
            breadcrumb
            content
        }
        .padding(20)
        .onAppear {
            mode = FilesViewMode.load(root: v5.folder.root) { UserDefaults.standard.string(forKey: $0) }
            if v5.folder.entries.isEmpty && !v5.folder.loading { m.openFolder(root: v5.folder.root, path: v5.folder.path) }
        }
        .onChange(of: v5.folder.root) { r in
            withAnimation(Motion.spring(reduce)) { mode = FilesViewMode.load(root: r) { UserDefaults.standard.string(forKey: $0) } }
        }
        .onChange(of: v5.folder.path) { _ in selection.clear() }
        .animation(Motion.bouncy(reduce), value: selection.ids.isEmpty)
        .background(Button("") { withAnimation(Motion.bouncy(reduce)) { selection.clear() } }
            .keyboardShortcut(.cancelAction).opacity(0).allowsHitTesting(false))
    }

    private func path(_ e: FsEntry) -> String { FsPathJoin.join(v5.folder.path, e.name) }

    private func downloadSelected() {
        for e in files where selection.ids.contains(path(e)) {
            m.fetchPhoneFile(root: v5.folder.root, path: path(e), name: e.name)
        }
        withAnimation(Motion.bouncy(reduce)) { selection.clear() }
    }

    private func select(_ ids: [String], additive: Bool) {
        withAnimation(Motion.bouncy(reduce)) { selection.drag(covering: ids, additive: additive) }
    }

    /// ⌘-click toggles, ⇧-click extends; returns true when the click was a selection click.
    private func click(_ e: FsEntry) -> Bool {
        let flags = NSEvent.modifierFlags
        guard !e.dir, flags.contains(.command) || flags.contains(.shift) else { return false }
        withAnimation(Motion.bouncy(reduce)) {
            selection.click(path(e), mode: flags.contains(.command) ? .toggle : .range, order: files.map(path))
        }
        return true
    }

    private var roots: some View {
            HStack(spacing: 6) {
                ForEach(FsRoots.browsable, id: \.self) { r in
                    Button { m.openFolder(root: r) } label: {
                        Label(FsRoots.title(r), systemImage: FsRoots.symbol(r))
                            .font(Typo.sans(12, v5.folder.root == r ? .semibold : .regular))
                            .foregroundColor(v5.folder.root == r ? Mono.text : Mono.muted)
                            .padding(.horizontal, 10).frame(height: 26)
                            .tetherControl(Capsule())
                    }
                    .buttonStyle(PressStyle())
                }
            }
    }

    private var breadcrumb: some View {
        HStack(spacing: 4) {
            Button(FsRoots.title(v5.folder.root)) { m.openFolder(root: v5.folder.root) }.buttonStyle(.plain)
            ForEach(Array(crumbs.enumerated()), id: \.offset) { _, c in
                Image(systemName: "chevron.right").font(.system(size: 9)).foregroundColor(Mono.muted)
                Button(c.name) { m.openFolder(root: v5.folder.root, path: c.path) }.buttonStyle(.plain)
            }
            Spacer()
            if v5.folder.loading { ProgressView().controlSize(.small) }
            Button { m.openFolder(root: v5.folder.root, path: v5.folder.path) } label: { Image(systemName: "arrow.clockwise") }
                .buttonStyle(.plain).accessibilityLabel("Refresh")
        }
        .font(Typo.mono(11)).foregroundColor(Mono.text)
    }

    private var crumbs: [(name: String, path: String)] {
        var out: [(String, String)] = []
        var acc = ""
        for part in v5.folder.path.split(separator: "/") {
            acc = FsPathJoin.join(acc, String(part))
            out.append((String(part), acc))
        }
        return out
    }

    @ViewBuilder private var content: some View {
        if let needs = v5.folder.needs {
            Card {
                Row(title: needs == "storage" ? "Allow file access on your phone" : "Files are off on your phone",
                    detail: "Open Tether on your phone › Setup and allow All files access.", divider: false) {
                    GhostButton(title: "Check again") { m.openFolder(root: v5.folder.root, path: v5.folder.path) }
                }
            }
        } else if let e = v5.folder.error {
            Card { Row(title: e, divider: false) { GhostButton(title: "Retry") { m.openFolder(root: v5.folder.root,
                path: v5.folder.path) } } }
        } else if v5.folder.entries.isEmpty && v5.folder.loading {
            SkeletonRows(count: 8, avatar: 30).tetherCard()
        } else if v5.folder.entries.isEmpty {
            Text("This folder is empty").font(Typo.sans(12)).foregroundColor(Mono.muted).padding(.top, 20)
        } else if mode == .grid {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 104, maximum: 132), spacing: 8)], spacing: 8) {
                    ForEach(Array(v5.folder.entries.enumerated()), id: \.element) { i, e in
                        PhoneFileCell(entry: e, root: v5.folder.root, base: v5.folder.path, selected: selection.ids.contains(path(e)),
                                      onClick: { click(e) })
                            .reportFrame(path(e))
                            .staggerIn(min(i, 24), step: 0.02)
                            .transition(.scale(scale: 0.92).combined(with: .opacity))
                    }
                }
                .padding(.bottom, 24)
                if v5.folder.more { GhostButton(title: "Load more") { m.loadMoreFiles() }.padding(10) }
            }
            .id(v5.folder.root + "/" + v5.folder.path)
            .marqueeSelection(registry: frames, order: { files.map(path) }, onSelect: select)
            .transition(.opacity)
        } else {
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(Array(v5.folder.entries.enumerated()), id: \.element) { i, e in
                        PhoneFileRow(entry: e, root: v5.folder.root, base: v5.folder.path, selected: selection.ids.contains(path(e)),
                                     onClick: { click(e) })
                            .reportFrame(path(e))
                            .staggerIn(min(i, 16), step: 0.025)
                    }
                    if v5.folder.more {
                        GhostButton(title: "Load more") { m.loadMoreFiles() }.padding(10)
                    }
                }
                .tetherCard()
                .padding(.bottom, 24)
            }
            .id(v5.folder.root + "/" + v5.folder.path)
            .marqueeSelection(registry: frames, order: { files.map(path) }, onSelect: select)
            .transition(.opacity)
        }
    }
}

struct PhoneFileRow: View {
    @EnvironmentObject var m: AppModel
    let entry: FsEntry
    let root: String
    let base: String
    var selected = false
    var onClick: () -> Bool = { false }
    @State private var hover = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    private var path: String { FsPathJoin.join(base, entry.name) }

    var body: some View {
        HStack(spacing: 10) {
            thumb
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name).font(Typo.sans(13)).foregroundColor(Mono.text).lineLimit(1)
                Text(entry.dir ? "Folder" : "\(ByteCountFormatter.string(fromByteCount: entry.size, countStyle: .file)) · \(date)")
                    .font(Typo.mono(10)).foregroundColor(Mono.muted)
            }
            Spacer()
            if !entry.dir && hover {
                SmallIcon(symbol: "eye", label: "Preview") { preview() }
                SmallIcon(symbol: "arrow.down.circle", label: "Download") { m.fetchPhoneFile(root: root, path: path, name: entry.name) }
            }
        }
        .padding(.horizontal, 12).frame(height: 46)
        .background(selected ? Mono.surfaceHigh : (hover ? Mono.surface : Color.clear))
        .overlay(alignment: .leading) {
            if selected {
                Capsule().fill(Mono.text).frame(width: 3, height: 22).padding(.leading, 3).transition(.scale.combined(with: .opacity))
            }
        }
        .offset(x: hover && !reduce ? 2 : 0)
        .animation(.spring(response: 0.3, dampingFraction: 0.7), value: hover)
        .animation(Motion.bouncy(reduce), value: selected)
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture(count: 2) { entry.dir ? m.openFolder(root: root, path: path) : preview() }
        .onTapGesture { if !onClick(), entry.dir { m.openFolder(root: root, path: path) } }
        .onDrag { m.phoneFileDragProvider(entry: entry, root: root, path: path) }
        .contextMenu {
            if !entry.dir {
                Button("Preview") { preview() }
                Button("Download") { m.fetchPhoneFile(root: root, path: path, name: entry.name) }
                Button("Download and open") { m.fetchPhoneFile(root: root, path: path, name: entry.name, open: true) }
                Button("Download to…") { chooseFolder() }
            }
        }
    }

    private var date: String {
        entry.mtime > 0 ? Date(timeIntervalSince1970: TimeInterval(entry.mtime) / 1000).formatted(date: .abbreviated, time: .shortened) : ""
    }

    @ViewBuilder private var thumb: some View {
        let key = m.thumbKey(root: root, path: path)
        Group {
            if !entry.dir && (entry.isImage || entry.isVideo) {
                ThumbImage(key: key, symbol: entry.isImage ? "photo" : "film") { m.requestThumb(root: root, path: path) }
            } else {
                Image(systemName: entry.dir ? "folder" : "doc").font(.system(size: 14, weight: .light)).foregroundColor(Mono.muted)
            }
        }
        .frame(width: 30, height: 30)
        .background(RoundedRectangle(cornerRadius: 6).fill(Mono.surfaceHigh))
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    private func preview() {
        m.v5.preview = PreviewItem(id: path, root: root, path: path, name: entry.name, size: entry.size, date: entry.mtime,
            mime: entry.mime)
    }

    private func chooseFolder() {
        let p = NSOpenPanel()
        p.canChooseDirectories = true
        p.canChooseFiles = false
        p.canCreateDirectories = true
        p.prompt = "Save here"
        if p.runModal() == .OK, let u = p.url { m.fetchPhoneFile(root: root, path: path, name: entry.name, folder: u) }
    }
}

struct SmallIcon: View {
    var symbol: String
    var label: String
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 13)).foregroundColor(Mono.text).frame(width: 26, height: 26)
                .tetherControl(Circle())
        }
        .buttonStyle(PressStyle())
        .help(label)
        .accessibilityLabel(label)
    }
}

/// Large thumbnail and details of a phone file or photo, with Download / Open.
struct PreviewSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    let item: PreviewItem

    private var key: String { m.thumbKey(root: item.root, path: item.path) + "@1024" }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 12).fill(Mono.surfaceHigh)
                if item.mime?.hasPrefix("image/") == true || item.mime?.hasPrefix("video/") == true {
                    ThumbImage(key: key, fallbacks: [m.thumbKey(root: item.root, path: item.path)], fit: true) {
                        m.requestThumb(root: item.root, path: item.path, size: 1024)
                    }
                } else {
                    Image(systemName: "doc").font(.system(size: 40, weight: .ultraLight)).foregroundColor(Mono.muted)
                }
            }
            .frame(width: 460, height: 320)
            Text(item.name).font(Typo.sans(15, .medium)).foregroundColor(Mono.text).lineLimit(2)
            Text("\(ByteCountFormatter.string(fromByteCount: item.size, countStyle: .file))\(item.mime.map { " · \($0)" } ?? "")")
                .font(Typo.mono(11)).foregroundColor(Mono.muted)
            HStack {
                MonoButton(title: "Download") {
                    m.fetchPhoneFile(root: item.root, path: item.path, name: item.name)
                    dismiss()
                }
                MonoButton(title: "Open on Mac", filled: false) {
                    m.fetchPhoneFile(root: item.root, path: item.path, name: item.name, open: true)
                    dismiss()
                }
                MonoButton(title: "Close", filled: false) { dismiss() }
            }
        }
        .padding(20)
        .frame(width: 500)
        .background(Mono.bg)
    }
}

// MARK: - Clipboard history

struct ClipboardPage: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var v5: V5State

    var body: some View {
        PageScroll {
            HStack {
                PageTitle(title: "Clipboard", detail: "The last \(v5.clips.limit) things copied on either device. Click one to "
                        + "copy it again.")
                Spacer()
                if !v5.clips.items.isEmpty { GhostButton(title: "Clear") { m.clearClipHistory() } }
            }
            Card {
                ToggleRow(title: "Keep clipboard history", isOn: $m.settings.clipHistory)
                ToggleRow(title: "Images both ways", detail: "Copy an image on one device, paste it on the other.",
                          isOn: $m.settings.clipImages, divider: false)
            }
            if v5.clips.items.isEmpty {
                Text("Nothing copied yet").font(Typo.sans(12)).foregroundColor(Mono.muted).padding(.top, 10)
            } else {
                VStack(spacing: 0) {
                    ForEach(v5.clips.items) { c in ClipRow(item: c) }
                }
                .tetherCard()
            }
        }
    }
}

struct ClipRow: View {
    @EnvironmentObject var m: AppModel
    let item: ClipItem
    @State private var hover = false

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: item.direction == .fromPhone ? "iphone.gen2" : "laptopcomputer")
                .font(.system(size: 12, weight: .light)).foregroundColor(Mono.muted).frame(width: 18)
            if let f = item.image {
                // 0.5.1: read and decoded off the main thread, then cached (no disk read per redraw).
                ThumbImage(key: "clip/\(f)") { m.loadLocalThumb(key: "clip/\(f)", url: AppModel.clipsDir.appendingPathComponent(f)) }
                    .frame(width: 64, height: 40)
                    .clipShape(RoundedRectangle(cornerRadius: 5))
                Text("Image").font(Typo.sans(12)).foregroundColor(Mono.muted)
            } else {
                Text(item.text ?? "").font(Typo.sans(12)).foregroundColor(Mono.text).lineLimit(2)
            }
            Spacer()
            Text(Date(timeIntervalSince1970: TimeInterval(item.ts) / 1000).formatted(date: .omitted, time: .shortened))
                .font(Typo.mono(10)).foregroundColor(Mono.muted)
            if hover {
                if let t = item.text, !t.isEmpty {
                    SmallIcon(symbol: "pin", label: "Pin to Shelf") { m.pinToShelf(t) }.transition(.scale.combined(with: .opacity))
                } else if let f = item.image {
                    SmallIcon(symbol: "pin", label: "Pin to Shelf") { m.addToShelf(file: AppModel.clipsDir.appendingPathComponent(f)) }
                        .transition(.scale.combined(with: .opacity))
                }
                SmallIcon(symbol: "xmark", label: "Remove") { m.removeClip(item.id) }.transition(.scale.combined(with: .opacity))
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(hover ? Mono.surfaceHigh : Color.clear)
        .animation(.spring(response: 0.28, dampingFraction: 0.7), value: hover)
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture { m.recopy(item) }
        .contextMenu {
            Button("Copy again") { m.recopy(item) }
            if let t = item.text, !t.isEmpty { Button("Pin to Shelf") { m.pinToShelf(t) } }
            Button("Remove") { m.removeClip(item.id) }
        }
    }
}
