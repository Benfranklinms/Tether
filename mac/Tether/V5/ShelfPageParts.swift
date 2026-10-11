import AppKit
import SwiftUI
import TetherCore
import UniformTypeIdentifiers

/// A card's title; double-click (or Rename in the context menu) edits it in place (0.5.3).
struct ShelfTitle: View {
    @EnvironmentObject var m: AppModel
    let item: ShelfItem
    /// What the card shows instead of the raw title (ShelfCardText); renaming still edits the title.
    var shown: String?
    @State private var draft = ""
    @FocusState private var focused: Bool

    private var editing: Bool { m.v5.shelfRenaming == item.id }

    var body: some View {
        Group {
            if editing {
                TextField("Name", text: $draft)
                    .textFieldStyle(.plain).font(Typo.sans(15, .medium)).foregroundColor(Mono.text)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(RoundedRectangle(cornerRadius: 6).fill(Mono.bg))
                    .overlay(RoundedRectangle(cornerRadius: 6).stroke(Mono.text, lineWidth: 1))
                    .focused($focused)
                    .onSubmit(commit)
                    .onExitCommand { m.v5.shelfRenaming = nil }
                    .onAppear {
                        draft = item.title
                        DispatchQueue.main.async {
                            focused = true
                            // Select the name without its extension, as Finder does.
                            DispatchQueue.main.async {
                                guard let editor = NSApp.keyWindow?.firstResponder as? NSTextView else { return }
                                let ext = FileKinds.ext(item.title)
                                let len = (item.title as NSString).length - (ext.isEmpty ? 0 : (ext as NSString).length + 1)
                                editor.setSelectedRange(NSRange(location: 0, length: max(0, len)))
                            }
                        }
                    }
                    .onChange(of: focused) { f in if !f && editing { commit() } }
            } else {
                Text(shown ?? item.title).font(Typo.sans(15, .medium)).foregroundColor(Mono.text).lineLimit(1).truncationMode(.middle)
                    .onTapGesture(count: 2) { m.v5.shelfRenaming = item.id }
                    .help("Double-click to rename")
            }
        }
        .accessibilityAction(named: "Rename") { m.v5.shelfRenaming = item.id }
    }

    private func commit() {
        guard editing else { return }
        m.v5.shelfRenaming = nil
        if m.renameShelf(item.id, to: draft) { m.flashSuccess("shelf-rename-\(item.id)") }
    }
}

/// An image file's thumbnail (from the encrypted local copy); [placeholder] until it loads.
struct ShelfFileThumb<Placeholder: View>: View {
    @EnvironmentObject var m: AppModel
    let item: ShelfItem
    let state: ShelfCache.State
    var side: CGFloat = 44
    @ViewBuilder var placeholder: () -> Placeholder
    @State private var image: NSImage?
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        ZStack {
            if let image {
                Image(nsImage: image).resizable().scaledToFill()
                    .frame(width: side, height: side)
                    .clipShape(RoundedRectangle(cornerRadius: side * 0.22, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: side * 0.22, style: .continuous).stroke(Mono.line))
                    .transition(reduce ? .opacity : .scale(scale: 0.85).combined(with: .opacity))
            } else {
                placeholder()
            }
        }
        .frame(width: side, height: side)
        .animation(Motion.snappy(reduce), value: image != nil)
        .onAppear(perform: load)
        .onChange(of: state) { _ in load() }
        .onChange(of: item.file?.sha256) { _ in load() }
        .accessibilityHidden(true)
    }

    private func load() {
        guard state == .local, let f = item.file, FileKinds.isImage(mime: f.mime, name: f.name) else {
            image = nil
            return
        }
        m.shelfThumbnail(item) { image = $0 }
    }
}
