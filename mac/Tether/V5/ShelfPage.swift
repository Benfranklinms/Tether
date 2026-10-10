import AppKit
import SwiftUI
import TetherCore
import UniformTypeIdentifiers

/// Shelf (0.5.2): a pinned list synced with the phone — text, links, Wi-Fi, addresses, snippets
/// and files. Up to 50 items; they stay until removed. Motion: new cards spring in from the top
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
    @Environment(\.accessibilityReduceMotion) private var reduce

    static let dragPrefix = "tether-shelf-item:"
    private var items: [ShelfItem] { v5.shelf.ordered }

    var body: some View {
        ZStack(alignment: .bottom) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    PageTitle(title: "Shelf",
                              detail: m.peer.map { "Pinned on both devices · synced with \($0.name)" } ?? "Pinned on both devices")
                    addBar
                    if items.isEmpty {
                        empty
                    } else {
                        LazyVGrid(columns: [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)], spacing: 14) {
                            ForEach(items) { item in card(item) }
                        }
                        .animation(reduce ? Motion.reducedFade : .spring(response: 0.46, dampingFraction: 0.76), value: items.map(\.id))
                    }
                    Text("Shelf keeps up to \(ShelfState.limit) items · drag to reorder · swipe or ⌫ to remove")
                        .font(Typo.sans(11)).foregroundColor(Mono.muted)
                }
                .padding(20)
                .frame(maxWidth: 900, alignment: .leading)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            undoBar
        }
        .overlay { dropGlow }
        .overlay { detail }
        .onDrop(of: [UTType.fileURL, UTType.utf8PlainText, UTType.url], isTargeted: $targeted, perform: drop)
        .animation(reduce ? Motion.reducedFade : .spring(response: 0.34, dampingFraction: 0.62), value: targeted)
        .animation(reduce ? Motion.reducedFade : .spring(response: 0.42, dampingFraction: 0.72), value: v5.shelfUndo?.id)
        .onAppear(perform: watchKeys)
        .onDisappear {
            if let k = keyMonitor { NSEvent.removeMonitor(k) }
            keyMonitor = nil
        }
    }

    // MARK: add

    private var addBar: some View {
        HStack(spacing: 10) {
            TextField("Drop text, a link or a file here…", text: $draft, axis: .vertical)
                .textFieldStyle(.plain).font(Typo.sans(13)).foregroundColor(Mono.text)
                .lineLimit(1...4)
                .onSubmit(add)
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

    private func add() {
        let t = draft
        draft = ""
        m.addToShelf(text: t)
    }

    private var empty: some View {
        VStack(spacing: 10) {
            Image(systemName: "tray").font(.system(size: 30, weight: .ultraLight)).foregroundColor(Mono.muted)
            Text("Nothing pinned yet").font(Typo.sans(14, .medium)).foregroundColor(Mono.text)
            Text("Pin a roll number, a Wi-Fi password, a resume link — it shows up on your phone too.")
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
                withAnimation(reduce ? Motion.reducedFade : .spring(response: 0.38, dampingFraction: 0.8)) { m.moveShelf(id, to: to) }
            }))
            .contextMenu {
                Button(ShelfDetect.action(item.kind)) { m.shelfPrimary(item) }
                Button("Copy") { m.copyShelf(item) }
                Button("Open details") { open(item) }
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
                    if let url, url.isFileURL { DispatchQueue.main.async { m.addToShelf(file: url) } }
                }
            } else if p.canLoadObject(ofClass: NSString.self) {
                handled = true
                _ = p.loadObject(ofClass: NSString.self) { s, _ in
                    guard let text = s as? String, !text.hasPrefix(Self.dragPrefix) else { return }
                    DispatchQueue.main.async { m.addToShelf(text: text) }
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

/// One Shelf card, as in the mockup: type tag, title, preview, divider, "From … · age" and one action.
struct ShelfCard: View {
    @EnvironmentObject var m: AppModel
    let item: ShelfItem
    let hero: Namespace.ID
    let heroActive: Bool
    let selected: Bool
    let lifted: Bool
    @State private var hover = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    private var mono: Bool { item.kind == ShelfKinds.wifi || item.kind == ShelfKinds.snippet }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ShelfTag(kind: item.kind).hero("s-tag-\(item.id)", in: hero, active: heroActive)
            Text(item.title).font(Typo.sans(17, .medium)).foregroundColor(Mono.text).lineLimit(1)
                .hero("s-title-\(item.id)", in: hero, active: heroActive)
            Text(preview).font(mono ? Typo.mono(13) : Typo.sans(13)).foregroundColor(Mono.muted).lineLimit(1).truncationMode(.tail)
            Rectangle().fill(Mono.line).frame(height: 1).padding(.top, 2)
            HStack {
                TimelineView(.periodic(from: .now, by: 30)) { ctx in
                    Text(ShelfAge.label(from: item.from, updatedMs: item.created, nowMs: Int64(ctx.date.timeIntervalSince1970 * 1000)))
                        .font(Typo.sans(11)).foregroundColor(Mono.muted)
                }
                Spacer()
                ShelfActionButton(item: item)
            }
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous).fill(hover || selected ? Mono.surfaceHigh : Mono.surface)
                .hero("s-card-\(item.id)", in: hero, active: heroActive)
        )
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
            .stroke(selected ? Mono.text : Mono.line, lineWidth: selected ? 1.5 : 1))
        .scaleEffect(lifted && !reduce ? 1.05 : (hover && !reduce ? 1.02 : 1))
        .offset(y: hover && !lifted && !reduce ? -2 : 0)
        .shadow(color: .black.opacity(lifted ? 0.4 : (hover ? 0.22 : 0)), radius: lifted ? 24 : 12, y: lifted ? 14 : 6)
        .opacity(lifted ? 0.6 : 1)
        .zIndex(lifted || hover ? 1 : 0)
        .animation(reduce ? Motion.reducedFade : .spring(response: 0.3, dampingFraction: 0.7), value: hover)
        .animation(reduce ? Motion.reducedFade : .spring(response: 0.3, dampingFraction: 0.65), value: lifted)
        .animation(Motion.snappy(reduce), value: selected)
        .contentShape(RoundedRectangle(cornerRadius: 14))
        .onHover { hover = $0 }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(item.kind) \(item.title)")
    }

    private var preview: String {
        switch item.kind {
        case ShelfKinds.link: return ShelfDetect.hostOf(item.body) + String(item.body.drop { $0 != "/" }.prefix(40))
        case ShelfKinds.file: return [item.file.map { formatBytes($0.size) }, item.file?.mime].compactMap { $0 }.joined(separator: " · ")
        default: return item.body.replacingOccurrences(of: "\n", with: " ")
        }
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
            Text(item.title).font(Typo.sans(22, .medium)).foregroundColor(Mono.text).textSelection(.enabled)
                .hero("s-title-\(item.id)", in: hero)
            Group {
                if item.kind == ShelfKinds.wifi {
                    HStack {
                        Text(reveal ? (item.secret ?? item.body) : String(repeating: "•", count: max(8, (item.secret ?? item.body).count)))
                            .font(Typo.mono(15)).foregroundColor(Mono.text).textSelection(.enabled)
                        Spacer()
                        GhostButton(title: reveal ? "Hide" : "Show") { withAnimation(Motion.snappy(false)) { reveal.toggle() } }
                    }
                } else if !item.body.isEmpty {
                    ScrollView {
                        Text(item.body).font(item.kind == ShelfKinds.snippet ? Typo.mono(13) : Typo.sans(14)).foregroundColor(Mono.text)
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
