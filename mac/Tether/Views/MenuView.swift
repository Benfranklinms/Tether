import ImageIO
import SwiftUI
import TetherCore
import UniformTypeIdentifiers

/// The menu-bar popover: status, quick actions, notifications and transfers.
struct MenuView: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduce
    @State private var tab: Tab = .notifications
    /// Set when hosted in the menu-bar panel (attach/detach controls); nil in screenshots.
    var panel: PanelControls?
    @State private var dropping = false
    @AppStorage("panelOnlyAlerts") private var panelOnlyAlerts = false
    @Namespace private var ns

    init(initialTab: Tab = .notifications, panel: PanelControls? = nil) {
        _tab = State(initialValue: initialTab)
        self.panel = panel
    }

    private var detached: Bool { panel?.detached == true }

    enum Tab: String, CaseIterable { case notifications = "Notifications", calls = "Calls", transfers = "Transfers" }

    var body: some View {
        VStack(spacing: 0) {
            header.background(panel.map { p in PanelDragArea(onTearOff: p.detach) })
            if m.peer != nil && !m.isConnected && m.pairingURI == nil && m.bluetooth == .on { ReconnectBanner() }
            if m.peer != nil && !m.notificationsAllowed && !panelOnlyAlerts { NotificationsOffBanner() }
            if m.peer != nil && m.showCodesGuide { CodesBanner() }
            if m.peer == nil {
                unpaired
            } else {
                phoneCards
                ActionGrid(open: { open($0.rawValue) })
                tabs
                content
            }
            footer
        }
        .frame(minWidth: 360, idealWidth: 360, maxWidth: detached ? .infinity : 360,
               maxHeight: detached ? .infinity : nil, alignment: .top)
        .overlay(alignment: .topTrailing) { panelButtons }
        .tetherBackground()
        .tetherWindowChrome()
        .overlay(dropOverlay)
        .overlay(alignment: .bottom) { noticeView }
        .onDrop(of: [UTType.fileURL], isTargeted: $dropping, perform: drop)
        .animation(Motion.spring(reduce), value: m.peer?.key)
        .animation(Motion.spring(reduce), value: dropping)
        .animation(Motion.spring(reduce), value: m.notice)
        .animation(Motion.spring(reduce), value: m.notificationsAllowed)
        .animation(Motion.spring(reduce), value: m.isConnected)
        .animation(Motion.spring(reduce), value: m.showCodesGuide)
        .onReceive(m.$panelTabRequest) { t in
            guard let t else { return }
            withAnimation(Motion.spring(reduce)) { tab = t }
            m.panelTabRequest = nil
        }
    }

    // MARK: header

    private var statusTitle: String {
        switch m.link {
        case .connected(let name, _): return name
        case .pairing(_, let name, _): return name
        default: return m.peer?.name ?? "Tether"
        }
    }

    private var statusLine: String {
        switch m.link {
        case .connected(_, let wifi): return wifi ? "Connected · Wi-Fi fast path" : "Connected over Bluetooth"
        case .pairing: return "Pairing…"
        case .handshaking: return "Connecting…"
        case .offline:
            switch m.bluetooth {
            case .off: return "Bluetooth is off"
            case .unauthorized: return "Allow Bluetooth in System Settings"
            case .unsupported: return "This Mac has no Bluetooth LE"
            default: return m.peer == nil ? "Not paired" : "Waiting for your phone"
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 14) {
                TetherMark(linked: m.isConnected, pulse: !m.isConnected && m.peer != nil, size: 44)
                    .matchedGeometryEffect(id: "mark", in: ns)
                VStack(alignment: .leading, spacing: 2) {
                    Text(statusTitle).font(Typo.sans(17, .regular)).foregroundColor(Mono.text).lineLimit(1)
                        .id(statusTitle)
                        .transition(.asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity), removal: .opacity))
                    Text(statusLine).font(Typo.sans(12)).foregroundColor(Mono.muted)
                        .id(statusLine).transition(.opacity)
                }
                .animation(Motion.spring(reduce), value: statusLine)
                Spacer()
            }
            .padding(.trailing, panel == nil ? 0 : 34)
            if m.isConnected {
                HStack(spacing: 6) {
                    Chip(text: "Bluetooth", on: !m.onWifi)
                    if m.onWifi { Chip(text: "Wi-Fi", on: true).transition(.scale(scale: 0.6).combined(with: .opacity)) }
                    Spacer()
                    if let b = m.battery { BatteryBadge(b: b).transition(.opacity) }
                }
                .transition(.move(edge: .top).combined(with: .opacity))
            }
            if let err = m.lastError {
                Text(err).font(Typo.sans(12)).foregroundColor(Mono.text)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(Mono.line))
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .padding(16)
        .animation(Motion.spring(reduce), value: m.isConnected)
        .animation(Motion.spring(reduce), value: m.onWifi)
        .animation(Motion.spring(reduce), value: m.lastError)
        .animation(Motion.spring(reduce), value: m.battery?.level)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(statusTitle). \(statusLine)")
    }

    private var unpaired: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Clipboard, notifications and files between your phone and this Mac. No cloud, no account.")
                .font(Typo.sans(12)).foregroundColor(Mono.muted).fixedSize(horizontal: false, vertical: true)
            MonoButton(title: "Pair a phone") { open("pair") }
        }
        .padding(.horizontal, 16).padding(.bottom, 16)
        .transition(.opacity)
    }

    // MARK: 0.3.0 cards

    @ViewBuilder private var phoneCards: some View {
        VStack(spacing: 8) {
            if let c = m.call, m.settings.callAlerts, m.liveCall?.isOffhook != true {
                CallBanner(call: c).transition(.move(edge: .top).combined(with: .opacity))
            }
            if let live = m.liveCall, live.isOffhook {
                LiveCallCard(call: live).transition(.move(edge: .top).combined(with: .opacity))
            }
            if let np = m.nowPlaying, m.settings.nowPlayingEnabled {
                if m.settings.panel.compactPlayer {
                    CompactNowPlaying(np: np).transition(.opacity)
                } else {
                    NowPlayingCard(np: np).transition(.opacity)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, m.call != nil || m.liveCall != nil || m.nowPlaying != nil ? 12 : 0)
        .animation(Motion.spring(reduce), value: m.call?.id)
        .animation(Motion.spring(reduce), value: m.liveCall?.id)
        .animation(Motion.spring(reduce), value: m.liveCall?.isOffhook)
        .animation(Motion.spring(reduce), value: m.nowPlaying == nil)
        .animation(Motion.spring(reduce), value: m.settings.panel.compactPlayer)
    }

    // MARK: tabs

    private var tabs: some View {
        HStack(spacing: 16) {
            ForEach(Tab.allCases, id: \.self) { t in
                Button {
                    withAnimation(Motion.spring(reduce)) { tab = t }
                } label: {
                    VStack(spacing: 6) {
                        HStack(spacing: 5) {
                            Text(t.rawValue).font(Typo.sans(12, tab == t ? .semibold : .regular))
                            let count = t == .notifications ? m.history.items.count : t == .calls ? m.missedBadge : activeTransfers
                            if count > 0 {
                                Text("\(count)").font(Typo.mono(10, .medium)).foregroundColor(Mono.muted)
                                    .contentTransition(.numericText())
                            }
                        }
                        // One line at its natural width: the bold selected label must never wrap
                        // ("Notificatio/ns" with a count badge next to it).
                        .lineLimit(1)
                        .fixedSize()
                        .foregroundColor(tab == t ? Mono.text : Mono.muted)
                        ZStack {
                            Rectangle().fill(Color.clear).frame(height: 2)
                            if tab == t { Capsule().fill(Mono.text).frame(height: 2).matchedGeometryEffect(id: "tab", in: ns) }
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(tab == t ? .isSelected : [])
            }
            Spacer()
            if tab == .notifications && !m.history.items.isEmpty {
                Button("Clear") { withAnimation(Motion.spring(reduce)) { m.clearHistory() } }
                    .buttonStyle(.plain).font(Typo.sans(11)).foregroundColor(Mono.muted)
                    .lineLimit(1).fixedSize()
            }
        }
        .padding(.horizontal, 16)
        .overlay(alignment: .bottom) { Rectangle().fill(Mono.line).frame(height: 1) }
    }

    private var activeTransfers: Int { m.transfers.filter { !$0.status.isFinal }.count }

    @ViewBuilder private var content: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                switch tab {
                case .notifications:
                    if m.history.items.isEmpty {
                        empty("bell", "Your phone's notifications appear here.")
                    }
                    ForEach(m.history.items, id: \.key) { n in
                        NotificationRow(n: n, privacy: m.settings.notifications.privacyMode,
                                        actionsEnabled: m.settings.notificationActions && m.supports(Caps.reply)) {
                            withAnimation(Motion.spring(reduce)) { m.dismiss(n.key) }
                        }
                        .transition(.asymmetric(
                            insertion: .move(edge: .top).combined(with: .opacity),
                            removal: .move(edge: .trailing).combined(with: .opacity)
                        ))
                    }
                case .calls:
                    CallsTab()
                case .transfers:
                    if !m.recentMedia.isEmpty { RecentMediaStrip(items: m.recentMedia) }
                    if m.visibleTransfers.isEmpty {
                        empty("arrow.up.arrow.down", "Drop files here to send them.\nFiles from your phone land in Downloads/Tether.")
                    }
                    ForEach(m.visibleTransfers, id: \.id) { t in
                        TransferRow(t: t)
                            .transition(.asymmetric(insertion: .move(edge: .top).combined(with: .opacity), removal: .opacity))
                    }
                }
            }
            .animation(Motion.spring(reduce), value: m.history.items.map(\.key))
            .animation(Motion.spring(reduce), value: m.visibleTransfers.map(\.id))
        }
        .id(tab) // a fresh ScrollView per tab, so every tab opens at the top (0.4.2)
        .onAppear { m.refreshReceivedFiles() }
        .frame(minHeight: 300, idealHeight: 300, maxHeight: detached ? .infinity : 300)
        .transition(.opacity)
    }

    private func empty(_ symbol: String, _ text: String) -> some View {
        VStack(spacing: 10) {
            Image(systemName: symbol).font(.system(size: 20, weight: .light)).foregroundColor(Mono.muted)
            Text(text).font(Typo.sans(12)).foregroundColor(Mono.muted).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity).padding(.vertical, 60)
    }

    // MARK: footer

    private var footer: some View {
        HStack {
            Button("Settings…") { open("settings") }.keyboardShortcut(",")
            Spacer()
            if m.peer != nil { Button("Pair…") { open("pair") } }
            Button("Quit") { NSApp.terminate(nil) }.keyboardShortcut("q")
        }
        .buttonStyle(.plain)
        .font(Typo.sans(12))
        .foregroundColor(Mono.muted)
        .padding(.horizontal, 16).padding(.vertical, 10)
        .overlay(alignment: .top) { Rectangle().fill(Mono.line).frame(height: 1) }
    }

    @ViewBuilder private var dropOverlay: some View {
        if dropping {
            RoundedRectangle(cornerRadius: 14)
                .stroke(Mono.text, style: StrokeStyle(lineWidth: 1.5, dash: [6, 5]))
                .background(RoundedRectangle(cornerRadius: 14).fill(Mono.bg.opacity(0.85)))
                .overlay(
                    VStack(spacing: 8) {
                        Image(systemName: "arrow.down.doc").font(.system(size: 26, weight: .light))
                        Text(m.isConnected ? "Drop to send to your phone" : "Drop to send when your phone connects")
                            .font(Typo.sans(13))
                    }.foregroundColor(Mono.text)
                )
                .padding(8)
                .transition(.scale(scale: 0.96).combined(with: .opacity))
        }
    }

    @ViewBuilder private var noticeView: some View {
        if let n = m.notice {
            Text(n).font(Typo.sans(12, .medium)).foregroundColor(Mono.bg)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(Capsule().fill(Mono.accent))
                .padding(.bottom, 52)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .accessibilityAddTraits(.updatesFrequently)
        }
    }

    private func open(_ id: String) {
        guard let w = WindowID(rawValue: id) else { return }
        if let panel { panel.open(w) } else { m.open(w) }
    }

    /// Pin = tear off into a floating panel. Detached: keep-on-top toggle and close (docks back).
    @ViewBuilder private var panelButtons: some View {
        if let p = panel {
            HStack(spacing: 4) {
                if p.detached {
                    PanelButton(symbol: p.onTop ? "pin.fill" : "pin", label: p.onTop ? "Don't keep on top" : "Keep on top",
                                action: p.toggleOnTop)
                    PanelButton(symbol: "xmark", label: "Dock to menu bar", action: p.attach)
                } else {
                    PanelButton(symbol: "rectangle.portrait.on.rectangle.portrait", label: "Detach (or drag the header)",
                                action: p.detach)
                }
            }
            .padding(10)
        }
    }

    private func drop(_ providers: [NSItemProvider]) -> Bool {
        let group = DispatchGroup()
        var urls: [URL] = []
        let lock = NSLock()
        for p in providers where p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
            group.enter()
            p.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                defer { group.leave() }
                guard let data = item as? Data, let url = URL(dataRepresentation: data, relativeTo: nil) else { return }
                var isDir: ObjCBool = false
                guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), !isDir.boolValue else { return }
                lock.lock()
                urls.append(url)
                lock.unlock()
            }
        }
        group.notify(queue: .main) {
            m.sendFiles(urls)
            withAnimation(Motion.spring(reduce)) { tab = .transfers }
        }
        return true
    }
}

