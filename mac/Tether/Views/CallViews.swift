import SwiftUI
import TetherCore

/// The call in progress (0.4.0): who, how long, and the controls this phone allows.
/// Controls the phone can't do are not shown at all.
struct LiveCallCard: View {
    @EnvironmentObject var m: AppModel
    let call: CallLivePayload

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Image(systemName: call.dir == CallDirs.outgoing ? "phone.arrow.up.right.fill" : "phone.fill")
                    .font(.system(size: 13)).foregroundColor(Mono.bg)
                    .frame(width: 34, height: 34).background(Circle().fill(Mono.accent))
                VStack(alignment: .leading, spacing: 2) {
                    Text(call.caller).font(Typo.sans(15, .semibold)).foregroundColor(Mono.text).lineLimit(1)
                    TimelineView(.periodic(from: .now, by: 1)) { ctx in
                        Text(status(at: ctx.date)).font(Typo.mono(11)).foregroundColor(Mono.muted).lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
            }
            HStack(spacing: 8) {
                if call.canSpeaker {
                    CallToggle(symbol: "speaker.wave.2.fill", title: "Speaker", on: call.speaker) {
                        m.callControl(CallActions4.speaker, on: !call.speaker)
                    }
                }
                if call.canMute {
                    CallToggle(symbol: "mic.slash.fill", title: "Mute", on: call.muted) {
                        m.callControl(CallActions4.mute, on: !call.muted)
                    }
                }
                if call.canHold {
                    CallToggle(symbol: "pause.fill", title: "Hold", on: call.held) { m.callControl(CallActions4.hold, on: !call.held) }
                }
                if call.canEnd {
                    MonoButton(title: "End") { m.callControl(CallActions4.end) }
                        .accessibilityLabel("End call")
                }
            }
            Text("Call audio is on your phone or its earbuds.").font(Typo.sans(10)).foregroundColor(Mono.muted)
            if let note = m.callNote {
                Text(note).font(Typo.sans(11)).foregroundColor(Mono.muted).fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(14)
        .tetherCard(highlighted: true)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Call with \(call.caller)")
    }

    private func status(at date: Date) -> String {
        let phoneNow = Int64(date.timeIntervalSince1970 * 1000) + m.phoneClockSkew
        let t = CallClock.elapsed(since: call.since, phoneNow: phoneNow)
        let sim = call.sub.flatMap { s in m.sms.sims.first { $0.id == s }?.name }
        let what = call.state == CallLiveStates.dialing ? "Calling" : "On call"
        return [what, t, sim].compactMap { $0?.isEmpty == false ? $0 : nil }.joined(separator: " · ")
    }
}

private struct CallToggle: View {
    let symbol: String
    let title: String
    let on: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: symbol).font(.system(size: 13))
                Text(title).font(Typo.sans(10, .medium))
            }
            .foregroundColor(on ? Mono.bg : Mono.text)
            .frame(width: 58, height: 44)
            .background(RoundedRectangle(cornerRadius: 9).fill(on ? Mono.accent : Color.clear))
            .tetherControl(RoundedRectangle(cornerRadius: 9))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
        .accessibilityLabel(title)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// The Calls tab in the panel: search contacts or type a number, dial pad, SIM choice,
/// contact matches, and recent calls with call back.
struct CallsTab: View {
    @EnvironmentObject var m: AppModel
    @FocusState private var focused: Bool

    private var query: Binding<String> {
        Binding(get: { m.calls.query }, set: { m.callQueryChanged($0) })
    }

