import AppKit
import SwiftUI
import TetherCore
import UniformTypeIdentifiers

/// Which folder-name card is up: a new folder (then the card `id` moves into it) or a rename.
enum FolderNaming {
    case new(String?)
    case rename(ShelfFolder)
}

/// 0.5.4 Shelf folders on the Mac (approved design "A · chips"): "+" then one chip per folder with
/// its count. Click opens the folder page; drop a card (or a Finder file) on a chip to put it in;
/// right-click for Rename, Keep offline and Delete folder. The row scrolls sideways and fades at
/// the right edge when there are more chips than fit.
struct ShelfChipRow: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var v5: V5State
    let onNew: () -> Void
    let onRename: (ShelfFolder) -> Void
    let onDelete: (ShelfFolder) -> Void
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        let counts = v5.shelf.counts()
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                Button(action: onNew) {
                    Image(systemName: "plus").font(.system(size: 14, weight: .medium)).foregroundColor(Mono.text)
                        .frame(width: 36, height: 36)
                        .background(Circle().fill(Mono.surface))
                        .overlay(Circle().stroke(Mono.line))
                }
                .buttonStyle(PressStyle())
                .help("New folder")
                .accessibilityLabel("New folder")
                ForEach(v5.shelf.orderedFolders) { f in
                    ShelfChip(folder: f, count: counts[f.id] ?? 0, hovered: v5.shelfChipHover == f.id)
                        .onTapGesture { withAnimation(Motion.snappy(reduce)) { v5.shelfFolder = f.id } }
                        .onDrop(of: [UTType.utf8PlainText, UTType.fileURL], delegate: ChipDrop(folder: f.id, m: m, v5: v5))
                        .contextMenu {
                            Button("Rename") { onRename(f) }
                            Button(v5.shelfPins.folders.contains(f.id) ? "✓ Keep offline" : "Keep offline") {
                                m.toggleKeepOffline(folder: f.id)
                            }
                            Divider()
                            Button("Delete folder…") { onDelete(f) }
                        }
                }
            }
            .padding(.vertical, 4).padding(.trailing, 36)
        }
        .mask(
            HStack(spacing: 0) {
                Rectangle()
                LinearGradient(colors: [.black, .clear], startPoint: .leading, endPoint: .trailing).frame(width: 40)
            }
        )
        .animation(Motion.snappy(reduce), value: v5.shelf.orderedFolders.map(\.id))
    }
}

struct ShelfChip: View {
    let folder: ShelfFolder
    let count: Int
    let hovered: Bool
    @State private var pointer = false
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "folder.fill").font(.system(size: 12)).foregroundColor(hovered ? Mono.bg : Mono.muted)
            Text(folder.name).font(Typo.sans(14, .medium)).foregroundColor(hovered ? Mono.bg : Mono.text).lineLimit(1).fixedSize()
            Text("\(count)").font(Typo.sans(12)).foregroundColor(hovered ? Mono.bg.opacity(0.7) : Mono.muted)
        }
        .padding(.leading, 14).padding(.trailing, 16).frame(height: 36)
        .background(Capsule().fill(hovered ? Mono.accent : (pointer ? Mono.surfaceHigh : Mono.surface)))
        .overlay(Capsule().stroke(hovered ? Mono.accent : Mono.line))
        .scaleEffect(hovered && !reduce ? 1.06 : 1)
        .animation(reduce ? Motion.reducedFade : .spring(response: 0.3, dampingFraction: 0.7), value: hovered)
        .contentShape(Capsule())
        .onHover { pointer = $0 }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Folder \(folder.name), \(count) items")
    }
}

/// A card (or a Finder file) dropped on a folder chip goes into that folder; the chip lights up
/// while something hovers over it. Dropping onto the folder the card is already in does nothing.
struct ChipDrop: DropDelegate {
    let folder: String
    let m: AppModel
    let v5: V5State

    func dropEntered(info: DropInfo) { v5.shelfChipHover = folder }

    func dropExited(info: DropInfo) { if v5.shelfChipHover == folder { v5.shelfChipHover = nil } }

    func dropUpdated(info: DropInfo) -> DropProposal? { DropProposal(operation: .move) }

