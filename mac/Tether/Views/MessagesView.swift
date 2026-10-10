import SwiftUI
import TetherCore

/// Messages window (0.3.0): the phone's SMS conversations, search, and replies from the Mac.
struct MessagesView: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduce
    @State private var composing = false
    @State private var newTo = ""

    var body: some View {
        HStack(spacing: 0) {
            sidebar.frame(width: 280)
            Rectangle().fill(Mono.line).frame(width: 1)
            detail.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .padding(.top, 28) // under the transparent title bar
        .tetherBackground()
        .tetherWindowChrome()
        .preferredColorScheme(.dark)
        .onAppear { if m.sms.threads.isEmpty { m.refreshThreads() } }
        .animation(Motion.spring(reduce), value: m.sms.selected)
    }

    // MARK: sidebar

    private var sidebar: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass").font(.system(size: 11)).foregroundColor(Mono.muted)
                    TextField("Search messages", text: Binding(get: { m.sms.search }, set: { m.searchSms($0) }))
                        .textFieldStyle(.plain).font(Typo.sans(12)).foregroundColor(Mono.text)
                    if !m.sms.search.isEmpty {
                        Button { m.searchSms("") } label: { Image(systemName: "xmark.circle.fill").foregroundColor(Mono.muted) }
                            .buttonStyle(.plain).accessibilityLabel("Clear search")
                    }
                }
                .padding(.horizontal, 10).frame(height: 28)
                .tetherControl(RoundedRectangle(cornerRadius: 8), interactive: false)
                Button {
                    composing = true
                    m.sms.selected = nil
                } label: {
                    Image(systemName: "square.and.pencil").font(.system(size: 13)).foregroundColor(Mono.text)
                        .frame(width: 28, height: 28).tetherControl(RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(PressStyle()).help("New message").accessibilityLabel("New message")
                .disabled(!m.supports(Caps3.sms))
            }
            .padding(12)
            Rectangle().fill(Mono.line).frame(height: 1)
            ScrollView {
                LazyVStack(spacing: 0) {
                    if let results = m.sms.searchResults {
                        if results.isEmpty { hint("No messages match.") }
                        ForEach(results) { msg in
                            SearchResultRow(msg: msg) {
                                composing = false
                                m.selectThread(msg.thread)
                            }
                        }
                    } else {
                        ForEach(m.sms.threads) { t in
                            ThreadRow(t: t, selected: m.sms.selected == t.id) {
                                composing = false
                                m.selectThread(t.id)
                                if let i = m.sms.threads.firstIndex(where: { $0.id == t.id }) { m.sms.threads[i].unread = 0 }
                            }
                            .onAppear { if t.id == m.sms.threads.last?.id { m.loadMoreThreads() } }
                        }
                        if m.sms.loadingThreads {
                            HStack(spacing: 8) {
                                ProgressView().controlSize(.small)
                                if m.sms.threads.isEmpty {
                                    Text("Loading conversations…").font(Typo.sans(12)).foregroundColor(Mono.muted)
                                }
                            }
                            .padding(12)
                        } else if let e = m.sms.threadsError, !m.sms.threads.isEmpty {
                            HStack(spacing: 8) {
                                Text(SmsModel.describe(e)).font(Typo.sans(11)).foregroundColor(Mono.muted).lineLimit(2)
                                Spacer(minLength: 4)
                                GhostButton(title: "Retry") { m.refreshThreads() }
                            }
                            .padding(12)
                        }
                    }
                }
            }
        }
    }

    // MARK: detail

    @ViewBuilder private var detail: some View {
        if !m.isConnected {
            placeholder("iphone.gen2.slash", m.peer == nil ? "Pair a phone to see its messages." : "Waiting for your phone…")
        } else if !m.supports(Caps3.sms) {
            placeholder("arrow.down.circle", "Update Tether on your phone to use Messages.")
        } else if !m.settings.smsEnabled {
            placeholder("message", "Messages are off. Turn them on in Settings › Features.")
        } else if m.sms.needs == "sms" {
            placeholder("lock", "Allow SMS for Tether on your phone:\nSettings › On your Mac › Texts on the Mac › Allow SMS access.")
        } else if m.sms.needs == "disabled" {
            placeholder("message", "Texts are off in Tether on your phone (Settings › On your Mac).")
        } else if let e = m.sms.threadsError, m.sms.threads.isEmpty, !composing {
            placeholder("exclamationmark.triangle", SmsModel.describe(e), retry: { m.refreshThreads() })
        } else if composing {
            NewMessage(to: $newTo) { done in if done { composing = false } }
        } else if let id = m.sms.selected {
            Conversation(thread: id)
                .id(id)
                .transition(.opacity)
        } else {
            if m.sms.loadingThreads && m.sms.threads.isEmpty {
                placeholder("ellipsis.message", "Loading conversations from your phone…")
            } else {
                placeholder("message", m.sms.threads.isEmpty ? "No conversations yet." : "Choose a conversation.")
            }
        }
    }

    private func placeholder(_ symbol: String, _ text: String, retry: (() -> Void)? = nil) -> some View {
        VStack(spacing: 12) {
            Image(systemName: symbol).font(.system(size: 26, weight: .light)).foregroundColor(Mono.muted)
            Text(text).font(Typo.sans(13)).foregroundColor(Mono.muted).multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            if let retry { GhostButton(title: "Retry", action: retry).padding(.top, 4) }
        }
        .padding(30)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func hint(_ text: String) -> some View {
        Text(text).font(Typo.sans(12)).foregroundColor(Mono.muted).padding(16)
    }
}

