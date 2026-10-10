import AppKit
import UniformTypeIdentifiers

/// Finder (and any app) Share › Tether: copies the shared files (or a link/text) into the
/// app-group inbox and opens tether://inbox so the app sends them to the phone.
final class ShareViewController: NSViewController {
    static let group = "group.in.benfranklin.tether"

    override func loadView() {
        view = NSView(frame: NSRect(x: 0, y: 0, width: 1, height: 1))
    }

    override func viewDidAppear() {
        super.viewDidAppear()
        let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        let providers = items.flatMap { $0.attachments ?? [] }
        guard let inbox = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.group)?
            .appendingPathComponent("Inbox") else { return finish(ok: false) }
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
                c.host = "send"
                c.queryItems = [URLQueryItem(name: "text", value: t)]
            } else {
                c.host = "inbox"
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
