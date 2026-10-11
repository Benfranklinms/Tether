import AppKit
import SwiftUI
import TetherCore
import UniformTypeIdentifiers

/// Shelf (0.5.2): a pinned list synced with the phone — text, links, Wi-Fi, addresses, snippets
/// and files. Up to 500 items (0.5.4; 50 before); they stay until removed. 0.5.4 adds folders: a
/// chip row above the cards ("+" first), a page per folder, drag a card onto a chip to move it,
/// "Move to" in the context menu, and rename / keep offline / delete on a chip. Motion: new cards spring in from the top
/// (synced ones too), removed ones collapse and slide away with an undo bar that springs up,
/// a dragged card lifts and its neighbours glide, a card opens into its detail as a hero, and
/// dragging a file over the page lights the drop target.
struct ShelfPage: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var v5: V5State
    @State private var draft = ""
    @State private var targeted = false
    @State private var dragging: String?
    @State private var selected: String?
    @State private var keyMonitor: Any?
    @Namespace private var hero
    @FocusState private var searchFocused: Bool
    @Environment(\.accessibilityReduceMotion) private var reduce

    static let dragPrefix = "tether-shelf-item:"
    /// 0.5.3: ⌘F search over title, content, file name and type.
    private var searching: Bool { !v5.shelfQuery.trimmingCharacters(in: .whitespaces).isEmpty }
    /// The folder page that is open (nil = the Shelf), if that folder still exists.
    private var folder: ShelfFolder? { v5.shelfFolder.flatMap { id in v5.shelf.folders.first { $0.id == id } } }
    /// This page's cards: the loose items, or one folder's. Search covers the whole Shelf on the
    /// Shelf page and only the folder on a folder page.
    private var pageItems: [ShelfItem] { v5.shelf.items(in: folder?.id) }
    private var items: [ShelfItem] {
        guard searching else { return pageItems }
        return ShelfSearch.filter(folder == nil ? v5.shelf.ordered : pageItems, query: v5.shelfQuery)
    }

    @State private var adding = false
    @State private var naming: FolderNaming?
    @State private var deleting: ShelfFolder?

    var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    if folder == nil && !(v5.shelf.items.isEmpty && v5.shelf.folders.isEmpty) {
                        ShelfChipRow(v5: v5, onNew: { naming = .new(nil) }, onRename: { naming = .rename($0) },
                                     onDelete: { deleting = $0 })
                    }
                    if adding || (v5.shelf.items.isEmpty && folder == nil) {
                        addBar.transition(reduce ? .opacity : .move(edge: .top).combined(with: .opacity))
                    }
                    if v5.shelf.items.isEmpty && folder == nil {
                        empty
                    } else if pageItems.isEmpty && !searching {
                        Text(folder == nil ? "Everything is in folders."
                             : "Nothing in this folder yet. Drag a card onto its chip, or choose Move to on a card.")
                            .font(Typo.sans(13)).foregroundColor(Mono.muted).padding(.vertical, 30).frame(maxWidth: .infinity)
                            .transition(.opacity)
                    } else if items.isEmpty {
                        Text("No match for “\(v5.shelfQuery.trimmingCharacters(in: .whitespaces))”")
                            .font(Typo.sans(13)).foregroundColor(Mono.muted).padding(.vertical, 30).frame(maxWidth: .infinity)
                            .transition(.opacity)
                    } else {
                        ForEach(groups, id: \.id) { g in
                            if let t = g.title {
                                Text(t).font(Typo.sans(13)).foregroundColor(Mono.muted).padding(.top, 8).padding(.leading, 2)
                            }
                            LazyVGrid(columns: [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)], spacing: 14) {
                                ForEach(g.items) { item in card(item) }
                            }
                        }
                        .animation(reduce ? Motion.reducedFade : .spring(response: 0.46, dampingFraction: 0.76), value: items.map(\.id))
                    }
                }
                .padding(.horizontal, 28).padding(.vertical, 22)
                .frame(maxWidth: 1100, alignment: .leading)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            undoBar
        }
        .overlay { dropGlow }
        .overlay { detail }
        .overlay { folderNameSheet }
        .alert(item: $deleting) { f in
            let n = v5.shelf.counts()[f.id] ?? 0
            return Alert(title: Text("Delete “\(f.name)”?"),
                         message: Text(n == 0 ? "The folder is empty. This removes it on both devices."
                                       : "Its \(n) item\(n == 1 ? "" : "s") move back to your Shelf. Nothing is removed."),
                         primaryButton: .destructive(Text("Delete folder")) { m.deleteShelfFolder(f.id) },
                         secondaryButton: .cancel())
        }
        .onChange(of: v5.shelf.folders) { fs in
            // The open folder was deleted (here or on the phone): back to the Shelf.
            if let id = v5.shelfFolder, !fs.contains(where: { $0.id == id }) { v5.shelfFolder = nil }
        }
        .onDrop(of: [UTType.fileURL, UTType.utf8PlainText, UTType.url], isTargeted: $targeted, perform: drop)
        .animation(reduce ? Motion.reducedFade : .spring(response: 0.34, dampingFraction: 0.62), value: targeted)
        .animation(reduce ? Motion.reducedFade : .spring(response: 0.42, dampingFraction: 0.72), value: v5.shelfUndo?.id)
        .animation(Motion.snappy(reduce), value: adding)
        .onAppear(perform: watchKeys)
        .onChange(of: v5.shelfSearchFocus) { _ in searchFocused = true }
        .onDisappear {
            if let k = keyMonitor { NSEvent.removeMonitor(k) }
            keyMonitor = nil
        }
    }

    /// "Today" / "Earlier" groups while the list is newest-first; one untitled group otherwise.
    private struct ShelfGroup: Identifiable {
        let id: String
        let title: String?
        let items: [ShelfItem]
    }

    private var groups: [ShelfGroup] {
        let list = items
        let heads = searching ? [:] : ShelfMeta.sections(list, now: Int64(Date().timeIntervalSince1970 * 1000))
        guard !heads.isEmpty else { return [ShelfGroup(id: "all", title: nil, items: list)] }
        let starts = heads.keys.sorted()
        return starts.enumerated().map { i, start in
            let end = i + 1 < starts.count ? starts[i + 1] : list.count
            return ShelfGroup(id: heads[start] ?? "\(start)", title: heads[start], items: Array(list[start..<end]))
        }
    }

    // MARK: header, add, search

    private var header: some View {
        VStack(spacing: 14) {
            HStack(alignment: .center, spacing: 12) {
                if let f = folder {
                    Button {
                        withAnimation(Motion.snappy(reduce)) { v5.shelfFolder = nil }
                    } label: {
                        Image(systemName: "chevron.left").font(.system(size: 13, weight: .semibold)).foregroundColor(Mono.text)
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(PressStyle()).help("Back to Shelf").accessibilityLabel("Back to Shelf")
                    Image(systemName: "folder.fill").font(.system(size: 18)).foregroundColor(Mono.text)
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Text(f.name).font(Typo.sans(28, .medium)).foregroundColor(Mono.text).lineLimit(1)
                        Text("\(pageItems.count)").font(Typo.mono(14)).foregroundColor(Mono.muted)
                    }
                    .contextMenu { folderMenu(f) }
                } else {
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Text("Shelf").font(Typo.sans(28, .medium)).foregroundColor(Mono.text)
                        if !v5.shelf.items.isEmpty {
                            Text("\(v5.shelf.items.count)").font(Typo.mono(14)).foregroundColor(Mono.muted)
                                .contentTransition(.numericText()).animation(Motion.snappy(reduce), value: v5.shelf.items.count)
                        }
                    }
                }
                Spacer(minLength: 12)
                if !v5.shelf.items.isEmpty || folder != nil { searchBar.frame(maxWidth: 300) }
                Button {
                    withAnimation(Motion.snappy(reduce)) { adding.toggle() }
                } label: {
                    Image(systemName: "plus").font(.system(size: 13, weight: .medium)).foregroundColor(Mono.text)
                        .rotationEffect(.degrees(adding && !reduce ? 45 : 0))
                        .frame(width: 32, height: 32)
                        .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Mono.surface))
                        .overlay(RoundedRectangle(cornerRadius: 9, style: .continuous).stroke(Mono.line))
                }
                .buttonStyle(PressStyle())
                .help("Add to Shelf")
                .accessibilityLabel("Add to Shelf")
            }
            Rectangle().fill(Mono.line).frame(height: 1).padding(.horizontal, -28)
        }
    }

    private var addBar: some View {
        HStack(spacing: 10) {
            TextField("Text, a link, or drop a file", text: $draft, axis: .vertical)
                .textFieldStyle(.plain).font(Typo.sans(13)).foregroundColor(Mono.text)
                .lineLimit(1...4)
                .onSubmit(add)
                .onExitCommand { adding = false }
            Button(action: add) {
                Text("Add").font(Typo.sans(13, .semibold)).foregroundColor(Mono.bg)
                    .padding(.horizontal, 22).frame(height: 30)
                    .background(RoundedRectangle(cornerRadius: 8).fill(draft.isEmpty ? Mono.muted.opacity(0.5) : Mono.accent))
            }
            .buttonStyle(PressStyle())
            .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .keyboardShortcut(.return, modifiers: .command)
        }
        .padding(.leading, 16).padding(.trailing, 8).padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 12).fill(Mono.surface))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(targeted ? Mono.text : Mono.line, lineWidth: targeted ? 1.5 : 1))
        .scaleEffect(targeted && !reduce ? 1.02 : 1)
    }

    private var searchBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").font(.system(size: 12)).foregroundColor(Mono.muted)
            TextField(folder.map { "Search \($0.name)" } ?? "Search", text: $v5.shelfQuery)
                .textFieldStyle(.plain).font(Typo.sans(13)).foregroundColor(Mono.text)
                .focused($searchFocused)
                .onExitCommand { v5.shelfQuery = "" }
            if searching {
                Button { v5.shelfQuery = "" } label: {
                    Image(systemName: "xmark.circle.fill").font(.system(size: 12)).foregroundColor(Mono.muted)
                }
                .buttonStyle(.plain).accessibilityLabel("Clear search").transition(.opacity.combined(with: .scale))
            } else {
                Text("⌘F").font(Typo.mono(10)).foregroundColor(Mono.muted)
                    .padding(.horizontal, 5).padding(.vertical, 2)
                    .overlay(RoundedRectangle(cornerRadius: 4).stroke(Mono.line))
                    .transition(.opacity)
            }
        }
        .padding(.horizontal, 12).frame(height: 32)
        .background(RoundedRectangle(cornerRadius: 10).fill(Mono.surface))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(searchFocused ? Mono.muted : Mono.line, lineWidth: 1))
        .animation(Motion.snappy(reduce), value: searchFocused)
        .animation(Motion.snappy(reduce), value: searching)
    }

    private func add() {
        let t = draft
        draft = ""
        m.addToShelf(text: t)
        withAnimation(Motion.snappy(reduce)) { adding = false }
    }

    private var empty: some View {
        VStack(spacing: 10) {
            Image(systemName: "tray").font(.system(size: 30, weight: .ultraLight)).foregroundColor(Mono.muted)
            Text("Nothing pinned yet").font(Typo.sans(14, .medium)).foregroundColor(Mono.text)
            Text("It shows up on your phone too.")
                .font(Typo.sans(12)).foregroundColor(Mono.muted).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity).padding(.vertical, 50)
        .transition(.opacity.combined(with: .scale(scale: 0.96)))
    }

    // MARK: cards

    private func card(_ item: ShelfItem) -> some View {
        ShelfCard(item: item, hero: hero, heroActive: v5.shelfDetail != item.id,
                  selected: selected == item.id, lifted: dragging == item.id)
            .onTapGesture(count: 2) { open(item) }
            .onTapGesture { withAnimation(Motion.snappy(reduce)) { selected = item.id } }
            .swipeToRemove { remove(item.id) }
            .onDrag {
                dragging = item.id
                return NSItemProvider(object: (Self.dragPrefix + item.id) as NSString)
            }
            .onDrop(of: [UTType.utf8PlainText],
                    delegate: ShelfReorder(target: item.id, items: { items }, dragging: $dragging, move: { id, to in
                guard !searching else { return } // reordering a filtered list would be ambiguous
                withAnimation(reduce ? Motion.reducedFade : .spring(response: 0.38, dampingFraction: 0.8)) { m.moveShelfWithin(id, to: to) }
            }))
            .contextMenu {
                Button(ShelfDetect.action(item.kind)) { m.shelfPrimary(item) }
                Button("Copy") { m.copyShelf(item) }
                Button("Rename") { v5.shelfRenaming = item.id }
                Button("Open details") { open(item) }
                Menu("Move to") {
                    let here = v5.shelf.folderOf(item)
                    Button(here == nil ? "✓ Shelf" : "Shelf") { m.moveShelfItem(item.id, toFolder: nil) }.disabled(here == nil)
                    ForEach(v5.shelf.orderedFolders) { f in
                        Button(here == f.id ? "✓ \(f.name)" : f.name) { m.moveShelfItem(item.id, toFolder: f.id) }.disabled(here == f.id)
                    }
                    Divider()
                    Button("New folder…") { naming = .new(item.id) }
                }
                if item.file != nil {
                    Button(m.shelfKept(item) ? "✓ Keep offline" : "Keep offline") { m.toggleKeepOffline(item: item.id) }
                }
                Divider()
                Button("Remove") { remove(item.id) }
            }
            .transition(reduce ? .opacity : .asymmetric(
                insertion: .modifier(active: CardIn(p: 0), identity: CardIn(p: 1)),
                removal: .modifier(active: CardOut(p: 0), identity: CardOut(p: 1))))
    }

    private func open(_ item: ShelfItem) {
        selected = item.id
        withAnimation(Motion.hero(reduce)) { v5.shelfDetail = item.id }
    }

    private func remove(_ id: String) {
        withAnimation(reduce ? Motion.reducedFade : .spring(response: 0.36, dampingFraction: 0.86)) {
            if selected == id { selected = nil }
            if v5.shelfDetail == id { v5.shelfDetail = nil }
            m.removeFromShelf(id)
        }
    }

    // MARK: undo, drop glow, detail, keys

    @ViewBuilder private var undoBar: some View {
        if let u = v5.shelfUndo {
            HStack(spacing: 14) {
                Text("Removed “\(u.title)”").font(Typo.sans(12, .medium)).foregroundColor(Mono.bg).lineLimit(1)
                Button {
                    withAnimation(reduce ? Motion.reducedFade : .spring(response: 0.46, dampingFraction: 0.72)) { m.undoShelfRemove() }
                } label: {
                    Text("Undo").font(Typo.sans(12, .semibold)).foregroundColor(Mono.bg).underline()
                }
                .buttonStyle(PressStyle())
                .keyboardShortcut("z", modifiers: .command)
            }
            .padding(.horizontal, 18).padding(.vertical, 10)
            .background(Capsule().fill(Mono.accent))
            .shadow(color: .black.opacity(0.3), radius: 16, y: 8)
            .padding(.bottom, 22)
            .transition(reduce ? .opacity
                        : .move(edge: .bottom).combined(with: .scale(scale: 0.8, anchor: .bottom)).combined(with: .opacity))
            .id(u.id)
        }
    }

    @ViewBuilder private var dropGlow: some View {
        if targeted {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Mono.text, style: StrokeStyle(lineWidth: 2, dash: [8, 6]))
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Mono.text.opacity(0.05)))
                .shadow(color: Mono.text.opacity(0.35), radius: 18)
                .overlay(
                    VStack(spacing: 8) {
                        Image(systemName: "tray.and.arrow.down").font(.system(size: 28, weight: .light)).symbolBounce(targeted)
                        Text("Drop to pin on both devices").font(Typo.sans(14, .medium))
                    }
                    .foregroundColor(Mono.text)
                )
                .padding(10)
                .allowsHitTesting(false)
                .transition(.scale(scale: 0.96).combined(with: .opacity))
        }
    }

    @ViewBuilder private var detail: some View {
        if let id = v5.shelfDetail, let item = v5.shelf.items.first(where: { $0.id == id }) {
            ZStack {
                Mono.bg.opacity(0.72).ignoresSafeArea().onTapGesture { closeDetail() }.transition(.opacity)
                ShelfDetail(item: item, hero: hero, close: closeDetail) {
                    closeDetail()
                    remove(item.id)
                }
                .transition(.opacity.combined(with: .scale(scale: 0.98)))
            }
            .zIndex(5)
            .background(Button("", action: closeDetail).keyboardShortcut(.cancelAction).opacity(0))
        }
    }

    private func closeDetail() {
        withAnimation(Motion.hero(reduce)) { v5.shelfDetail = nil }
    }

    @ViewBuilder func folderMenu(_ f: ShelfFolder) -> some View {
        Button("Rename") { naming = .rename(f) }
        Button(v5.shelfPins.folders.contains(f.id) ? "✓ Keep offline" : "Keep offline") { m.toggleKeepOffline(folder: f.id) }
        Divider()
        Button("Delete folder…") { deleting = f }
    }

    @ViewBuilder private var folderNameSheet: some View {
        if let n = naming {
            ZStack {
                Mono.bg.opacity(0.72).ignoresSafeArea().onTapGesture { naming = nil }.transition(.opacity)
                FolderNameCard(naming: n, done: { name in
                    naming = nil
                    guard let name else { return }
                    switch n {
                    case .new(let moveId):
                        if let id = m.createShelfFolder(name), let moveId { m.moveShelfItem(moveId, toFolder: id) }
                    case .rename(let f):
                        m.renameShelfFolder(f.id, to: name)
                    }
                })
                .transition(.opacity.combined(with: .scale(scale: 0.98)))
            }
            .zIndex(6)
        }
    }

    /// ⌫ removes the selected card, Space opens it — but never while typing in a text field.
    private func watchKeys() {
        guard keyMonitor == nil else { return }
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { e in
            guard v5.page == .shelf, !(NSApp.keyWindow?.firstResponder is NSTextView),
                  e.modifierFlags.isDisjoint(with: [.command, .option, .control]) else { return e }
            switch e.keyCode {
            case 51, 117:
                guard let s = selected, v5.shelfDetail == nil else { return e }
                remove(s)
                return nil
            case 49:
                guard let s = selected, let i = items.first(where: { $0.id == s }) else { return e }
                open(i)
                return nil
            default:
                return e
            }
        }
    }

    private func drop(_ providers: [NSItemProvider]) -> Bool {
        var handled = false
        for p in providers {
            if p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                handled = true
                _ = p.loadObject(ofClass: URL.self) { url, _ in
                    if let url, url.isFileURL { DispatchQueue.main.async { m.addToShelf(file: url, folder: v5.shelfFolder) } }
                }
            } else if p.canLoadObject(ofClass: NSString.self) {
                handled = true
                _ = p.loadObject(ofClass: NSString.self) { s, _ in
                    guard let text = s as? String, !text.hasPrefix(Self.dragPrefix) else { return }
                    DispatchQueue.main.async { m.addToShelf(text: text, folder: v5.shelfFolder) }
                }
            }
        }
        dragging = nil
        return handled
    }
}