// MARK: - Rows

private struct ThreadRow: View {
    let t: SmsThread
    let selected: Bool
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 10) {
                Monogram(text: t.title, size: 34)
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(t.title).font(Typo.sans(13, t.unread > 0 ? .semibold : .medium)).foregroundColor(Mono.text).lineLimit(1)
                        Spacer(minLength: 6)
                        Text(SmsTime.short(t.date)).font(Typo.mono(10)).foregroundColor(Mono.muted)
                    }
                    HStack(alignment: .top) {
                        Text((t.outLast ? "You: " : "") + t.snippet).font(Typo.sans(12))
                            .foregroundColor(t.unread > 0 ? Mono.text : Mono.muted).lineLimit(2)
                        Spacer(minLength: 4)
                        if t.unread > 0 {
                            Text("\(t.unread)").font(Typo.mono(10, .semibold)).foregroundColor(Mono.bg)
                                .padding(.horizontal, 6).padding(.vertical, 1).background(Capsule().fill(Mono.accent))
                        }
                    }
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 9)
            .background(RoundedRectangle(cornerRadius: 9).fill(selected ? Mono.surfaceHigh : hover ? Mono.surface : Color.clear))
            .padding(.horizontal, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .accessibilityLabel("\(t.title). \(t.snippet)")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct SearchResultRow: View {
    let msg: SmsMessage
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(msg.out ? "You" : (msg.name ?? msg.addr)).font(Typo.sans(12, .medium)).foregroundColor(Mono.text)
                    Spacer()
                    Text(SmsTime.short(msg.date)).font(Typo.mono(10)).foregroundColor(Mono.muted)
                }
                Text(msg.body).font(Typo.sans(12)).foregroundColor(Mono.muted).lineLimit(3)
            }
            .padding(.horizontal, 18).padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Conversation

