import AppKit
import SwiftUI
import TetherCore

/// The windowed app (0.5.0): a sidebar of pages, universal search (⌘F), remembered size,
/// position and page. The menu-bar popover is the slim companion (SlimMenuView).
struct MainWindowView: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var v5: V5State
    @Environment(\.accessibilityReduceMotion) private var reduce
    @FocusState private var searchFocused: Bool
    @Namespace private var sidebarNS

    var body: some View {
        HStack(spacing: 0) {
            sidebar.frame(width: 210)
            Rectangle().fill(Mono.line).frame(width: 1)
            ZStack(alignment: .top) {
                detail
                    .id(v5.page)
                    // 0.5.2: pages rise from the direction of travel (down the sidebar = from below).
                    .transition(Motion.pageTransition(reduce, direction: v5.pageDirection))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                if !v5.searchQuery.isEmpty { SearchResults(v5: v5).transition(.move(edge: .top).combined(with: .opacity)) }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .padding(.top, 28)
        .frame(minWidth: 860, minHeight: 560)
        .tetherBackground()
        .tetherWindowChrome()
        .overlay(alignment: .bottom) { noticeView }
        .overlay {
            if v5.updateSheet {
                UpdateSheet(v5: v5).transition(.opacity)
            } else if v5.whatsNew {
                WhatsNewSheet(v5: v5).transition(.opacity)
            }
        }
        .animation(Motion.spring(reduce), value: v5.updateSheet)
        .animation(Motion.spring(reduce), value: v5.whatsNew)
        .animation(reduce ? Motion.reducedFade : .spring(response: 0.48, dampingFraction: 0.86), value: v5.page)
        .animation(Motion.spring(reduce), value: v5.searchQuery.isEmpty)
        .animation(Motion.spring(reduce), value: m.notice)
        .onChange(of: v5.page) { p in UserDefaults.standard.set(p.rawValue, forKey: MainPage.storeKey) }
        .onChange(of: v5.searchFocus) { _ in searchFocused = true }
        .sheet(item: $v5.preview) { p in PreviewSheet(item: p).environmentObject(m) }
        // ⌘F: the Shelf page has its own search (0.5.3); everywhere else it's universal search.
        .background(Button("") {
            if v5.page == .shelf { v5.shelfSearchFocus += 1 } else { v5.searchFocus += 1 }
        }.keyboardShortcut("f", modifiers: .command).opacity(0))
    }

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").font(.system(size: 12)).foregroundColor(Mono.muted)
                TextField("Search  ⌘F", text: $v5.searchQuery)
                    .textFieldStyle(.plain).font(Typo.sans(12)).foregroundColor(Mono.text)
                    .focused($searchFocused)
                    .onExitCommand { v5.searchQuery = "" }
            }
            .padding(.horizontal, 10).frame(height: 28)
            .tetherControl(RoundedRectangle(cornerRadius: 8))
            .padding(.bottom, 8)
            ForEach(Array(MainPage.allCases.enumerated()), id: \.element) { i, p in
                Button {
                    v5.searchQuery = ""
                    v5.page = p
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: p.symbol).font(.system(size: 13, weight: v5.page == p ? .medium : .light)).frame(width: 18)
                            .symbolBounce(v5.page == p)
                        Text(p.rawValue).font(Typo.sans(13, v5.page == p ? .semibold : .regular))
                            .offset(x: v5.page == p && !reduce ? 2 : 0)
                        Spacer()
                        badge(p)
                    }
                    .foregroundColor(v5.page == p ? Mono.text : Mono.muted)
                    .padding(.horizontal, 10).frame(height: 30)
                    .background {
                        // 0.5.1: the selection slides between pages.
                        if v5.page == p {
                            RoundedRectangle(cornerRadius: 8).fill(Mono.surfaceHigh)
                                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Mono.line.opacity(0.6)))
                                .matchedGeometryEffect(id: "page", in: sidebarNS)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle(scale: 0.96))
                .staggerIn(i, step: 0.03)
                .accessibilityAddTraits(v5.page == p ? .isSelected : [])
            }
            Spacer()
            if m.updatePill { HStack { UpdatePill(); Spacer() }.padding(.bottom, 6) }
            DeviceFooter()
        }
        .padding(12)
    }

    @ViewBuilder private func badge(_ p: MainPage) -> some View {
        switch p {
        case .notifications where !m.history.items.isEmpty: Chip(text: "\(m.history.items.count)")
        case .messages where m.sms.threads.contains { $0.unread > 0 }: Badge(count: m.sms.threads.filter { $0.unread > 0 }.count)
        case .calls where !m.calls.liveMissed.isEmpty: Badge(count: m.calls.liveMissed.count)
        case .shelf where !v5.shelf.items.isEmpty: Chip(text: "\(v5.shelf.items.count)")
        default: EmptyView()
        }
    }

    @ViewBuilder private var detail: some View {
        switch v5.page {
        case .home: HomePage(v5: v5)
        case .messages: MessagesView().padding(.top, -28)
        case .calls: PageScroll { CallsTab() }
        case .notifications: NotificationsPage5()
        case .files: FilesPage(v5: v5)
        case .photos: PhotosPage(v5: v5)
        case .screen: ScreenPage()
        case .clipboard: ClipboardPage(v5: v5)
        case .shelf: ShelfPage(v5: v5)
        case .settings: SettingsView().padding(.top, -28)
        }
    }

    @ViewBuilder private var noticeView: some View {
        if let n = m.notice {
            Text(n).font(Typo.sans(12, .medium)).foregroundColor(Mono.bg)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(Capsule().fill(Mono.accent))
                .padding(.bottom, 18)
                .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }
}

