import AppKit
import Foundation
import TetherCore

/// Calling on the Mac (0.4.0): what the phone has told us (never stored on disk except the
/// "seen until" mark for the missed-call badge).
struct CallsModel: Equatable {
    var log: [CallLogEntry] = []
    var more = false
    var loading = false
    /// "calllog" (permission), "disabled", or "error:<reason>" from the phone.
    var needs: String?
    /// "timeout" / "offline" from this Mac.
    var error: String?
    var request = 0
    /// Missed-call events (phone clock) since the last log refresh.
    var liveMissed: [Int64] = []
    /// Missed calls up to this time (phone clock) were seen on this Mac.
    var seenUntil: Int64 = Int64(UserDefaults.standard.double(forKey: CallsModel.seenKey))
    /// The search / dial field.
    var query = ""
    var contacts: [ContactInfo] = []
    var contactsNeeds: String?
    /// The dial request waiting for the phone, by ref.
    var dialing: String?
    /// One line under the dial field ("Calling Asha on your phone…", or why it failed).
    var dialNote: String?
    /// SIM chosen for calls (subscription id); nil = the phone's default.
    var sim: Int?
    var showPad = false

    static let seenKey = "callsSeenUntil"
    static let requestTimeout: TimeInterval = 15
    static let dialTimeout: TimeInterval = 20

    var badge: Int { MissedCalls.badge(log: log, liveMissed: liveMissed, seenUntil: seenUntil) }

    /// Recent calls matching the field (all of them when it's empty).
    var filteredLog: [CallLogEntry] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return log }
        return log.filter { CallSearch.matches(q, name: $0.name, numbers: [$0.number]) }
    }

    static func describeLog(_ e: String) -> String {
        switch e {
        case "calllog": return "Allow call log access for Tether on your phone."
        case "disabled": return "Recent calls are off in Tether on your phone."
        case "timeout": return "Your phone didn't answer in time."
        case "offline": return "Not connected to your phone."
        default: return e.hasPrefix("error:") ? "Your phone couldn't read its call log (\(e.dropFirst(6)))." : "Couldn't load calls (\(e))."
        }
    }

    static func describeDial(_ reason: String?) -> String {
        switch reason {
        case "needs_permission": return "Allow Phone calls for Tether on your phone (Settings › On your Mac › Calls)."
        case "disabled": return "Calls from the Mac are off in Tether on your phone."
        case "invalid_number": return "That isn't a number your phone can call."
        case "no_sim": return "That SIM isn't available on your phone."
        case "busy": return "Your phone is already on a call."
        case "emergency": return "Call emergency numbers from your phone itself."
        case "no_call": return "Your phone didn't start the call. Check it for a message."
        case "offline": return "Not connected to your phone."
        case "timeout": return "Your phone didn't answer in time."
        default: return "Your phone couldn't place the call."
        }
    }

    /// A sentence for a failed call control.
    static func describeAction(_ reason: String) -> String? {
        switch reason {
        case "no_call": return nil
        case "not_allowed": return "Allow call access for Tether on your phone."
        case "needs_dnd_access": return "Allow Do Not Disturb access for Tether on your phone to silence calls."
        case "unsupported": return "Your phone doesn't let other apps do that during a call."
        case "disabled": return "Calls are off in Tether on your phone."
        default: return "Your phone couldn't do that."
        }
    }
}

extension AppModel {
    /// Calling features are usable right now.
    var canDial: Bool { settings.callsEnabled && supports(Caps4.dial) }
    var missedBadge: Int { settings.callsEnabled && supports(Caps4.callLog) ? calls.badge : 0 }

    func linkChanged4(wasConnected: Bool) {
        if isConnected && !wasConnected {
            calls.error = nil
            if settings.callsEnabled && supports(Caps4.callLog) { refreshCallLog() }
        } else if !isConnected && wasConnected {
            liveCall = nil
            if calls.loading { failCallLog("offline") }
            if calls.dialing != nil {
                calls.dialing = nil
                calls.dialNote = CallsModel.describeDial("offline")
            }
        }
    }

    func settingsChanged4(_ old: MacSettings) {
        if !settings.callsEnabled && old.callsEnabled {
            if liveCall?.dir == CallDirs.outgoing { liveCall = nil }
            calls.dialNote = nil
        }
        if settings.callsEnabled && !old.callsEnabled && supports(Caps4.callLog) { refreshCallLog() }
    }

    // MARK: - History

    func refreshCallLog() {
        guard supports(Caps4.callLog) else { return }
        requestCallLog(CallLogReq(limit: 60))
    }

    func loadMoreCalls() {
        guard calls.more, !calls.loading, let last = calls.log.last else { return }
        requestCallLog(CallLogReq(limit: 60, before: last.date))
    }

