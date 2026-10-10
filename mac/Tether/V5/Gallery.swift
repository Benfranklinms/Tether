import AppKit
import ImageIO
import SwiftUI
import TetherCore
import UniformTypeIdentifiers

// 0.5.1: a real gallery. Uniform square tiles (no more mosaic), sharp 2× thumbnails decoded off
// the main thread and cached in an NSCache, albums, date sections, filters, multi-select, and a
// viewer that zooms out of its thumbnail.

extension Notification.Name {
    static let tetherThumbReady = Notification.Name("tether.thumbReady")
}

/// Thumbnails by key, in an NSCache (evicted under memory pressure). Setting one notifies only the
/// views showing that key, so a page of 60 arriving thumbnails doesn't redraw the whole window.
final class ThumbCache {
    static let shared = ThumbCache()
    private let cache = NSCache<NSString, NSImage>()

    init() {
        cache.countLimit = 2500
        cache.totalCostLimit = 384 * 1_048_576
    }

    subscript(key: String) -> NSImage? {
        get { cache.object(forKey: key as NSString) }
        set {
            guard let img = newValue else { return cache.removeObject(forKey: key as NSString) }
            let px = img.representations.first.map { $0.pixelsWide * $0.pixelsHigh } ?? 0
            cache.setObject(img, forKey: key as NSString, cost: max(1, px * 4))
            NotificationCenter.default.post(name: .tetherThumbReady, object: key)
        }
    }

    func removeAll() { cache.removeAllObjects() }

    /// Decodes JPEG bytes into a ready bitmap (call off the main thread).
    static func decode(_ data: Data) -> NSImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let opts = [kCGImageSourceShouldCacheImmediately: true] as CFDictionary
        guard let cg = CGImageSourceCreateImageAtIndex(src, 0, opts) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
    }
}

/// A thumbnail that loads itself: cached image, else a shimmer while it is requested.
struct ThumbImage: View {
    let key: String
    /// Smaller keys to show while `key` loads (e.g. the grid thumb under the 1024 px preview).
    var fallbacks: [String] = []
    var symbol: String?
    var fit = false
    let request: () -> Void
    @State private var image: NSImage?
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        ZStack {
            if let image {
                Image(nsImage: image).resizable().interpolation(.high)
                    .aspectRatio(contentMode: fit ? .fit : .fill)
                    .transition(.opacity)
            } else if let symbol {
                Image(systemName: symbol).font(.system(size: 14, weight: .light)).foregroundColor(Mono.muted)
            } else {
                Shimmer()
            }
        }
        .onAppear(perform: load)
        .onReceive(NotificationCenter.default.publisher(for: .tetherThumbReady)) { n in
            guard let k = n.object as? String, k == key || (image == nil && fallbacks.contains(k)) else { return }
            withAnimation(reduce ? nil : .easeOut(duration: 0.22)) { image = ThumbCache.shared[key] ?? ThumbCache.shared[k] }
        }
    }

    private func load() {
        if let i = ThumbCache.shared[key] {
            image = i
            return
        }
        image = fallbacks.lazy.compactMap { ThumbCache.shared[$0] }.first
        request()
    }
}

/// Loading placeholder: a soft highlight band sweeping across the full width (0.5.2: sized to
/// the view so wide rows shimmer edge to edge); static with Reduce Motion.
struct Shimmer: View {
    @State private var phase: CGFloat = -1
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        Rectangle().fill(Mono.surfaceHigh)
            .overlay(GeometryReader { g in
                LinearGradient(colors: [.clear, Mono.text.opacity(0.10), .clear], startPoint: .leading, endPoint: .trailing)
                    .frame(width: max(40, g.size.width * 0.7))
                    .offset(x: phase * g.size.width * 1.2 + g.size.width * 0.15)
                    .opacity(reduce ? 0 : 1)
            })
            .clipped()
            .onAppear {
                guard !reduce else { return }
                withAnimation(.easeInOut(duration: 1.25).repeatForever(autoreverses: false)) { phase = 1 }
            }
    }
}

// MARK: - Photos page