/// A padded scrolling page body.
struct PageScroll<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) { content }
                .padding(20)
                .frame(maxWidth: 760, alignment: .leading)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct PageTitle: View {
    var title: String
    var detail: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(Typo.sans(22, .regular)).foregroundColor(Mono.text)
            if let detail { Text(detail).font(Typo.sans(12)).foregroundColor(Mono.muted) }
        }
        .padding(.bottom, 4)
    }
}

/// Phone name, link and battery at the bottom of the sidebar.
struct DeviceFooter: View {
    @EnvironmentObject var m: AppModel

    private var transport: String {
        m.isConnected ? (m.onWifi ? "Wi-Fi" : "Bluetooth") : (m.peer == nil ? "Not paired" : "Offline")
    }

    private var transportSymbol: String {
        m.isConnected ? (m.onWifi ? "wifi" : "dot.radiowaves.left.and.right") : "antenna.radiowaves.left.and.right.slash"
    }

    var body: some View {
        HStack(spacing: 10) {
            TetherMark(linked: m.isConnected, pulse: !m.isConnected && m.peer != nil, size: 26)
            VStack(alignment: .leading, spacing: 1) {
                Text(m.peer?.name ?? "No phone").font(Typo.sans(12, .medium)).foregroundColor(Mono.text).lineLimit(1)
                // 0.5.2: Bluetooth → Wi-Fi flips live with a symbol replace and a sliding label.
                HStack(spacing: 4) {
                    Image(systemName: transportSymbol).font(.system(size: 9, weight: .medium))
                        .symbolReplace()
                        .symbolSweep(!m.isConnected && m.peer != nil)
                    Text(transport).id(transport)
                        .transition(.asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity),
                                                removal: .move(edge: .top).combined(with: .opacity)))
                }
                .font(Typo.mono(10)).foregroundColor(Mono.muted)
                .clipped()
                .animation(.spring(response: 0.4, dampingFraction: 0.75), value: transport)
            }
            Spacer()
            if let b = m.battery {
                HStack(spacing: 3) {
                    if b.charging {
                        Image(systemName: "bolt.fill").font(.system(size: 9)).foregroundColor(Mono.text)
                            .symbolBreathe(true).transition(.scale.combined(with: .opacity))
                    }
                    Text("\(b.level)%").font(Typo.mono(11, .medium)).foregroundColor(Mono.text).rollingNumber()
                }
                .animation(.spring(response: 0.35, dampingFraction: 0.7), value: b.level)
                .animation(.spring(response: 0.35, dampingFraction: 0.7), value: b.charging)
            }
        }
        .padding(10)
        .tetherCard()
    }
}

// MARK: - Universal search

struct SearchResults: View {
    @EnvironmentObject var m: AppModel
    @ObservedObject var v5: V5State