    private var typedNumber: String? {
        let q = m.calls.query
        // Only treat the field as a number when it has no letters (a name search isn't dialable).
        guard !q.contains(where: { $0.isLetter && !"pPwW".contains($0) }) else { return nil }
        return Dialable.clean(q)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if !m.supports(Caps4.dial) && !m.supports(Caps4.callLog) {
                notice(m.isConnected ? "Update Tether on your phone to 0.4 to call from this Mac." : "Connect your phone to make calls.")
            } else if !m.settings.callsEnabled {
                notice("Calling is off. Turn it on in Settings › Calls.")
            } else {
                dialRow
                if m.sms.sims.count > 1 { simPicker }
                if m.calls.showPad { DialPad { m.callQueryChanged(m.calls.query + $0) }.transition(.opacity) }
                if let note = m.calls.dialNote {
                    Text(note).font(Typo.sans(11)).foregroundColor(Mono.text).fixedSize(horizontal: false, vertical: true)
                }
                contactMatches
                recents
            }
        }
        .padding(16)
        .onAppear { m.markCallsSeen() }
        .onChange(of: m.calls.log.first?.id) { _ in m.markCallsSeen() }
    }

    private func notice(_ text: String) -> some View {
        VStack(spacing: 10) {
            Image(systemName: "phone").font(.system(size: 20, weight: .light)).foregroundColor(Mono.muted)
            Text(text).font(Typo.sans(12)).foregroundColor(Mono.muted).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity).padding(.vertical, 50)
    }

    private var dialRow: some View {
        HStack(spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").font(.system(size: 11)).foregroundColor(Mono.muted)
                TextField("Name or number", text: query)
                    .textFieldStyle(.plain).font(Typo.sans(13)).foregroundColor(Mono.text)
                    .focused($focused)
                    .onSubmit(callTopResult)
                if !m.calls.query.isEmpty {
                    Button { m.callQueryChanged("") } label: {
                        Image(systemName: "xmark.circle.fill").font(.system(size: 11)).foregroundColor(Mono.muted)
                    }
                    .buttonStyle(.plain).accessibilityLabel("Clear")
                }
            }
            .padding(.horizontal, 10).frame(height: 30)
            .tetherControl(RoundedRectangle(cornerRadius: 9))
            iconButton(m.calls.showPad ? "circle.grid.3x3.fill" : "circle.grid.3x3", "Dial pad") {
                withAnimation(Motion.spring(false)) { m.calls.showPad.toggle() }
            }
            iconButton("phone.fill", typedNumber.map { "Call \($0)" } ?? "Call", filled: typedNumber != nil) { callTopResult() }
                .disabled(typedNumber == nil || m.calls.dialing != nil)
        }
    }

    private var simPicker: some View {
        HStack(spacing: 8) {
            Text("Call from").font(Typo.sans(11)).foregroundColor(Mono.muted)
            MonoSegmented(options: [nil] + m.sms.sims.map { Optional($0.id) }, selection: $m.calls.sim) { id in
                id.flatMap { i in m.sms.sims.first { $0.id == i }?.name } ?? "Default"
            }
        }
    }

    @ViewBuilder private var contactMatches: some View {
        if !m.calls.query.trimmingCharacters(in: .whitespaces).isEmpty {
            if m.calls.contactsNeeds == "contacts" {
                Text("Allow Contacts for Tether on your phone to search names.").font(Typo.sans(11)).foregroundColor(Mono.muted)
            }
            ForEach(m.calls.contacts, id: \.self) { c in
                ForEach(c.numbers, id: \.self) { n in
                    CallRow(symbol: "person.crop.circle", title: c.name, detail: [n.label, n.n].compactMap { $0 }.joined(separator: " · "),
                            strong: false) { m.dial(n.n, name: c.name) }
                }
            }
        }
    }

    @ViewBuilder private var recents: some View {
        HStack {
            SectionLabel(text: "Recent")
            Spacer()
            if m.calls.loading { ProgressView().controlSize(.small) }
        }
        if let e = m.calls.needs ?? m.calls.error {
            HStack {
                Text(CallsModel.describeLog(e)).font(Typo.sans(11)).foregroundColor(Mono.muted).fixedSize(horizontal: false, vertical: true)
                Spacer()
                GhostButton(title: "Retry") { m.refreshCallLog() }
            }
        }
        let list = m.calls.filteredLog
        if list.isEmpty && !m.calls.loading && m.calls.needs == nil && m.calls.error == nil {
            Text(m.calls.query.isEmpty ? "No calls yet." : "No recent calls match.").font(Typo.sans(11)).foregroundColor(Mono.muted)
        }
        ForEach(list) { e in
            CallRow(symbol: Self.symbol(e.type), title: e.caller, detail: detail(e), strong: e.isMissed) {
                m.dial(e.number, name: e.name, sub: e.sub)
            }
            .disabled(e.number.isEmpty)
        }
        if m.calls.more && m.calls.query.isEmpty {
            GhostButton(title: "Older calls") { m.loadMoreCalls() }.frame(maxWidth: .infinity)
        }
    }

    private func detail(_ e: CallLogEntry) -> String {
        let kind: String
        switch e.type {
        case CallLogTypes.missed: kind = "Missed"
        case CallLogTypes.outgoing: kind = "Outgoing"
        case CallLogTypes.incoming: kind = "Incoming"
        case CallLogTypes.rejected: kind = "Declined"
        case CallLogTypes.blocked: kind = "Blocked"
        case CallLogTypes.voicemail: kind = "Voicemail"
        default: kind = "Call"
        }
        let parts = [kind, e.name != nil ? e.number : nil, CallClock.duration(e.dur), relativeTime(e.date)]
        return parts.compactMap { $0?.isEmpty == false ? $0 : nil }.joined(separator: " · ")
    }

    static func symbol(_ type: String) -> String {
        switch type {
        case CallLogTypes.missed, CallLogTypes.rejected: return "phone.down"
        case CallLogTypes.outgoing: return "phone.arrow.up.right"
        case CallLogTypes.blocked: return "nosign"
        case CallLogTypes.voicemail: return "recordingtape"
        default: return "phone.arrow.down.left"
        }
    }

    private func callTopResult() {
        if let n = typedNumber {
            m.dial(n)
        } else if let c = m.calls.contacts.first, let n = c.numbers.first {
            m.dial(n.n, name: c.name)
        } else if let e = m.calls.filteredLog.first, !m.calls.query.isEmpty {
            m.dial(e.number, name: e.name, sub: e.sub)
        }
    }

    private func iconButton(_ symbol: String, _ label: String, filled: Bool = false, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 12))
                .foregroundColor(filled ? Mono.bg : Mono.text)
                .frame(width: 30, height: 30)
                .background(RoundedRectangle(cornerRadius: 9).fill(filled ? Mono.accent : Color.clear))
                .tetherControl(RoundedRectangle(cornerRadius: 9))
                .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle()).help(label).accessibilityLabel(label)
    }
}

