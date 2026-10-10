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

    var body: some View {
        HStack(spacing: 0) {
            sidebar.frame(width: 210)
            Rectangle().fill(Mono.line).frame(width: 1)
            ZStack(alignment: .top) {
                detail
                    .id(v5.page)
                    .transition(.opacity)
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
        .animation(Motion.spring(reduce), value: v5.page)
        .animation(Motion.spring(reduce), value: v5.searchQuery.isEmpty)
        .animation(Motion.spring(reduce), value: m.notice)
        .onChange(of: v5.page) { p in UserDefaults.standard.set(p.rawValue, forKey: MainPage.storeKey) }
        .onChange(of: v5.searchFocus) { _ in searchFocused = true }
        .sheet(item: $v5.preview) { p in PreviewSheet(item: p).environmentObject(m) }
        .background(Button("") { v5.searchFocus += 1 }.keyboardShortcut("f", modifiers: .command).opacity(0))
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
            ForEach(MainPage.allCases) { p in
                Button {
                    v5.searchQuery = ""
                    v5.page = p
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: p.symbol).font(.system(size: 13, weight: v5.page == p ? .medium : .light)).frame(width: 18)
                        Text(p.rawValue).font(Typo.sans(13, v5.page == p ? .semibold : .regular))
                        Spacer()
                        badge(p)
                    }
                    .foregroundColor(v5.page == p ? Mono.text : Mono.muted)
                    .padding(.horizontal, 10).frame(height: 30)
                    .background(RoundedRectangle(cornerRadius: 8).fill(v5.page == p ? Mono.surfaceHigh : Color.clear))
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                .accessibilityAddTraits(v5.page == p ? .isSelected : [])
            }
            Spacer()
            DeviceFooter()
        }
        .padding(12)
    }

    @ViewBuilder private func badge(_ p: MainPage) -> some View {
        switch p {
        case .notifications where !m.history.items.isEmpty: Chip(text: "\(m.history.items.count)")
        case .messages where m.sms.threads.contains { $0.unread > 0 }: Badge(count: m.sms.threads.filter { $0.unread > 0 }.count)
        case .calls where !m.calls.liveMissed.isEmpty: Badge(count: m.calls.liveMissed.count)
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
    var body: some View {
        HStack(spacing: 10) {
            TetherMark(linked: m.isConnected, pulse: !m.isConnected && m.peer != nil, size: 26)
            VStack(alignment: .leading, spacing: 1) {
                Text(m.peer?.name ?? "No phone").font(Typo.sans(12, .medium)).foregroundColor(Mono.text).lineLimit(1)
                Text(m.isConnected ? (m.onWifi ? "Wi-Fi" : "Bluetooth") : (m.peer == nil ? "Not paired" : "Offline"))
                    .font(Typo.mono(10)).foregroundColor(Mono.muted)
            }
            Spacer()
            if let b = m.battery { Text("\(b.level)%\(b.charging ? "+" : "")").font(Typo.mono(11, .medium)).foregroundColor(Mono.text) }
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
    var body: some View {
        PageScroll {
            HStack {
                PageTitle(title: "Notifications", detail: m.history.items.isEmpty ? "Nothing from your phone right now." : nil)
                Spacer()
                if !m.history.items.isEmpty { GhostButton(title: "Clear all") { m.clearHistory() } }
            }
            VStack(spacing: 0) {
                ForEach(m.history.items) { n in
                    NotificationRow(n: n, privacy: m.settings.notifications.privacyMode,
                                    actionsEnabled: m.settings.notificationActions && m.peerCaps.contains(Caps.reply),
                                    onDismiss: { m.dismiss(n.key) })
                }
            }
            .tetherCard()
        }
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