    private var hits: [SearchHit] {
        let q = v5.searchQuery
        var out: [SearchHit] = []
        for t in m.sms.threads {
            let name = t.names.compactMap { $0 }.first ?? t.addrs.first ?? ""
            if let s = UniversalSearch.score(query: q, title: name, body: t.snippet + " " + t.addrs.joined(separator: " ")) {
                out.append(SearchHit(kind: .message, id: "\(t.id)", title: name, subtitle: t.snippet, ts: t.date, score: s))
            }
        }
        for c in m.calls.log {
            if let s = UniversalSearch.score(query: q, title: c.name ?? c.number, body: c.number) {
                out.append(SearchHit(kind: .call, id: "\(c.id)", title: c.name ?? c.number, subtitle: c.number, ts: c.date, score: s))
            }
        }
        for n in m.history.items {
            if let s = UniversalSearch.score(query: q, title: n.title, body: n.text + " " + n.app) {
                out.append(SearchHit(kind: .notification, id: n.key, title: n.title, subtitle: "\(n.app) · \(n.text)", ts: n.ts, score: s))
            }
        }
        return UniversalSearch.rank(out, limitPerKind: 12)
    }

    var body: some View {
        let list = hits
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                if list.isEmpty {
                    Text("Nothing matches “\(v5.searchQuery)”").font(Typo.sans(13)).foregroundColor(Mono.muted).padding(20)
                }
                ForEach(SearchHit.Kind.allCases, id: \.self) { kind in
                    let group = list.filter { $0.kind == kind }
                    if !group.isEmpty {
                        SectionLabel(text: title(kind))
                        Card {
                            ForEach(Array(group.enumerated()), id: \.element.id) { i, h in
                                Button { open(h) } label: {
                                    Row(title: h.title, detail: h.subtitle, divider: i < group.count - 1) {
                                        Image(systemName: "arrow.up.forward").font(.system(size: 11)).foregroundColor(Mono.muted)
                                    }
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
            }
            .padding(20)
        }
        .background(Mono.bg.opacity(0.97))
    }

    private func title(_ k: SearchHit.Kind) -> String {
        switch k {
        case .message: return "Messages"
        case .contact: return "Contacts"
        case .call: return "Calls"
        case .notification: return "Notifications"
        }
    }

    private func open(_ h: SearchHit) {
        v5.searchQuery = ""
        switch h.kind {
        case .message:
            v5.page = .messages
            if let id = Int64(h.id) { m.selectThread(id) }
        case .call, .contact: v5.page = .calls
        case .notification: v5.page = .notifications
        }
    }
}

// MARK: - Notifications page

struct NotificationsPage5: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduce
    @Namespace private var hero
    @State private var open: String?

    var body: some View {
        PageScroll {
            HStack {
                PageTitle(title: "Notifications", detail: m.history.items.isEmpty ? "Nothing from your phone right now." : nil)
                Spacer()
                if !m.history.items.isEmpty { GhostButton(title: "Clear all") { m.clearHistory() } }
            }
            VStack(spacing: 0) {
                ForEach(Array(m.history.items.enumerated()), id: \.element.key) { i, n in
                    NotificationRow(n: n, privacy: m.settings.notifications.privacyMode,
                                    actionsEnabled: m.settings.notificationActions && m.peerCaps.contains(Caps.reply),
                                    onDismiss: { m.dismiss(n.key) },
                                    hero: hero, heroActive: open != n.key,
                                    onOpen: { withAnimation(Motion.hero(reduce)) { open = n.key } })
                        .swipeToRemove { withAnimation(Motion.spring(reduce)) { m.dismiss(n.key) } }
                        .staggerIn(i)
                        .transition(Motion.listRow(reduce))
                }
            }
            .tetherCard()
            .animation(Motion.bouncy(reduce), value: m.history.items.map(\.key))
            if !m.history.items.isEmpty {
                Text("Click to open · drag sideways to dismiss").font(Typo.sans(11)).foregroundColor(Mono.muted)
            }
        }
        .overlay { detail }
    }

    @ViewBuilder private var detail: some View {
        if let key = open, let n = m.history.items.first(where: { $0.key == key }) {
            ZStack {
                Mono.bg.opacity(0.72).ignoresSafeArea()
                    .onTapGesture { close() }
                    .transition(.opacity)
                NotificationDetail(n: n, hero: hero, privacy: m.settings.notifications.privacyMode,
                                   actionsEnabled: m.settings.notificationActions && m.peerCaps.contains(Caps.reply),
                                   close: close) {
                    close()
                    m.dismiss(n.key)
                }
                .transition(.opacity.combined(with: .scale(scale: 0.98)))
            }
            .zIndex(5)
            .background(Button("", action: close).keyboardShortcut(.cancelAction).opacity(0))
        }
    }

    private func close() {
        withAnimation(Motion.hero(reduce)) { open = nil }
    }
}

/// A notification opened from its row: the card, monogram and title travel from the row.
struct NotificationDetail: View {
    @EnvironmentObject var m: AppModel
    let n: MirroredNotification
    let hero: Namespace.ID
    let privacy: Bool
    let actionsEnabled: Bool
    let close: () -> Void
    let dismiss: () -> Void
    @State private var draft = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                Monogram(text: n.app, size: 44).hero("n-mono-\(n.key)", in: hero)
                VStack(alignment: .leading, spacing: 3) {
                    Text(n.app).font(Typo.sans(12)).foregroundColor(Mono.muted)
                    Text(relativeTime(n.ts)).font(Typo.mono(10)).foregroundColor(Mono.muted)
                }
                Spacer()
                Button(action: close) {
                    Image(systemName: "xmark").font(.system(size: 10, weight: .semibold)).foregroundColor(Mono.muted)
                        .frame(width: 24, height: 24).background(Circle().fill(Mono.surfaceHigh))
                }
                .buttonStyle(PressStyle()).accessibilityLabel("Close")
            }
            Text(privacy ? "New notification" : (n.title.isEmpty ? n.app : n.title))
                .font(Typo.sans(18, .medium)).foregroundColor(Mono.text)
                .hero("n-title-\(n.key)", in: hero)
            if !privacy && !n.text.isEmpty {
                ScrollView {
                    Text(n.text).font(Typo.sans(13)).foregroundColor(Mono.text).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 220)
                .staggerIn(1)
            }
            if actionsEnabled, let r = n.replyAction {
                HStack(spacing: 8) {
                    TextField("Reply to \(n.title.isEmpty ? n.app : n.title)", text: $draft)
                        .textFieldStyle(.plain).font(Typo.sans(13)).foregroundColor(Mono.text)
                        .padding(.horizontal, 10).padding(.vertical, 7)
                        .tetherCard(cornerRadius: 8)
                        .onSubmit(send)
                    MonoButton(title: "Send") { send() }.frame(width: 80)
                        .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                .staggerIn(2)
                .accessibilityLabel("Reply with \(r.title)")
            }
            HStack(spacing: 8) {
                if actionsEnabled {
                    ForEach(n.actions.filter { !$0.reply }.prefix(3), id: \.id) { a in
                        GhostButton(title: a.title) { m.performAction(key: n.key, action: a.id, text: nil) }
                    }
                }
                Spacer()
                GhostButton(title: "Dismiss on phone", action: dismiss)
            }
            .staggerIn(3)
        }
        .padding(20)
        .frame(width: 520)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Mono.surface)
                .hero("n-card-\(n.key)", in: hero)
        )
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Mono.line))
        .shadow(color: .black.opacity(0.35), radius: 30, y: 14)
    }

    private func send() {
        guard let r = n.replyAction, !draft.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        m.performAction(key: n.key, action: r.id, text: draft)
        draft = ""
    }
}

// MARK: - Phone screen page

struct ScreenPage: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        VStack(spacing: 14) {
            PageTitle(title: "Phone screen", detail: "See and control your phone over Wi-Fi. It opens in its own window so you "
                    + "can keep it beside your work.")
            HStack(spacing: 10) {
                MonoButton(title: "Open phone screen") { m.windows?.show(.screen) }.frame(width: 200)
                MonoButton(title: "Keyboard", filled: false) { m.windows?.show(.keyboard) }.frame(width: 140)
            }
            Spacer()
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A filled count badge (unread texts, missed calls).
struct Badge: View {
    let count: Int
    var body: some View {
        Text("\(count)")
            .font(Typo.mono(10, .semibold))
            .foregroundColor(Mono.bg)
            .padding(.horizontal, 6)
            .frame(minWidth: 18, minHeight: 16)
            .background(Capsule().fill(Mono.text))
            .accessibilityLabel("\(count) new")
    }
}