    func performDrop(info: DropInfo) -> Bool {
        v5.shelfChipHover = nil
        var handled = false
        for p in info.itemProviders(for: [UTType.fileURL]) {
            handled = true
            _ = p.loadObject(ofClass: URL.self) { url, _ in
                if let url, url.isFileURL { DispatchQueue.main.async { m.addToShelf(file: url, folder: folder) } }
            }
        }
        for p in info.itemProviders(for: [UTType.utf8PlainText]) where !handled {
            handled = true
            _ = p.loadObject(ofClass: NSString.self) { s, _ in
                guard let text = s as? String else { return }
                DispatchQueue.main.async {
                    if text.hasPrefix(ShelfPage.dragPrefix) {
                        m.moveShelfItem(String(text.dropFirst(ShelfPage.dragPrefix.count)), toFolder: folder)
                    } else {
                        m.addToShelf(text: text, folder: folder)
                    }
                }
            }
        }
        return handled
    }
}

/// New folder / Rename folder: a small card with the folder tile, the name field and Create.
struct FolderNameCard: View {
    let naming: FolderNaming
    let done: (String?) -> Void
    @State private var name = ""
    @FocusState private var focused: Bool

    private var isNew: Bool { if case .new = naming { return true } else { return false } }
    private var ok: Bool { ShelfFolders.cleanName(name) != nil }

    var body: some View {
        VStack(spacing: 18) {
            HStack {
                Button("Cancel") { done(nil) }.buttonStyle(.plain).font(Typo.sans(13)).foregroundColor(Mono.muted)
                    .keyboardShortcut(.cancelAction)
                Spacer()
                Text(isNew ? "New folder" : "Rename folder").font(Typo.sans(15, .semibold)).foregroundColor(Mono.text)
                Spacer()
                Button { if ok { done(name) } } label: {
                    Text(isNew ? "Create" : "Save").font(Typo.sans(13, .semibold)).foregroundColor(Mono.bg)
                        .padding(.horizontal, 16).frame(height: 28)
                        .background(Capsule().fill(ok ? Mono.accent : Mono.muted.opacity(0.5)))
                }
                .buttonStyle(PressStyle()).disabled(!ok).keyboardShortcut(.defaultAction)
            }
            RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Mono.surfaceHigh).frame(width: 56, height: 56)
                .overlay(Image(systemName: "folder.fill").font(.system(size: 22)).foregroundColor(Mono.text))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Mono.line))
            TextField("Folder name", text: $name)
                .textFieldStyle(.plain).font(Typo.sans(15)).foregroundColor(Mono.text)
                .padding(.horizontal, 14).frame(height: 40)
                .background(RoundedRectangle(cornerRadius: 12).fill(Mono.bg))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(focused ? Mono.text : Mono.line, lineWidth: 1))
                .focused($focused)
                .onSubmit { if ok { done(name) } }
                .onChange(of: name) { n in if n.count > ShelfFolders.nameMax + 8 { name = String(n.prefix(ShelfFolders.nameMax)) } }
        }
        .padding(22)
        .frame(width: 400)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Mono.surface))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Mono.line))
        .shadow(color: .black.opacity(0.35), radius: 30, y: 14)
        .onAppear {
            if case .rename(let f) = naming { name = f.name }
            DispatchQueue.main.async { focused = true }
        }
    }
}

/// Settings › Mac › Shelf: offline files (space used, cap, remove copies). Local to this Mac.
struct ShelfOfflineSettings: View {
    @EnvironmentObject var m: AppModel
    @State private var confirm = false

    var body: some View {
        SectionLabel(text: "Shelf · Offline files")
        Card {
            Row(title: ShelfOffline.usageLabel(used: m.v5.shelfOfflineUsed, cap: m.shelfOfflineCap),
                detail: "Files up to 100 MB are kept on this Mac so they open while your phone is away. Over the limit, "
                    + "the ones opened least recently go first; items you keep offline always stay.", divider: true) {
                EmptyView()
            }
            Row(title: "Storage limit", divider: true) {
                MonoSegmented(options: ShelfOffline.capOptions,
                              selection: Binding(get: { m.shelfOfflineCap }, set: { m.shelfOfflineCap = $0 }),
                              label: { ShelfOffline.capLabel($0) })
            }
            Row(title: "Remove offline copies", detail: "Frees the space. Shelf items stay; files added on this Mac stay.", divider: false) {
                GhostButton(title: "Remove…") { confirm = true }
            }
        }
        .onAppear { m.measureShelfOffline() }
        .alert("Remove offline copies?", isPresented: $confirm) {
            Button("Remove", role: .destructive) { m.removeShelfOfflineCopies() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Shelf items aren't removed. Files come back from your phone when you open them.")
        }
    }
}