struct NotificationRow: View {
    @EnvironmentObject var m: AppModel
    let n: MirroredNotification
    let privacy: Bool
    var actionsEnabled = false
    let onDismiss: () -> Void
    @State private var hover = false
    @State private var replying = false
    @State private var draft = ""
    @FocusState private var focused: Bool
    @Environment(\.accessibilityReduceMotion) private var reduce

    private var buttons: [NotifActionInfo] { actionsEnabled ? n.actions.filter { !$0.reply } : [] }
    private var reply: NotifActionInfo? { actionsEnabled ? n.replyAction : nil }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                Monogram(text: n.app, size: 28)
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(n.app).font(Typo.sans(11)).foregroundColor(Mono.muted)
                        Spacer()
                        Text(relativeTime(n.ts)).font(Typo.sans(10)).foregroundColor(Mono.muted)
                    }
                    if privacy {
                        Text("New notification").font(Typo.sans(13)).foregroundColor(Mono.text)
                    } else {
                        if !n.title.isEmpty { Text(n.title).font(Typo.sans(13, .medium)).foregroundColor(Mono.text).lineLimit(1) }
                        if !n.text.isEmpty { Text(n.text).font(Typo.sans(12)).foregroundColor(Mono.muted).lineLimit(3) }
                    }
                }
                Button(action: onDismiss) {
                    Image(systemName: "xmark").font(.system(size: 9, weight: .semibold)).foregroundColor(Mono.muted)
                        .frame(width: 20, height: 20).background(Circle().fill(Mono.surfaceHigh))
                }
                .buttonStyle(PressStyle())
                .opacity(hover ? 1 : 0)
                .accessibilityLabel("Dismiss")
            }
            if reply != nil || !buttons.isEmpty { actionBar.padding(.leading, 38) }
            if replying { replyField.padding(.leading, 38).transition(.move(edge: .top).combined(with: .opacity)) }
            if let st = m.actionStatus[n.key], st != "sending", st != "sent" {
                Text(st).font(Typo.sans(11)).foregroundColor(Mono.muted).padding(.leading, 38)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(hover ? Mono.surface : Color.clear)
        .onHover { h in withAnimation(.easeOut(duration: 0.12)) { hover = h } }
        .animation(Motion.spring(reduce), value: replying)
        .animation(Motion.spring(reduce), value: m.actionStatus[n.key])
        .accessibilityElement(children: .contain)
        .accessibilityAction(named: "Dismiss", onDismiss)
    }

    private var actionBar: some View {
        HStack(spacing: 6) {
            if reply != nil {
                SmallAction(title: m.actionStatus[n.key] == "sent" ? "Sent" : "Reply", symbol: "arrowshape.turn.up.left") {
                    replying.toggle()
                    if replying { DispatchQueue.main.async { focused = true } }
                }
            }
            ForEach(buttons, id: \.id) { a in
                SmallAction(title: a.title, symbol: nil) { m.performAction(key: n.key, action: a.id, text: nil) }
            }
            Spacer(minLength: 0)
        }
    }

    private var replyField: some View {
        HStack(spacing: 6) {
            TextField("Reply to \(n.title.isEmpty ? n.app : n.title)", text: $draft)
                .textFieldStyle(.plain)
                .font(Typo.sans(12))
                .foregroundColor(Mono.text)
                .focused($focused)
                .onSubmit(send)
                .padding(.horizontal, 10).padding(.vertical, 6)
                .tetherCard(cornerRadius: 8)
            Button(action: send) {
                Image(systemName: m.actionStatus[n.key] == "sending" ? "ellipsis" : "arrow.up")
                    .font(.system(size: 11, weight: .semibold)).foregroundColor(Mono.bg)
                    .frame(width: 26, height: 26).background(Circle().fill(draft.isEmpty ? Mono.muted : Mono.accent))
            }
            .buttonStyle(PressStyle())
            .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .accessibilityLabel("Send reply")
        }
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let r = reply else { return }
        m.performAction(key: n.key, action: r.id, text: text)
        draft = ""
        replying = false
    }
}