struct PhotosPage: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var v5: V5State
    @Environment(\.accessibilityReduceMotion) private var reduce
    @State private var appeared = false
    /// Tile frames for rectangle selection and the viewer's zoom (0.5.2).
    @State private var frames = FrameRegistry()

    private static let filterTitles = ["all": "All", PhotoFilters.photos: "Photos", PhotoFilters.videos: "Videos",
                                       PhotoFilters.screenshots: "Screenshots"]

    private var tile: Double {
        switch v5.photoZoom {
        case .years: return 64
        case .months: return 86
        case .all: return v5.gallerySize
        }
    }

    var body: some View {
        HStack(spacing: 0) {
            AlbumList(v5: v5).frame(width: 200)
            Rectangle().fill(Mono.line).frame(width: 1)
            VStack(alignment: .leading, spacing: 10) {
                header
                toolbar
                content
            }
            .padding(20)
        }
        .overlay { viewerOverlay }
        .onAppear {
            if v5.library.items.isEmpty && !v5.library.loading { m.loadPhotos() }
            if v5.albums.isEmpty { m.loadAlbums() }
        }
        .background(shortcuts)
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            PageTitle(title: v5.albums.first { $0.id == (v5.album ?? AlbumKinds.all) }?.name ?? "Photos",
                      detail: subtitle)
            Spacer()
            if v5.library.loading { ProgressView().controlSize(.small) }
            GhostButton(title: "Refresh") {
                m.loadAlbums()
                m.loadPhotos()
            }
        }
    }

    private var subtitle: String {
        if !v5.photoSelection.ids.isEmpty { return "\(v5.photoSelection.ids.count) selected" }
        let n = v5.albums.first { $0.id == (v5.album ?? AlbumKinds.all) }?.count
        return n.map { "\($0) items · newest first" } ?? "Your phone's camera roll, newest first."
    }

    // One row when the window is wide enough; otherwise the filters get their own row so nothing is clipped.
    private var toolbar: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                filterControl
                zoomControl
                Spacer(minLength: 8)
                toolbarTrailing
            }
            VStack(alignment: .leading, spacing: 8) {
                filterControl
                HStack(spacing: 12) {
                    zoomControl
                    Spacer(minLength: 8)
                    toolbarTrailing
                }
            }
        }
        .animation(Motion.spring(reduce), value: v5.photoSelection.ids.isEmpty)
    }

    private var filterControl: some View {
        MonoSegmented(options: ["all", PhotoFilters.photos, PhotoFilters.videos, PhotoFilters.screenshots],
                      selection: Binding(get: { v5.photoFilter ?? "all" },
                                         set: { m.setPhotoFilter($0 == "all" ? nil : $0) })) {
            Self.filterTitles[$0] ?? $0
        }
    }

    private var zoomControl: some View {
        MonoSegmented(options: PhotoSections.Zoom.allCases, selection: $v5.photoZoom) { $0.rawValue }
    }

    @ViewBuilder private var toolbarTrailing: some View {
        if !v5.photoSelection.ids.isEmpty {
            GhostButton(title: "Download \(v5.photoSelection.ids.count)") { m.downloadSelectedPhotos() }
            GhostButton(title: "Clear") { withAnimation(Motion.spring(reduce)) { v5.photoSelection.clear() } }
                .transition(.scale(scale: 0.9).combined(with: .opacity))
        } else if v5.photoZoom == .all {
            Image(systemName: "square.grid.3x3").font(.system(size: 10)).foregroundColor(Mono.muted)
            Slider(value: $v5.gallerySize, in: GallerySize.min...GallerySize.max, step: GallerySize.step)
                .frame(width: 110).controlSize(.small)
                .help("Thumbnail size (⌘+ / ⌘−)")
            Image(systemName: "square.grid.2x2").font(.system(size: 12)).foregroundColor(Mono.muted)
        }
    }

    @ViewBuilder private var content: some View {
        if let needs = v5.library.needs {
            Card {
                Row(title: needs == "photos" ? "Allow photo access on your phone" : "Photos are off on your phone",
                    detail: "Photos tab needs Photos and videos access in Tether on your phone.", divider: false) {
                    GhostButton(title: "Fix on phone") { m.openPhoneSetup("photos") }
                }
            }
        } else if let e = v5.library.error {
            Card { Row(title: e, divider: false) { GhostButton(title: "Retry") { m.loadPhotos() } } }
        } else if v5.library.items.isEmpty && v5.library.loading {
            skeleton
        } else if v5.library.items.isEmpty {
            Text("Nothing here yet").font(Typo.sans(12)).foregroundColor(Mono.muted).padding(.top, 20)
        } else {
            grid
        }
    }

    private var skeleton: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: tile, maximum: tile * 1.35), spacing: 4)], spacing: 4) {
            ForEach(0..<24, id: \.self) { _ in
                Shimmer().aspectRatio(1, contentMode: .fit).clipShape(RoundedRectangle(cornerRadius: 4))
            }
        }
    }

    private var grid: some View {
        let sections = PhotoSections.group(v5.library.items, zoom: v5.photoZoom, now: Date())
        let order = v5.library.items.map(\.id)
        return ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: tile, maximum: tile * 1.35), spacing: 4)], spacing: 4,
                      pinnedViews: [.sectionHeaders]) {
                ForEach(Array(sections.enumerated()), id: \.element.title) { si, section in
                    Section {
                        ForEach(Array(section.items.enumerated()), id: \.element.id) { i, p in
                            GalleryTile(item: p, tile: tile, selected: v5.photoSelection.ids.contains(p.id),
                                        hidden: v5.viewer?.id == p.id, order: order, v5: v5)
                                .reportFrame(p.id)
                                .opacity(appeared || reduce || m.demo ? 1 : 0)
                                .scaleEffect(appeared || reduce || m.demo ? 1 : 0.94)
                                .animation(reduce ? nil : Motion.spring(false)?.delay(Stagger.delay(index: si * 8 + i)), value: appeared)
                                .onAppear { if p.id == order.last && v5.library.more { m.loadPhotos(more: true) } }
                        }
                    } header: {
                        SectionHeaderBar(title: section.title, count: section.items.count) {
                            if v5.photoZoom != .all { withAnimation(Motion.spring(reduce)) { v5.photoZoom = .all } }
                        }
                    }
                }
            }
            .padding(.bottom, 20)
        }
        // 0.5.2: drag from a gap or the margins to select a rectangle of photos.
        .marqueeSelection(registry: frames, order: { v5.library.items.map(\.id) }, onSelect: { ids, additive in
            withAnimation(Motion.bouncy(reduce)) { v5.photoSelection.drag(covering: ids, additive: additive) }
        })
        .onAppear { appeared = true }
    }

    @ViewBuilder private var viewerOverlay: some View {
        if let item = v5.viewer {
            PhotoViewer52(item: item, frames: frames, v5: v5)
                .zIndex(10)
        }
    }

    /// ⌘+ / ⌘− size, Esc clears the selection, ⌘A selects all.
    private var shortcuts: some View {
        ZStack {
            Button("") { v5.gallerySize = GallerySize.bigger(v5.gallerySize) }.keyboardShortcut("+", modifiers: .command)
            Button("") { v5.gallerySize = GallerySize.bigger(v5.gallerySize) }.keyboardShortcut("=", modifiers: .command)
            Button("") { v5.gallerySize = GallerySize.smaller(v5.gallerySize) }.keyboardShortcut("-", modifiers: .command)
            Button("") { v5.photoSelection.drag(covering: v5.library.items.map(\.id), additive: true) }
                .keyboardShortcut("a", modifiers: .command)
        }
        .opacity(0).allowsHitTesting(false)
    }
}