/// Card entrance: drops in from above with a slight overshoot and un-blurs.
struct CardIn: ViewModifier {
    var p: Double
    func body(content: Content) -> some View {
        content.opacity(p).offset(y: (1 - p) * -40).scaleEffect(0.9 + 0.1 * p, anchor: .top).blur(radius: (1 - p) * 6)
    }
}

/// Card exit: collapses and slides away while fading.
struct CardOut: ViewModifier {
    var p: Double
    func body(content: Content) -> some View {
        content.opacity(p).offset(x: (1 - p) * 60).scaleEffect(x: 0.85 + 0.15 * p, y: 0.4 + 0.6 * p, anchor: .center)
    }
}

/// Drag-to-reorder: the hovered card's slot takes the dragged card; neighbours glide.
struct ShelfReorder: DropDelegate {
    let target: String
    let items: () -> [ShelfItem]
    @Binding var dragging: String?
    let move: (String, Int) -> Void

    func dropEntered(info: DropInfo) {
        guard let d = dragging, d != target, let to = items().firstIndex(where: { $0.id == target }) else { return }
        move(d, to)
    }

    func dropUpdated(info: DropInfo) -> DropProposal? { DropProposal(operation: .move) }

    func performDrop(info: DropInfo) -> Bool {
        let wasReorder = dragging != nil
        dragging = nil
        return wasReorder
    }
}