/// One contact number or recent call, with a call button.
private struct CallRow: View {
    let symbol: String
    let title: String
    let detail: String
    let strong: Bool
    let call: () -> Void
    @State private var hover = false
    @Environment(\.isEnabled) private var enabled

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: symbol).font(.system(size: 12)).foregroundColor(strong ? Mono.text : Mono.muted).frame(width: 18)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(Typo.sans(13, strong ? .semibold : .regular)).foregroundColor(Mono.text).lineLimit(1)
                Text(detail).font(Typo.sans(10)).foregroundColor(Mono.muted).lineLimit(1)
            }
            Spacer(minLength: 6)
            Button(action: call) {
                Image(systemName: "phone.fill").font(.system(size: 11)).foregroundColor(Mono.text)
                    .frame(width: 26, height: 26).tetherControl(Circle()).contentShape(Circle())
            }
            .buttonStyle(PressStyle()).opacity(hover || !enabled ? 1 : 0.75)
            .help("Call \(title)").accessibilityLabel("Call \(title)")
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .accessibilityElement(children: .combine)
    }
}

/// 3×4 keypad; long-press 0 for +.
struct DialPad: View {
    let tap: (String) -> Void
    private let keys = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["*", "0", "#"]]
    private let letters = ["2": "ABC", "3": "DEF", "4": "GHI", "5": "JKL", "6": "MNO", "7": "PQRS", "8": "TUV", "9": "WXYZ", "0": "+"]

    var body: some View {
        VStack(spacing: 6) {
            ForEach(keys, id: \.self) { row in
                HStack(spacing: 6) {
                    ForEach(row, id: \.self) { k in
                        Button { tap(k) } label: {
                            VStack(spacing: 0) {
                                Text(k).font(Typo.sans(16, .medium)).foregroundColor(Mono.text)
                                Text(letters[k] ?? " ").font(Typo.mono(7)).foregroundColor(Mono.muted)
                            }
                            .frame(maxWidth: .infinity, minHeight: 40)
                            .tetherControl(RoundedRectangle(cornerRadius: 9))
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(PressStyle())
                        .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in if k == "0" { tap("+") } })
                        .accessibilityLabel(k)
                    }
                }
            }
        }
        .tetherGlassGroup(spacing: 6)
    }
}