/// A pinned date header ("Today", "September 2026").
struct SectionHeaderBar: View {
    let title: String
    let count: Int
    var action: () -> Void = {}

    var body: some View {
        HStack {
            Text(title).font(Typo.sans(13, .semibold)).foregroundColor(Mono.text)
            Text("\(count)").font(Typo.mono(10)).foregroundColor(Mono.muted)
            Spacer()
        }
        .padding(.vertical, 6).padding(.horizontal, 2)
        .background(Mono.bg.opacity(0.94))
        .contentShape(Rectangle())
        .onTapGesture(perform: action)
    }
}

/// One square tile: aspect-fill crop inside a fixed square, so tiles can never overlap.
struct GalleryTile: View {
    @EnvironmentObject var m: AppModel
    let item: PhotoItem
    let tile: Double
    let selected: Bool
    let hidden: Bool
    let order: [String]
    @ObservedObject var v5: V5State
    @State private var hover = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    private var px: Int { ThumbPixels.forTile(tile) }
    private var key: String { m.thumbKey(root: FsRoots.photos, path: item.id) + (px > 256 ? "@\(px)" : "") }
    private var small: String { m.thumbKey(root: FsRoots.photos, path: item.id) }

    var body: some View {
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay(
                ThumbImage(key: key, fallbacks: [small]) { m.requestThumb(root: FsRoots.photos, path: item.id, size: px) }
            )
            .clipShape(RoundedRectangle(cornerRadius: 4))
            .overlay(alignment: .bottomTrailing) { badges }
            .overlay(RoundedRectangle(cornerRadius: 4)
                .stroke(selected ? Mono.text : (hover ? Mono.muted : .clear), lineWidth: selected ? 2 : 1))
            .overlay(alignment: .topTrailing) {
                if selected {
                    Image(systemName: "checkmark.circle.fill").font(.system(size: 15)).foregroundStyle(Mono.bg, Mono.text)
                        .padding(5).transition(.scale.combined(with: .opacity))
                }
            }
            .scaleEffect(selected && !reduce ? 0.94 : (hover && !reduce ? 1.04 : 1))
            .shadow(color: .black.opacity(hover && !selected ? 0.25 : 0), radius: hover ? 10 : 0, y: hover ? 5 : 0)
            .zIndex(hover ? 1 : 0)
            .opacity(hidden ? 0 : 1)
            .animation(Motion.spring(reduce), value: hover)
            .animation(Motion.bouncy(reduce), value: selected)
            .contentShape(Rectangle())
            .onHover { hover = $0 }
            .onTapGesture(count: 2) { open() }
            .onTapGesture { click() }
            .onDrag { m.photoDragProvider(for: item) }
            .contextMenu {
                Button("Open") { open() }
                Button(v5.photoSelection.ids.count > 1 && selected ? "Download \(v5.photoSelection.ids.count) items" : "Download") {
                    if selected && v5.photoSelection.ids.count > 1 { m.downloadSelectedPhotos() } else {
                        m.fetchPhoneFile(root: FsRoots.photos, path: item.id, name: item.name)
                    }
                }
                Button("Download and open") { m.fetchPhoneFile(root: FsRoots.photos, path: item.id, name: item.name, open: true) }
            }
            .accessibilityLabel(item.name)
            .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder private var badges: some View {
        HStack(spacing: 3) {
            if item.fav { Image(systemName: "heart.fill").font(.system(size: 8)) }
            if item.video {
                Image(systemName: "play.fill").font(.system(size: 7))
                if item.dur > 0 { Text(PhotoSections.duration(item.dur)).font(Typo.mono(9, .medium)) }
            }
        }
        .foregroundColor(.white)
        .padding(.horizontal, item.video || item.fav ? 5 : 0).padding(.vertical, 2)
        .background(item.video || item.fav ? Capsule().fill(Color.black.opacity(0.55)) : nil)
        .padding(4)
    }

    private func click() {
        let flags = NSEvent.modifierFlags
        let mode: GridSelection.Click? = flags.contains(.command) ? .toggle : flags.contains(.shift) ? .range : nil
        withAnimation(Motion.bouncy(reduce)) {
            if let mode {
                v5.photoSelection.click(item.id, mode: mode, order: order)
            } else if !v5.photoSelection.ids.isEmpty {
                v5.photoSelection.click(item.id, mode: .plain, order: order)
            } else {
                open()
            }
        }
    }

    private func open() {
        v5.viewer = item // PhotoViewer52 runs the zoom itself, from this tile's frame
    }
}

extension ThumbPixels {
    /// Grid sizes a viewer can fall back to while the 1024 px preview loads.
    static let all = [128, 192, 256, 320, 384, 448, 512]
}

/// Albums from the phone's MediaStore buckets, each with its cover and count.
struct AlbumList: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var v5: V5State
    @Namespace private var ns
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 2) {
                SectionLabel(text: "Albums").padding(.bottom, 4)
                if v5.albums.isEmpty {
                    ForEach(0..<6, id: \.self) { _ in Shimmer().frame(height: 34).clipShape(RoundedRectangle(cornerRadius: 8)) }
                }
                ForEach(v5.albums) { a in row(a) }
            }
            .padding(12)
        }
    }

    private func row(_ a: AlbumInfo) -> some View {
        let on = (v5.album ?? AlbumKinds.all) == a.id
        return Button { m.selectAlbum(a.id) } label: {
            HStack(spacing: 9) {
                Group {
                    if let c = a.cover {
                        ThumbImage(key: m.thumbKey(root: FsRoots.photos, path: c)) { m.requestThumb(root: FsRoots.photos, path: c) }
                    } else {
                        Image(systemName: "photo").font(.system(size: 11)).foregroundColor(Mono.muted)
                    }
                }
                .frame(width: 28, height: 28)
                .clipShape(RoundedRectangle(cornerRadius: 5))
                Text(a.name).font(Typo.sans(12, on ? .semibold : .regular)).lineLimit(1).truncationMode(.tail)
                Spacer(minLength: 4)
                Text("\(a.count)").font(Typo.mono(10)).foregroundColor(Mono.muted)
            }
            .foregroundColor(on ? Mono.text : Mono.muted)
            .padding(.horizontal, 6).frame(height: 36)
            .background {
                if on { RoundedRectangle(cornerRadius: 8).fill(Mono.surfaceHigh).matchedGeometryEffect(id: "album", in: ns) }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
        .animation(Motion.spring(reduce), value: v5.album)
    }
}

// MARK: - Files: grid / list

/// Icon segmented control (list / grid) whose pill slides between options.
struct ViewModeToggle: View {
    @Binding var mode: FilesViewMode
    @Namespace private var ns
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        HStack(spacing: 2) {
            ForEach(FilesViewMode.allCases, id: \.self) { m in
                let on = m == mode
                Button { withAnimation(Motion.spring(reduce)) { mode = m } } label: {
                    Image(systemName: m == .list ? "list.bullet" : "square.grid.2x2")
                        .font(.system(size: 11, weight: on ? .semibold : .regular))
                        .foregroundColor(on ? Mono.bg : Mono.muted)
                        .frame(width: 28, height: 20)
                        .background { if on { Capsule().fill(Mono.accent).matchedGeometryEffect(id: "mode", in: ns) } }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .help(m == .list ? "List" : "Grid")
                .accessibilityLabel(m == .list ? "List view" : "Grid view")
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(2)
        .background(Capsule().fill(Mono.surfaceHigh))
        .overlay(Capsule().stroke(Mono.line))
    }
}

/// A grid cell for a phone file: thumbnail for images and videos, an icon otherwise, name under it.
struct PhoneFileCell: View {
    @EnvironmentObject var m: AppModel
    let entry: FsEntry
    let root: String
    let base: String
    var selected = false
    var onClick: () -> Bool = { false }
    @State private var hover = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    private var path: String { FsPathJoin.join(base, entry.name) }
    private var symbol: String { entry.dir ? "folder" : entry.isImage ? "photo" : entry.isVideo ? "film" : "doc" }

    var body: some View {
        VStack(spacing: 6) {
            Color.clear.aspectRatio(1, contentMode: .fit)
                .overlay {
                    if !entry.dir && (entry.isImage || entry.isVideo) {
                        ThumbImage(key: m.thumbKey(root: root, path: path)) { m.requestThumb(root: root, path: path) }
                    } else {
                        Image(systemName: symbol).font(.system(size: 30, weight: .ultraLight)).foregroundColor(Mono.muted)
                    }
                }
                .background(RoundedRectangle(cornerRadius: 10).fill(Mono.surfaceHigh))
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(alignment: .bottomTrailing) {
                    if entry.isVideo { Image(systemName: "play.fill").font(.system(size: 9)).foregroundColor(.white).padding(6) }
                }
            // One line, shortened in the middle (keeps the extension), never broken mid-word.
            Text(entry.name).font(Typo.sans(11)).foregroundColor(Mono.text).lineLimit(1).truncationMode(.middle)
                .frame(maxWidth: .infinity)
                .help(entry.name)
        }
        .padding(6)
        .background(RoundedRectangle(cornerRadius: 12).fill(selected ? Mono.surfaceHigh : (hover ? Mono.surfaceHigh.opacity(0.6) : .clear)))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(selected ? Mono.text : .clear, lineWidth: 1.5))
        .scaleEffect(selected && !reduce ? 0.95 : (hover && !reduce ? 1.04 : 1))
        .offset(y: hover && !selected && !reduce ? -2 : 0)
        .shadow(color: .black.opacity(hover && !selected ? 0.2 : 0), radius: hover ? 10 : 0, y: hover ? 5 : 0)
        .animation(.spring(response: 0.32, dampingFraction: 0.68), value: hover)
        .animation(Motion.bouncy(reduce), value: selected)
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture(count: 2) { entry.dir ? m.openFolder(root: root, path: path) : preview() }
        .onTapGesture { if !onClick() { if entry.dir { m.openFolder(root: root, path: path) } else { preview() } } }
        .onDrag { m.phoneFileDragProvider(entry: entry, root: root, path: path) }
        .contextMenu {
            if !entry.dir {
                Button("Preview") { preview() }
                Button("Download") { m.fetchPhoneFile(root: root, path: path, name: entry.name) }
                Button("Download and open") { m.fetchPhoneFile(root: root, path: path, name: entry.name, open: true) }
            }
        }
        .accessibilityLabel(entry.name)
    }

    private func preview() {
        m.v5.preview = PreviewItem(id: path, root: root, path: path, name: entry.name, size: entry.size, date: entry.mtime,
                                   mime: entry.mime)
    }
}