/// One Shelf card (0.5.3 redesign): a real thumbnail or a document with its type, the title once,
/// one calm line of detail and "Phone · 12m". Hovering lifts the card and shows its actions.
struct ShelfCard: View {
    @EnvironmentObject var m: AppModel
    let item: ShelfItem
    let hero: Namespace.ID
    let heroActive: Bool
    let selected: Bool
    let lifted: Bool
    @State private var hover = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    private var editing: Bool { m.v5.shelfRenaming == item.id }
    private var lines: (title: String, detail: String) {
        ShelfMeta.lines(item, size: formatBytes)
    }

    var body: some View {
        HStack(alignment: .center, spacing: 16) {
            ShelfLeading(item: item, state: m.shelfState(item), size: 46)
            VStack(alignment: .leading, spacing: 4) {
                ShelfTitle(item: item, shown: lines.title).hero("s-title-\(item.id)", in: hero, active: heroActive)
                // Detail and the trailing "Phone · 1h" share one row, so a long detail truncates
                // before the meta instead of running into it.
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    HStack(spacing: 5) {
                        // 0.5.4: no copy on this Mac and the phone away: a quiet cloud (labelled "Available
                        // when connected"), or a spinner while it downloads.
                        if item.file != nil && [.fetching, .notDownloaded].contains(m.shelfState(item)) {
                            if m.shelfState(item) == .fetching {
                                ProgressView().controlSize(.mini).frame(width: 12, height: 12)
                            } else {
                                Image(systemName: "icloud").font(.system(size: 10)).foregroundColor(Mono.muted)
                                    .help(ShelfOffline.away).accessibilityLabel(ShelfOffline.away)
                            }
                        }
                        Text(lines.detail).font(Typo.sans(13)).foregroundColor(Mono.muted).lineLimit(1).truncationMode(.tail)
                    }
                    Spacer(minLength: 0)
                    trailingMeta.transition(.opacity)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.trailing, hover ? 150 : 0)
        }
        .padding(.horizontal, 20).padding(.vertical, 18)
        .frame(minHeight: 84)
        .overlay(alignment: .topTrailing) {
            if hover && !editing && !lifted {
                ShelfHoverActions(item: item).padding(.top, 14).padding(.trailing, 14)
                    .transition(reduce ? .opacity : .scale(scale: 0.85, anchor: .trailing).combined(with: .opacity))
            }
        }
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous).fill(hover || selected ? Mono.surfaceHigh : Mono.surface)
                .hero("s-card-\(item.id)", in: hero, active: heroActive)
        )
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
            .stroke(selected ? Mono.muted : Mono.line, lineWidth: 1))
        .scaleEffect(lifted && !reduce ? 1.04 : (hover && !reduce ? 1.01 : 1))
        .offset(y: hover && !lifted && !reduce ? -1 : 0)
        .shadow(color: .black.opacity(lifted ? 0.4 : (hover ? 0.18 : 0)), radius: lifted ? 24 : 10, y: lifted ? 14 : 4)
        .opacity(lifted ? 0.6 : 1)
        .zIndex(lifted || hover ? 1 : 0)
        .animation(reduce ? Motion.reducedFade : .spring(response: 0.3, dampingFraction: 0.72), value: hover)
        .animation(reduce ? Motion.reducedFade : .spring(response: 0.3, dampingFraction: 0.65), value: lifted)
        .animation(Motion.snappy(reduce), value: selected)
        .animation(Motion.snappy(reduce), value: editing)
        .contentShape(RoundedRectangle(cornerRadius: 14))
        .onHover { hover = $0 }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(lines.title), \(lines.detail), from \(ShelfMeta.source(item))")
    }

    /// "Save ⏎" while renaming, else "Phone · 1h" (hidden while the hover actions show).
    @ViewBuilder private var trailingMeta: some View {
        if editing {
            HStack(spacing: 6) {
                Text("Save").font(Typo.sans(11)).foregroundColor(Mono.muted)
                Text("⏎").font(Typo.mono(10)).foregroundColor(Mono.muted)
                    .padding(.horizontal, 5).padding(.vertical, 1).overlay(RoundedRectangle(cornerRadius: 4).stroke(Mono.line))
            }
            .fixedSize()
        } else if !hover {
            TimelineView(.periodic(from: .now, by: 30)) { ctx in
                Text(ShelfMeta.source(item) + " · " + When.short(item.created, now: Int64(ctx.date.timeIntervalSince1970 * 1000)))
                    .font(Typo.sans(11)).foregroundColor(Mono.muted).lineLimit(1)
            }
            .fixedSize()
        }
    }

    /// "11 KB · Word document" (0.5.3: friendly kinds, not MIME types), plus "Not downloaded yet"
    /// when the copy is missing and the phone is away.
    static func filePreview(_ item: ShelfItem, state: ShelfCache.State) -> String {
        guard let f = item.file else { return "" }
        return [formatBytes(f.size), FileKinds.describe(mime: f.mime, name: f.name),
                state == .notDownloaded ? "Not downloaded yet" : nil].compactMap { $0 }.joined(separator: " · ")
    }

    /// "forms.gle/x7Tq2abc": the link without its scheme or "www.", capped at 48 characters.
    static func linkPreview(_ url: String) -> String {
        var s = url.trimmingCharacters(in: .whitespacesAndNewlines)
        for p in ["https://", "http://", "www."] where s.lowercased().hasPrefix(p) { s = String(s.dropFirst(p.count)) }
        if s.hasSuffix("/") { s.removeLast() }
        return s.count > 48 ? String(s.prefix(47)) + "…" : s
    }
}