    private func requestCallLog(_ r: CallLogReq) {
        calls.loading = true
        calls.error = nil
        calls.request += 1
        let n = calls.request
        engine?.send(.callLogReq(r)) { [weak self] in self?.failCallLog("offline") }
        DispatchQueue.main.asyncAfter(deadline: .now() + CallsModel.requestTimeout) { [weak self] in
            guard let self, self.calls.loading, self.calls.request == n else { return }
            self.failCallLog("timeout")
        }
    }

    func failCallLog(_ reason: String) {
        guard calls.loading else { return }
        calls.loading = false
        calls.error = reason
        calls.request += 1
        Log.warn("calls.log_failed", ["reason": reason])
    }

    func callLogUpdate(_ p: CallLogPayload) {
        calls.loading = false
        calls.request += 1
        calls.error = nil
        calls.needs = p.needs
        if p.before == nil {
            calls.log = p.calls
            // The refreshed log now holds the missed calls we were counting live.
            let newest = p.calls.map(\.date).max() ?? 0
            calls.liveMissed.removeAll { $0 <= newest }
        } else {
            let ids = Set(calls.log.map(\.id))
            calls.log += p.calls.filter { !ids.contains($0.id) }
        }
        calls.more = p.more
    }

    /// The user looked at the call list: clear the badge.
    func markCallsSeen() {
        let seen = MissedCalls.seen(log: calls.log, liveMissed: calls.liveMissed, previous: calls.seenUntil)
        guard seen != calls.seenUntil else { return }
        calls.seenUntil = seen
        if !demo { UserDefaults.standard.set(Double(seen), forKey: CallsModel.seenKey) }
    }

    // MARK: - Contacts