/// A compact capsule button for notification actions.
struct SmallAction: View {
    var title: String
    var symbol: String?
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if let symbol { Image(systemName: symbol).font(.system(size: 9, weight: .semibold)) }
                Text(title).font(Typo.sans(11, .medium)).lineLimit(1)
            }
            .foregroundColor(Mono.text)
            .padding(.horizontal, 9).frame(minHeight: 22)
            .tetherControl(Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(PressStyle())
    }
}

/// Phone battery: level glyph (bolt while charging), percent, network.
struct BatteryBadge: View {
    let b: BatteryPayload

    private var symbol: String {
        if b.charging { return "battery.100.bolt" }
        switch b.level {
        case ..<13: return "battery.0"
        case ..<38: return "battery.25"
        case ..<63: return "battery.50"
        case ..<88: return "battery.75"
        default: return "battery.100"
        }
    }

    private var network: String? {
        switch b.net {
        case "wifi": return "Wi-Fi"
        case "cellular": return b.gen ?? "Cellular"
        default: return nil
        }
    }

    var body: some View {
        HStack(spacing: 5) {
            if let network { Text(network).font(Typo.mono(10, .medium)).foregroundColor(Mono.muted) }
            Image(systemName: symbol).font(.system(size: 13)).foregroundColor(b.level <= 20 && !b.charging ? Mono.text : Mono.muted)
            Text("\(b.level)%").font(Typo.mono(11, .medium)).foregroundColor(Mono.text).contentTransition(.numericText())
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Phone battery \(b.level) percent\(b.charging ? ", charging" : "")")
    }
}

/// Recent screenshots/photos from the phone; click opens, drag drops the file anywhere.
struct RecentMediaStrip: View {
    let items: [TransferRecord]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("FROM YOUR PHONE").font(Typo.mono(10, .medium)).tracking(0.8).foregroundColor(Mono.muted)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(items, id: \.id) { r in MediaThumb(record: r) }
                }
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
    }
}