struct ShelfTag: View {
    let kind: String
    var body: some View {
        Text(kind.uppercased()).font(Typo.mono(10, .medium)).tracking(0.6).foregroundColor(Mono.text)
            .padding(.horizontal, 8).padding(.vertical, 4)
            .background(RoundedRectangle(cornerRadius: 6).fill(Mono.surfaceHigh))
    }
}

/// The primary action: press-scale, then the label morphs into a check ("Copied" / "Opened").
struct ShelfActionButton: View {
    @EnvironmentObject var m: AppModel
    let item: ShelfItem
    @Environment(\.accessibilityReduceMotion) private var reduce

    private var done: Bool { m.v5.succeeded.contains("shelf-\(item.id)") }
    private var fetching: Bool { m.v5.shelfFetching.contains(item.id) }
    private var title: String { ShelfDetect.action(item.kind) }
    private var doneTitle: String { title == "Copy" ? "Copied" : "Opened" }

    var body: some View {
        Button { m.shelfPrimary(item) } label: {
            ZStack {
                Text(title).opacity(done || fetching ? 0 : 1).scaleEffect(done ? 0.6 : 1)
                HStack(spacing: 4) {
                    Image(systemName: "checkmark").font(.system(size: 10, weight: .bold))
                    Text(doneTitle)
                }
                .opacity(done ? 1 : 0).scaleEffect(done ? 1 : 0.6)
                if fetching { ProgressView().controlSize(.small) }
            }
            .font(Typo.sans(12, .semibold)).foregroundColor(Mono.text)
            .padding(.horizontal, 14).frame(height: 28)
            .background(RoundedRectangle(cornerRadius: 8).fill(done ? Mono.line : Mono.surfaceHigh))
        }
        .buttonStyle(PressStyle(scale: 0.9))
        .animation(reduce ? Motion.reducedFade : .spring(response: 0.34, dampingFraction: 0.6), value: done)
        .accessibilityLabel(done ? doneTitle : title)
    }
}