/// Android 15+ hides one-time codes from Tether: the one-time fix, in the panel.
struct CodesBanner: View {
    @EnvironmentObject var m: AppModel
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "eye.slash").font(.system(size: 13)).foregroundColor(Mono.text)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Your phone hides one-time codes").font(Typo.sans(12, .medium)).foregroundColor(Mono.text)
                    Text("Android 15+ shows \"Sensitive notification content hidden\" to Tether. A one-time command fixes it.")
                        .font(Typo.sans(11)).foregroundColor(Mono.muted).fixedSize(horizontal: false, vertical: true)
                }
            }
            if open { CodesSteps(oem: m.notifAccess?.oem ?? "") }
            HStack(spacing: 8) {
                GhostButton(title: open ? "Hide steps" : "Show steps") { withAnimation(Motion.spring(false)) { open.toggle() } }
                if open { GhostButton(title: "Copy command") { m.copyAdbCommand() } }
                Spacer()
                Button("Not now") { m.settings.codesGuideDismissed = true }
                    .buttonStyle(.plain).font(Typo.sans(11)).foregroundColor(Mono.muted)
            }
        }
        .padding(12)
        .tetherCard()
        .padding(.horizontal, 16).padding(.bottom, 10)
        .transition(.opacity)
    }
}

/// The adb steps (same as the phone's guide).
struct CodesSteps: View {
    let oem: String

    static func steps(oem: String) -> [String] {
        var s = [
            "On the phone: Settings › About phone › tap Build number 7 times to turn on Developer options "
                + "(OnePlus: About device › Version).",
            "Developer options › Wireless debugging: on, with the phone and this Mac on the same Wi-Fi.",
            "On this Mac: brew install android-platform-tools. Then on the phone tap Pair device with pairing code and run "
                + "adb pair <ip:port>, enter the code, and adb connect <ip:port> (the address on the Wireless debugging screen).",
            "Run the command below.",
        ]
        let o = oem.lowercased()
        if o.contains("oneplus") || o.contains("oppo") || o.contains("realme") {
            s.append("OnePlus, OPPO, realme: also turn on Developer options › Disable permission monitoring, or the phone undoes it.")
        }
        s.append("Restart the phone. Tether shows \"Codes are shown\" in Settings › Notifications once Android allows it.")
        return s
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(Self.steps(oem: oem).enumerated()), id: \.offset) { i, s in
                Text("\(i + 1). \(s)").font(Typo.sans(11)).foregroundColor(Mono.text).fixedSize(horizontal: false, vertical: true)
            }
            Text(NotifAccessPayload.adbCommand())
                .font(Typo.mono(10)).foregroundColor(Mono.text).textSelection(.enabled)
                .padding(8).frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 8).fill(Mono.surfaceHigh))
        }
    }
}