struct MediaThumb: View {
    let record: TransferRecord
    @State private var image: NSImage?
    @State private var hover = false

    private var url: URL? { record.ref.map { URL(fileURLWithPath: $0) } }

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8).fill(Mono.surfaceHigh)
            if let image {
                Image(nsImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "photo").foregroundColor(Mono.muted)
            }
        }
        .frame(width: 64, height: 64)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(hover ? Mono.text : Mono.line))
        .scaleEffect(hover ? 1.04 : 1)
        .animation(.spring(response: 0.3, dampingFraction: 0.7), value: hover)
        .onHover { hover = $0 }
        .onTapGesture { if let url { NSWorkspace.shared.open(url) } }
        .onDrag { url.flatMap { NSItemProvider(contentsOf: $0) } ?? NSItemProvider() }
        .help(record.name)
        .accessibilityLabel(record.name)
        .task(id: record.id) { image = await Self.thumbnail(url) }
    }

    static func thumbnail(_ url: URL?) async -> NSImage? {
        guard let url else { return nil }
        return await Task.detached(priority: .utility) {
            guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
            let opts: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 160,
                kCGImageSourceCreateThumbnailWithTransform: true,
            ]
            guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
            return NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
        }.value
    }
}

struct TransferRow: View {
    @EnvironmentObject var m: AppModel
    let t: TransferRecord
    @Environment(\.accessibilityReduceMotion) private var reduce

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: t.direction == .OUT ? "arrow.up.right" : "arrow.down.left")
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(t.status == .DONE ? Mono.text : Mono.muted)
                .frame(width: 28, height: 28).background(RoundedRectangle(cornerRadius: 7).fill(Mono.surfaceHigh))
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(t.name).font(Typo.sans(13)).foregroundColor(Mono.text).lineLimit(1).truncationMode(.middle)
                    if let via = t.via { Chip(text: via == .WIFI ? "Wi-Fi" : "BT", on: via == .WIFI && !t.status.isFinal) }
                }
                Text(status).font(Typo.sans(11)).foregroundColor(Mono.muted).lineLimit(1)
                if !t.status.isFinal {
                    FluidProgress(value: t.progress).frame(height: 3).transition(.opacity)
                }
            }
            Spacer(minLength: 4)
            actionButton
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .animation(Motion.spring(reduce), value: t.status)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(t.direction == .OUT ? "Sending" : "Receiving") \(t.name). \(status)")
    }

    @ViewBuilder private var actionButton: some View {
        if !t.status.isFinal {
            iconButton("xmark", "Cancel") { m.cancel(t.id) }
        } else if t.status == .FAILED && t.direction == .OUT {
            iconButton("arrow.clockwise", "Retry") { m.retry(t.id) }
        } else if t.status == .DONE {
            iconButton("magnifyingglass", "Show in Finder") { m.reveal(t) }
        } else {
            iconButton("trash", "Remove") { m.remove(t.id) }
        }
    }

    private func iconButton(_ symbol: String, _ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 11)).foregroundColor(Mono.muted).frame(width: 24, height: 24)
        }
        .buttonStyle(PressStyle())
        .help(label)
        .accessibilityLabel(label)
    }

    private var status: String {
        switch t.status {
        case .QUEUED: return "Queued · \(formatBytes(t.size))"
        case .OFFERED: return "Starting…"
        case .ACTIVE: return "\(formatBytes(t.transferred)) of \(formatBytes(t.size))"
        case .WAITING: return t.error ?? "Paused · resumes when connected"
        case .VERIFYING: return "Verifying…"
        case .DONE: return (t.direction == .OUT ? "Sent · " : "Received · ") + formatBytes(t.size)
        case .FAILED: return t.error ?? "Failed"
        case .CANCELLED: return t.error ?? "Cancelled"
        }
    }
}