/// A card opened: the card, tag and title travel from the grid into this sheet.
struct ShelfDetail: View {
    @EnvironmentObject var m: AppModel
    let item: ShelfItem
    let hero: Namespace.ID
    let close: () -> Void
    let remove: () -> Void
    @State private var reveal = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                ShelfTag(kind: item.kind).hero("s-tag-\(item.id)", in: hero)
                Spacer()
                Button(action: close) {
                    Image(systemName: "xmark").font(.system(size: 10, weight: .semibold)).foregroundColor(Mono.muted)
                        .frame(width: 24, height: 24).background(Circle().fill(Mono.surfaceHigh))
                }
                .buttonStyle(PressStyle()).accessibilityLabel("Close")
            }
            // 0.5.4 fix: a snippet titled from its own first line no longer shows that line twice.
            Text(ShelfCardText.detail(item).title).font(Typo.sans(22, .medium)).foregroundColor(Mono.text).textSelection(.enabled)
                .hero("s-title-\(item.id)", in: hero)
            Group {
                if item.kind == ShelfKinds.wifi {
                    HStack {
                        Text(reveal ? (item.secret ?? item.body) : String(repeating: "•", count: max(8, (item.secret ?? item.body).count)))
                            .font(Typo.mono(15)).foregroundColor(Mono.text).textSelection(.enabled)
                        Spacer()
                        GhostButton(title: reveal ? "Hide" : "Show") { withAnimation(Motion.snappy(false)) { reveal.toggle() } }
                    }
                } else if !ShelfCardText.detail(item).preview.isEmpty {
                    ScrollView {
                        Text(ShelfCardText.detail(item).preview).font(item.kind == ShelfKinds.snippet ? Typo.mono(13) : Typo.sans(14))
                            .foregroundColor(Mono.text)
                            .textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 260)
                }
            }
            .staggerIn(1)
            Text(ShelfAge.label(from: item.from, updatedMs: item.created, nowMs: Int64(Date().timeIntervalSince1970 * 1000)))
                .font(Typo.sans(11)).foregroundColor(Mono.muted).staggerIn(2)
            HStack(spacing: 8) {
                ShelfActionButton(item: item)
                if ShelfDetect.action(item.kind) != "Copy" { GhostButton(title: "Copy") { m.copyShelf(item) } }
                GhostButton(title: "Rename") {
                    close()
                    m.v5.shelfRenaming = item.id
                }
                Spacer()
                GhostButton(title: "Remove", action: remove)
            }
            .staggerIn(3)
        }
        .padding(22)
        .frame(width: 520)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Mono.surface).hero("s-card-\(item.id)", in: hero)
        )
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Mono.line))
        .shadow(color: .black.opacity(0.35), radius: 30, y: 14)
    }
}