    /// The search / dial field changed: filter recents now, ask the phone's contacts shortly.
    func callQueryChanged(_ q: String) {
        calls.query = q
        contactSearchWork?.cancel()
        let t = q.trimmingCharacters(in: .whitespaces)
        guard t.count >= 2, supports(Caps4.dial), settings.callsEnabled else {
            calls.contacts = []
            calls.contactsNeeds = nil
            return
        }
        let w = DispatchWorkItem { [weak self] in self?.engine?.send(.contactSearch(ContactSearchPayload(query: t, limit: 12))) }
        contactSearchWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25, execute: w)
    }

    func contactsUpdate(_ p: ContactsPayload) {
        guard p.query == calls.query.trimmingCharacters(in: .whitespaces) else { return }
        calls.contacts = p.contacts
        calls.contactsNeeds = p.needs
    }

    // MARK: - Dialing

    /// Asks the phone to call [raw]. Returns false (with a note) when it can't.
    @discardableResult
    func dial(_ raw: String, name: String? = nil, sub: Int? = nil) -> Bool {
        guard let number = Dialable.clean(raw) else {
            calls.dialNote = CallsModel.describeDial("invalid_number")
            return false
        }
        guard isConnected else {
            calls.dialNote = CallsModel.describeDial("offline")
            return false
        }
        guard canDial else {
            calls.dialNote = settings.callsEnabled
                ? "Update Tether on your phone to call from the Mac." : "Calling is off in Settings › Calls."
            return false
        }
        let ref = UUID().uuidString
        calls.dialing = ref
        calls.dialNote = "Calling \(name ?? number) on your phone…"
        Log.info("calls.dial", ["sim": "\(sub ?? calls.sim ?? -1)"])
        engine?.send(.callDial(CallDialPayload(ref: ref, number: number, sub: sub ?? calls.sim))) { [weak self] in
            self?.callDialResult(CallDialResultPayload(ref: ref, ok: false, reason: "offline"))
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + CallsModel.dialTimeout) { [weak self] in
            guard let self, self.calls.dialing == ref else { return }
            self.callDialResult(CallDialResultPayload(ref: ref, ok: false, reason: "timeout"))
        }
        haptic()
        return true
    }

    func callDialResult(_ p: CallDialResultPayload) {
        guard calls.dialing == p.ref else { return }
        calls.dialing = nil
        if p.ok {
            calls.dialNote = p.via == "notification" ? "Tap the notification on your phone to start the call." : nil
        } else {
            calls.dialNote = CallsModel.describeDial(p.reason)
            Log.info("calls.dial_failed", ["reason": p.reason ?? "-"])
        }
    }

    /// A tel: / callto: link was opened (Tether is the handler).
    func openPhoneLink(_ url: URL) {
        guard let number = TelURL.number(from: url.absoluteString) else {
            toast("That link has no phone number")
            return
        }
        Log.info("calls.tel_link", ["mode": settings.telLinks.rawValue])
        calls.query = number
        calls.contacts = []
        panelTabRequest = .calls
        showPanel?()
        if settings.telLinks == .call && canDial {
            dial(number)
        } else if !canDial {
            calls.dialNote = isConnected ? "Update Tether on your phone to call from the Mac." : CallsModel.describeDial("offline")
        } else {
            calls.dialNote = nil
        }
    }

    // MARK: - Live call

    func callLiveUpdate(_ p: CallLivePayload) {
        defer { callMediaChanged(busy: call != nil || liveCall != nil) }
        phoneClockSkew = p.ts - Self.nowMs
        if let r = p.reason {
            if r == "unsupported" && lastCallControl == CallActions4.mute {
                // One clear line instead of two conflicting ones (0.5.1): the card hides Mute and says why.
                callMuteBlocked = true
                liveCall?.muted = false
                callNote = nil
            } else {
                callNote = CallsModel.describeAction(r)
            }
            Log.info("calls.action_failed", ["reason": r])
        }
        let wanted = p.dir == CallDirs.incoming ? settings.callAlerts : settings.callsEnabled || settings.callAlerts
        guard wanted else { return }
        if p.isUp {
            if liveCall?.id != p.id && p.reason == nil {
                callNote = nil
                callMuteBlocked = false
            }
            liveCall = p
            if callMuteBlocked { liveCall?.muted = false }
        } else if liveCall?.id == p.id || liveCall == nil {
            liveCall = nil
            callNote = nil
            callMuteBlocked = false
            // The phone writes the call log a moment after the call ends.
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                guard let self, self.settings.callsEnabled, self.supports(Caps4.callLog) else { return }
                self.refreshCallLog()
            }
        }
    }

    /// Answer from the panel, the banner or the notification (speaker per Settings).
    func answerCall(id: String) {
        guard supports(Caps4.callControl) else {
            callNote = "Update Tether on your phone to answer from the Mac."
            return
        }
        callNote = settings.speakerOnAnswer ? "Answering on speaker…" : "Answering…"
        let req = CallActionPayload(id: id, action: CallActions4.answer, speaker: settings.speakerOnAnswer)
        engine?.send(.callAction(req)) { [weak self] in
            self?.callNote = "Not connected"
        }
        haptic()
    }

    /// End / Speaker / Mute on the live call card.
    func callControl(_ action: String, on: Bool? = nil) {
        guard let c = liveCall, supports(Caps4.callControl) else { return }
        // Optimistic, corrected by the phone's next update (or its failure reason).
        switch action {
        case CallActions4.speaker: liveCall?.speaker = on ?? !c.speaker
        case CallActions4.mute: liveCall?.muted = on ?? !c.muted
        default: break
        }
        callNote = action == CallActions4.end ? "Ending…" : nil
        lastCallControl = action
        engine?.send(.callAction(CallActionPayload(id: c.id, action: action, on: on))) { [weak self] in self?.callNote = "Not connected" }
        haptic()
    }

    /// The phone reported a missed call (0.3 event): count it until the log catches up.
    func missedCall(at ts: Int64) {
        guard settings.callsEnabled else { return }
        calls.liveMissed.append(ts)
        if supports(Caps4.callLog) {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in self?.refreshCallLog() }
        }
    }

    // MARK: - Sensitive notifications

    func notifAccessUpdate(_ p: NotifAccessPayload) {
        let was = notifAccess
        notifAccess = p
        // The problem came back after being fixed: show the guide again.
        if p.redacting && was?.redacting != true && was?.granted == true { settings.codesGuideDismissed = false }
        if p.redacting != was?.redacting {
            Log.info("notify.codes_hidden", ["redacting": "\(p.redacting)", "granted": p.granted.map { "\($0)" } ?? "?"])
        }
    }

    var showCodesGuide: Bool { notifAccess?.redacting == true && !settings.codesGuideDismissed }

    func copyAdbCommand() {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(NotifAccessPayload.adbCommand(), forType: .string)
        toast("Command copied")
    }
}

extension AppModel {
    /// Tether opens tel: and callto: links (clicking a number in Safari, Mail, Contacts…).
    var isPhoneLinkHandler: Bool {
        guard let url = URL(string: "tel:1"), let app = NSWorkspace.shared.urlForApplication(toOpen: url) else { return false }
        return app.standardizedFileURL == Bundle.main.bundleURL.standardizedFileURL
    }

    func useTetherForPhoneLinks() {
        guard !demo else { return }
        let me = Bundle.main.bundleURL
        for scheme in ["tel", "callto"] {
            NSWorkspace.shared.setDefaultApplication(at: me, toOpenURLsWithScheme: scheme) { [weak self] err in
                DispatchQueue.main.async {
                    if let err {
                        Log.warn("calls.tel_handler_failed", ["scheme": scheme, "err": "\(err.localizedDescription)"])
                        if scheme == "tel" { self?.toast("macOS didn't switch phone links to Tether") }
                    } else if scheme == "tel" {
                        self?.toast("Phone links now open Tether")
                        self?.objectWillChange.send()
                    }
                }
            }
        }
    }
}