/// A progress bar whose fill chases each update on a critically-damped spring, so chunky
/// acknowledgements read as smooth motion. Shows a moving sheen while active.
struct FluidProgress: View {
    var value: Double
    @Environment(\.accessibilityReduceMotion) private var reduce
    @State private var sheen = false

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Mono.line)
                Capsule().fill(Mono.accent)
                    .frame(width: max(3, g.size.width * value))
                    .overlay(
                        LinearGradient(colors: [.clear, .white.opacity(0.55), .clear], startPoint: .leading, endPoint: .trailing)
                            .frame(width: 40)
                            .offset(x: sheen ? g.size.width : -40)
                            .opacity(reduce ? 0 : 1),
                        alignment: .leading
                    )
                    .clipShape(Capsule())
                    .animation(Motion.soft(reduce), value: value)
            }
        }
        .onAppear {
            guard !reduce else { return }
            withAnimation(.linear(duration: 1.6).repeatForever(autoreverses: false)) { sheen = true }
        }
        .accessibilityValue("\(Int(value * 100)) percent")
    }
}

/// Small round header button of the panel (detach, keep on top, dock).
private struct PanelButton: View {
    var symbol: String
    var label: String
    var action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 10, weight: .semibold))
                .foregroundColor(hover ? Mono.text : Mono.muted)
                .frame(width: 22, height: 22)
                .background(Circle().fill(hover ? Mono.surfaceHigh : Color.clear))
                .contentShape(Circle())
        }
        .buttonStyle(PressStyle())
        .onHover { hover = $0 }
        .help(label)
        .accessibilityLabel(label)
    }
}