private struct Conversation: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduce
    let thread: Int64
    @State private var draft = ""

    private var info: SmsThread? { m.sms.threads.first { $0.id == thread } }
    private var messages: [SmsMessage] { m.sms.messages(in: thread) }

    var body: some View {
        VStack(spacing: 0) {
            header
            Rectangle().fill(Mono.line).frame(height: 1)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 4) {
                        if m.sms.moreMessages[thread] == true {
                            Button(m.sms.loadingThread == thread ? "Loading…" : "Load earlier messages") { m.loadOlder(thread) }
                                .buttonStyle(.plain).font(Typo.sans(11)).foregroundColor(Mono.muted).padding(.vertical, 8)
                        } else if m.sms.loadingThread == thread && messages.isEmpty {
                            ProgressView().controlSize(.small).padding(20)
                        }
                        if let e = m.sms.threadErrors[thread] {
                            VStack(spacing: 8) {
                                Text(SmsModel.describe(e)).font(Typo.sans(12)).foregroundColor(Mono.muted)
                                    .multilineTextAlignment(.center)
                                GhostButton(title: "Retry") { m.retryThread(thread) }
                            }
                            .padding(20)
                        }
                        ForEach(Array(messages.enumerated()), id: \.element.id) { i, msg in
                            if i == 0 || !SmsTime.sameDay(messages[i - 1].date, msg.date) {
                                Text(SmsTime.day(msg.date)).font(Typo.mono(10)).foregroundColor(Mono.muted).padding(.vertical, 8)
                            }
                            Bubble(msg: msg, group: (info?.addrs.count ?? 1) > 1, sims: m.sms.sims,
                                   error: ref(of: msg).flatMap { m.sms.sendErrors[$0] }) {
                                if let r = ref(of: msg) { m.retrySms(ref: r) }
                            }
                            .id(msg.id)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                        }
                        Color.clear.frame(height: 1).id("bottom")
                    }
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .animation(Motion.spring(reduce), value: messages.map(\.id))
                }
                .onAppear { proxy.scrollTo("bottom", anchor: .bottom) }
                .onChange(of: messages.last?.id) { _ in
                    withAnimation(Motion.spring(reduce)) { proxy.scrollTo("bottom", anchor: .bottom) }
                }
            }
            Composer(draft: $draft, placeholder: "Text message") {
                guard let t = info else { return false }
                return m.sendSms(to: t.addrs, body: draft, thread: thread)
            }
        }
    }

    private func ref(of msg: SmsMessage) -> String? {
        msg.id.hasPrefix("pending-") ? String(msg.id.dropFirst("pending-".count)) : nil
    }

    private var header: some View {
        HStack(spacing: 10) {
            Monogram(text: info?.title ?? "?", size: 30)
            VStack(alignment: .leading, spacing: 1) {
                Text(info?.title ?? "Conversation").font(Typo.sans(14, .semibold)).foregroundColor(Mono.text).lineLimit(1)
                if let t = info, t.names.contains(where: { $0 != nil }) {
                    Text(t.addrs.joined(separator: ", ")).font(Typo.mono(10)).foregroundColor(Mono.muted).lineLimit(1)
                }
                if let note = m.calls.dialNote, callNumber != nil {
                    Text(note).font(Typo.sans(10)).foregroundColor(Mono.muted).lineLimit(2)
                }
            }
            Spacer()
            if let n = callNumber, m.canDial {
                Button { m.dial(n, name: info?.names.first.flatMap { $0 }) } label: {
                    Label("Call", systemImage: "phone.fill").font(Typo.sans(12, .medium)).foregroundColor(Mono.text)
                        .padding(.horizontal, 12).frame(minHeight: 26).tetherControl(Capsule()).contentShape(Capsule())
                }
                .buttonStyle(PressStyle()).disabled(m.calls.dialing != nil)
                .help("Call \(info?.title ?? n) from your phone").accessibilityLabel("Call \(info?.title ?? n)")
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
    }

    /// One-to-one conversations with a real number (not "AX-BANK" style senders) can be called.
    private var callNumber: String? {
        guard let t = info, t.addrs.count == 1, let a = t.addrs.first, Dialable.digits(a).count >= 3 else { return nil }
        return Dialable.clean(a)
    }
}

private struct Bubble: View {
    let msg: SmsMessage
    let group: Bool
    let sims: [SimInfo]
    let error: String?
    let retry: () -> Void

    var body: some View {
        HStack {
            if msg.out { Spacer(minLength: 60) }
            VStack(alignment: msg.out ? .trailing : .leading, spacing: 3) {
                if group && !msg.out {
                    Text(msg.name ?? msg.addr).font(Typo.sans(10, .medium)).foregroundColor(Mono.muted).padding(.horizontal, 4)
                }
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(msg.att, id: \.id) { a in
                        Label(a.label, systemImage: a.mime.hasPrefix("image/") ? "photo" : "paperclip")
                            .font(Typo.sans(12, .medium))
                    }
                    if !msg.body.isEmpty {
                        Text(msg.body).font(Typo.sans(13)).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                    }
                }
                .foregroundColor(msg.out ? Mono.bg : Mono.text)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(msg.out ? Mono.accent.opacity(msg.status == SmsStatus.pending ? 0.6 : 1) : Mono.surfaceHigh)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .stroke(msg.status == SmsStatus.failed ? Mono.text : Color.clear, lineWidth: 1)
                )
                footer
            }
            if !msg.out { Spacer(minLength: 60) }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(msg.out ? "You" : (msg.name ?? msg.addr)): \(msg.body)")
    }

    @ViewBuilder private var footer: some View {
        HStack(spacing: 6) {
            if sims.count > 1, let sub = msg.sub, let sim = sims.first(where: { $0.id == sub }) {
                Text("SIM \(sim.slot + 1)").font(Typo.mono(9)).foregroundColor(Mono.muted)
            }
            switch msg.status {
            case SmsStatus.pending: Text("Sending…").font(Typo.sans(10)).foregroundColor(Mono.muted)
            case SmsStatus.failed:
                Text(error ?? "Not sent").font(Typo.sans(10)).foregroundColor(Mono.text)
                if msg.id.hasPrefix("pending-") {
                    Button("Retry", action: retry).buttonStyle(.plain).font(Typo.sans(10, .semibold)).foregroundColor(Mono.text)
                }
            default: Text(SmsTime.clock(msg.date)).font(Typo.mono(9)).foregroundColor(Mono.muted)
            }
        }
        .padding(.horizontal, 4)
    }
}

