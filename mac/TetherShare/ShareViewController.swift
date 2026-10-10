import AppKit
import UniformTypeIdentifiers

/// Finder (and any app) Share › Tether: "Send to phone" copies the shared files (or a link /
/// text) into the app-group inbox and opens tether://inbox; "Add to Shelf" (0.5.2) does the
/// same into the Shelf inbox and opens tether://shelf-inbox (or tether://shelf?text=).
final class ShareViewController: NSViewController {
    static let group = "group.in.benfranklin.tether"
    private var toShelf = false

    override func loadView() {
        let v = NSView(frame: NSRect(x: 0, y: 0, width: 320, height: 110))
        let title = NSTextField(labelWithString: "Tether")
        title.font = .systemFont(ofSize: 15, weight: .semibold)
        title.frame = NSRect(x: 20, y: 70, width: 280, height: 22)
        let send = NSButton(title: "Send to phone", target: self, action: #selector(sendTapped))
        send.bezelStyle = .rounded
        send.keyEquivalent = "\r"
        send.frame = NSRect(x: 170, y: 20, width: 130, height: 32)
        let shelf = NSButton(title: "Add to Shelf", target: self, action: #selector(shelfTapped))
        shelf.bezelStyle = .rounded
        shelf.frame = NSRect(x: 30, y: 20, width: 130, height: 32)
        v.addSubview(title)
        v.addSubview(send)
        v.addSubview(shelf)
        view = v
    }

    @objc private func sendTapped() { collect() }

    @objc private func shelfTapped() {
        toShelf = true
        collect()
    }

    private func collect() {
        let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        let providers = items.flatMap { $0.attachments ?? [] }
        guard let inbox = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.group)?
            .appendingPathComponent(toShelf ? "ShelfInbox" : "Inbox") else { return finish(ok: false) }
        try? FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        let group = DispatchGroup()
        var texts: [String] = []
        let lock = NSLock()
        for p in providers {
            if p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                group.enter()
                _ = p.loadObject(ofClass: URL.self) { url, _ in
                    defer { group.leave() }
                    guard let url else { return }
                    let dest = inbox.appendingPathComponent(String(UUID().uuidString.prefix(6)) + "-" + url.lastPathComponent)
                    try? FileManager.default.copyItem(at: url, to: dest)
                }
            } else if p.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                group.enter()
                _ = p.loadObject(ofClass: URL.self) { url, _ in
                    if let url { lock.lock(); texts.append(url.absoluteString); lock.unlock() }
                    group.leave()
                }
            } else if p.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                group.enter()
                _ = p.loadObject(ofClass: NSString.self) { s, _ in
                    if let s = s as? String { lock.lock(); texts.append(s); lock.unlock() }
                    group.leave()
                }
            }
        }
        group.notify(queue: .main) {
            var c = URLComponents()
            c.scheme = "tether"
            if let t = texts.first {
                c.host = self.toShelf ? "shelf" : "send"
                c.queryItems = [URLQueryItem(name: "text", value: t)]
            } else {
                c.host = self.toShelf ? "shelf-inbox" : "inbox"
            }
            if let url = c.url { NSWorkspace.shared.open(url) }
            self.finish(ok: true)
        }
    }

    private func finish(ok: Bool) {
        if ok {
            extensionContext?.completeRequest(returningItems: nil)
        } else {
            extensionContext?.cancelRequest(withError: NSError(domain: "Tether", code: 1))
        }
    }
}