/// Text field + SIM picker + Send. `send` returns true when the text went out (the field clears).
private struct Composer: View {
    @EnvironmentObject var m: AppModel
    @Binding var draft: String
    var placeholder: String
    var send: () -> Bool
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            Rectangle().fill(Mono.line).frame(height: 1)
            HStack(alignment: .bottom, spacing: 8) {
                if m.sms.sims.count > 1 {
                    Menu {
                        Button("Phone's default SIM") { m.sms.sim = nil }
                        ForEach(m.sms.sims) { s in Button("SIM \(s.slot + 1) · \(s.name)") { m.sms.sim = s.id } }
                    } label: {
                        Text(simLabel).font(Typo.mono(10, .medium)).foregroundColor(Mono.text)
                    }
                    .menuStyle(.borderlessButton).fixedSize()
                    .padding(.horizontal, 8).frame(height: 30).tetherControl(Capsule())
                    .help("Send from")
                }
                TextField(placeholder, text: $draft, axis: .vertical)
                    .textFieldStyle(.plain).font(Typo.sans(13)).foregroundColor(Mono.text)
                    .lineLimit(1...6)
                    .focused($focused)
                    .onSubmit(submit)
                    .padding(.horizontal, 12).padding(.vertical, 7)
                    .tetherControl(RoundedRectangle(cornerRadius: 15), interactive: false)
                Button(action: submit) {
                    Image(systemName: "arrow.up").font(.system(size: 13, weight: .semibold))
                        .foregroundColor(Mono.bg).frame(width: 30, height: 30)
                        .background(Circle().fill(canSend ? Mono.accent : Mono.muted.opacity(0.5)))
                }
                .buttonStyle(PressStyle()).disabled(!canSend)
                .keyboardShortcut(.return, modifiers: .command)
                .accessibilityLabel("Send")
            }
            .padding(12)
            if let segments = SmsSegments.count(draft), segments > 1 {
                Text("\(segments) messages").font(Typo.mono(9)).foregroundColor(Mono.muted).padding(.bottom, 6)
            }
        }
        .onAppear { focused = true }
    }

    private var canSend: Bool { m.isConnected && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    private var simLabel: String {
        guard let id = m.sms.sim, let s = m.sms.sims.first(where: { $0.id == id }) else { return "Auto SIM" }
        return "SIM \(s.slot + 1)"
    }

    private func submit() {
        guard canSend else { return }
        if send() { draft = "" }
    }
}

private struct NewMessage: View {
    @EnvironmentObject var m: AppModel
    @Binding var to: String
    @State private var draft = ""
    var done: (Bool) -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Text("To:").font(Typo.sans(13)).foregroundColor(Mono.muted)
                TextField("Phone number (comma-separate for several)", text: $to)
                    .textFieldStyle(.plain).font(Typo.sans(13)).foregroundColor(Mono.text)
                Button("Cancel") { done(true) }.buttonStyle(.plain).font(Typo.sans(12)).foregroundColor(Mono.muted)
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
            Rectangle().fill(Mono.line).frame(height: 1)
            Spacer()
            Text("Replies show up in the conversation list once your phone records the message.")
                .font(Typo.sans(11)).foregroundColor(Mono.muted).padding()
            Composer(draft: $draft, placeholder: "Text message") {
                let recipients = to.split(whereSeparator: { $0 == "," || $0 == ";" }).map(String.init)
                guard m.sendSms(to: recipients, body: draft, thread: nil) else { return false }
                to = ""
                done(true)
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) { m.refreshThreads() }
                return true
            }
        }
    }
}

// MARK: - Helpers

enum SmsTime {
    private static let clockF: DateFormatter = {
        let f = DateFormatter()
        f.timeStyle = .short
        return f
    }()
    private static let dayF: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.doesRelativeDateFormatting = true
        return f
    }()
    private static let shortF: DateFormatter = {
        let f = DateFormatter()
        f.setLocalizedDateFormatFromTemplate("d MMM")
        return f
    }()

    static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: TimeInterval(ms) / 1000) }
    static func clock(_ ms: Int64) -> String { clockF.string(from: date(ms)) }
    static func day(_ ms: Int64) -> String { dayF.string(from: date(ms)) }
    static func sameDay(_ a: Int64, _ b: Int64) -> Bool { Calendar.current.isDate(date(a), inSameDayAs: date(b)) }

    /// "14:05" today, "Yesterday", else "3 Oct".
    static func short(_ ms: Int64) -> String {
        let d = date(ms)
        if Calendar.current.isDateInToday(d) { return clockF.string(from: d) }
        if Calendar.current.isDateInYesterday(d) { return "Yesterday" }
        return shortF.string(from: d)
    }
}

/// Rough SMS segment count (GSM-7: 160 / 153 per part; anything else UCS-2: 70 / 67).
enum SmsSegments {
    static func count(_ text: String) -> Int? {
        guard !text.isEmpty else { return nil }
        let gsm = text.unicodeScalars.allSatisfy { $0.isASCII }
        let n = gsm ? text.unicodeScalars.count : text.utf16.count
        let single = gsm ? 160 : 70
        let multi = gsm ? 153 : 67
        return n <= single ? 1 : (n + multi - 1) / multi
    }
}
