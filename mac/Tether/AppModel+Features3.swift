import AppKit
import Foundation
import TetherCore

/// Messages on the Mac: what the phone has sent us so far (conversations are never stored on disk).
struct SmsModel: Equatable {
    var threads: [SmsThread] = []
    var moreThreads = false
    var loadingThreads = false
    /// The pending thread request is for an older page.
    var paging = false
    /// Missing permission on the phone ("sms") or "disabled", from the last thread list.
    var needs: String?
    /// Why the conversation list didn't load: "timeout", "offline", "decode" or "error:<reason>"
    /// from the phone. Shown with a Retry button instead of a spinner.
    var threadsError: String?
    /// Same for one conversation's messages, by thread.
    var threadErrors: [Int64: String] = [:]
    /// Bumped on every request so a late timeout can't clear a newer one.
    var threadsRequest = 0
    var threadRequest = 0
    var selected: Int64?
    var conversations: [Int64: [SmsMessage]] = [:]
    var moreMessages: [Int64: Bool] = [:]
    var loadingThread: Int64?
    var search = ""
    var searchResults: [SmsMessage]?
    var sims: [SimInfo] = []
    /// SIM chosen in the composer (subscription id); nil = the phone's default SMS SIM.
    var sim: Int?
    /// Messages being sent, by ref, shown in the conversation until the phone's copy arrives.
    var sending: [String: SmsMessage] = [:]
    var sendErrors: [String: String] = [:]

    /// Seconds before a list or conversation request gives up and offers Retry.
    static let requestTimeout: TimeInterval = 15

    /// A sentence for an error code from the phone or from the Mac's own timeout.
    static func describe(_ error: String) -> String {
        switch error {
        case "timeout": return "Your phone didn't answer in time."
        case "offline": return "Not connected to your phone."
        case "decode": return "Your phone sent something this Mac couldn't read. Update Tether on both devices."
        case "sms": return "Allow SMS for Tether on your phone."
        case "disabled": return "Texts are off in Tether on your phone."
        default:
            if error.hasPrefix("error:") {
                let why = error.dropFirst("error:".count)
                return why == "timeout" ? "Your phone took too long reading its messages."
                    : "Your phone couldn't read its messages (\(why))."
            }
            return "Couldn't load messages (\(error))."
        }
    }

    func messages(in thread: Int64) -> [SmsMessage] {
        let sent = conversations[thread] ?? []
        let pending = sending.values.filter { $0.thread == thread }.sorted { $0.date < $1.date }
        return sent + pending
    }

    /// Adds or updates messages (matched by id), keeping the conversation in date order.
    mutating func merge(_ msgs: [SmsMessage], into thread: Int64) {
        var list = conversations[thread] ?? []
        for m in msgs {
            if let i = list.firstIndex(where: { $0.id == m.id }) { list[i] = m } else { list.append(m) }
        }
        list.sort { $0.date == $1.date ? $0.id < $1.id : $0.date < $1.date }
        conversations[thread] = list
    }
}

struct ProximityStatus: Equatable {
    var state: ProximityMonitor.State = .unknown
    var smoothed: Double?
    var lastReading: Date?
    var lockedAt: Date?
}

/// 0.3.0 features: Messages, call alerts, now playing, proximity lock.
extension AppModel {
    func setUp3() {
        notifier.onSms = { [weak self] thread in
            DispatchQueue.main.async { self?.openMessages(thread: thread) }
        }
        proximityMonitor = ProximityMonitor(config: proximityConfig)
        updateMediaKeys()
    }

    func linkChanged3(wasConnected: Bool) {
        if isConnected && !wasConnected {
            sms.sending.removeAll()
            // Re-ask for anything that was open, loading or failed when the link dropped:
            // a request sent just before a reconnect is otherwise lost for good.
            let wanted = (windows?.isOpen(.messages) ?? false) || sms.loadingThreads || sms.threadsError != nil
            if supports(Caps3.sms) && settings.smsEnabled && wanted {
                refreshThreads()
                if let t = sms.selected, sms.loadingThread == t || sms.threadErrors[t] != nil { loadThread(t, before: nil) }
            }
            configureProximity()
        } else if !isConnected && wasConnected {
            if sms.loadingThreads { failThreads("offline") }
            if let t = sms.loadingThread { failThread(t, "offline") }
            if let c = call { notifier.removeCall(id: c.id) }
            call = nil
            nowPlaying = nil
            artwork = nil
            updateMediaKeys()
            proximityLinkLost()
        }
    }

    func settingsChanged3(_ old: MacSettings) {
        if old.proximityLock != settings.proximityLock || old.proximityDistance != settings.proximityDistance
            || old.proximityAwaySeconds != settings.proximityAwaySeconds {
            proximityMonitor.config = proximityConfig
            if !settings.proximityLock { proximityMonitor.reset() }
            configureProximity()
        }
        if old.mediaKeys != settings.mediaKeys || old.nowPlayingEnabled != settings.nowPlayingEnabled { updateMediaKeys() }
        if !settings.nowPlayingEnabled {
            nowPlaying = nil
            artwork = nil
        }
        if !settings.callAlerts, let c = call {
            notifier.removeCall(id: c.id)
            call = nil
        }
    }

    // MARK: - Messages

    func openMessages(thread: Int64? = nil) {
        open(.messages)
        if sms.threads.isEmpty || sms.needs != nil || sms.threadsError != nil { refreshThreads() }
        if let thread { selectThread(thread) }
    }

    func refreshThreads(query: String? = nil) {
        guard supports(Caps3.sms) else { return }
        sms.paging = false
        requestThreads(SmsThreadsReq(limit: 40, query: query))
    }

    func loadMoreThreads() {
        guard sms.moreThreads, !sms.loadingThreads, let last = sms.threads.last else { return }
        sms.paging = true
        requestThreads(SmsThreadsReq(limit: 40, before: last.date))
    }

    /// Sends a thread-list request and arms its timeout: the spinner never outlives 15 s.
    private func requestThreads(_ req: SmsThreadsReq) {
        sms.loadingThreads = true
        sms.threadsError = nil
        sms.threadsRequest += 1
        let n = sms.threadsRequest
        Log.info("sms.threads_request", ["paging": "\(req.before != nil)", "wifi": "\(onWifi)"])
        engine?.send(.smsThreadsReq(req)) { [weak self] in self?.failThreads("offline") }
        DispatchQueue.main.asyncAfter(deadline: .now() + SmsModel.requestTimeout) { [weak self] in
            guard let self, self.sms.loadingThreads, self.sms.threadsRequest == n else { return }
            self.failThreads("timeout")
        }
    }

    func failThreads(_ reason: String) {
        guard sms.loadingThreads || reason == "decode" else { return }
        sms.loadingThreads = false
        sms.paging = false
        sms.threadsError = reason
        sms.threadsRequest += 1
        Log.warn("sms.threads_failed", ["reason": reason, "wifi": "\(onWifi)"])
    }

    func smsThreads(_ p: SmsThreadsPayload) {
        sms.loadingThreads = false
        sms.threadsRequest += 1
        // "error:<reason>" means the phone's store failed; a permission or switch is a "needs".
        if let n = p.needs, n.hasPrefix("error:") {
            sms.threadsError = n
            sms.needs = nil
            Log.warn("sms.threads_failed", ["reason": n])
        } else {
            sms.threadsError = nil
            sms.needs = p.needs
        }
        guard p.query == nil else { return }
        // A first page replaces the list; an older page (paging) appends, skipping threads already shown.
        if sms.paging {
            let ids = Set(sms.threads.map(\.id))
            sms.threads += p.threads.filter { !ids.contains($0.id) }
        } else {
            sms.threads = p.threads
        }
        sms.paging = false
        sms.moreThreads = p.more
        if sms.selected == nil, let first = sms.threads.first { selectThread(first.id) }
    }

    func selectThread(_ id: Int64) {
        sms.selected = id
        if sms.conversations[id] == nil { loadThread(id, before: nil) }
    }

    func loadOlder(_ id: Int64) {
        guard sms.moreMessages[id] == true, sms.loadingThread == nil, let first = sms.conversations[id]?.first else { return }
        loadThread(id, before: first.date)
    }

    func loadThread(_ id: Int64, before: Int64?) {
        sms.loadingThread = id
        sms.threadErrors[id] = nil
        sms.threadRequest += 1
        let n = sms.threadRequest
        engine?.send(.smsThreadReq(SmsThreadReq(thread: id, limit: 40, before: before))) { [weak self] in self?.failThread(id, "offline") }
        DispatchQueue.main.asyncAfter(deadline: .now() + SmsModel.requestTimeout) { [weak self] in
            guard let self, self.sms.loadingThread == id, self.sms.threadRequest == n else { return }
            self.failThread(id, "timeout")
        }
    }

    func retryThread(_ id: Int64) { loadThread(id, before: nil) }

    func failThread(_ id: Int64, _ reason: String) {
        if sms.loadingThread == id { sms.loadingThread = nil }
        sms.threadErrors[id] = reason
        sms.threadRequest += 1
        Log.warn("sms.thread_failed", ["reason": reason])
    }

    /// A message from the phone this Mac couldn't decode: fail whatever it was likely answering.
    func undecodable(type: UInt8) {
        switch type {
        case 0x91: failThreads("decode")
        case 0x93: if let t = sms.loadingThread { failThread(t, "decode") }
        default: break
        }
    }

    func smsMessages(_ p: SmsMessagesPayload) {
        if let q = p.search {
            if q == sms.search.trimmingCharacters(in: .whitespaces) {
                sms.searchResults = p.msgs
                if let e = p.error { Log.warn("sms.search_failed", ["reason": e]) }
            }
            return
        }
        if sms.loadingThread == p.thread {
            sms.loadingThread = nil
            sms.threadRequest += 1
        }
        if let e = p.error {
            failThread(p.thread, e)
            return
        }
        sms.threadErrors[p.thread] = nil
        sms.merge(p.msgs, into: p.thread)
        sms.moreMessages[p.thread] = p.more
    }

    func searchSms(_ q: String) {
        sms.search = q
        let t = q.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else {
            sms.searchResults = nil
            return
        }
        engine?.send(.smsSearch(SmsSearchPayload(query: t, limit: 50)))
    }

    func smsNew(_ msg: SmsMessage) {
        // Our own send came back from the phone's provider: drop the placeholder.
        if msg.out, let ref = sms.sending.first(where: { $0.value.thread == msg.thread && $0.value.body == msg.body })?.key {
            sms.sending[ref] = nil
        }
        if sms.conversations[msg.thread] != nil { sms.merge([msg], into: msg.thread) }
        if let i = sms.threads.firstIndex(where: { $0.id == msg.thread }) {
            var t = sms.threads.remove(at: i)
            t.snippet = msg.body.isEmpty ? (msg.att.first?.label ?? "") : msg.body
            if msg.date >= t.date {
                t.date = msg.date
                t.outLast = msg.out
            }
            if !msg.out && !msg.read && sms.selected != msg.thread { t.unread += 1 }
            sms.threads.insert(t, at: 0)
        } else if supports(Caps3.sms) {
            sms.threads.insert(SmsThread(id: msg.thread, addrs: [msg.addr], names: [msg.name], snippet: msg.body, date: msg.date,
                                         unread: msg.out ? 0 : 1, outLast: msg.out), at: 0)
        }
        if !msg.out && msg.status == SmsStatus.received && settings.smsNotify && settings.smsEnabled {
            let title = msg.name ?? msg.addr
            notifier.postSms(id: msg.id, thread: msg.thread, title: title,
                             body: settings.notifications.privacyMode ? "New message" : msg.body,
                             sound: settings.notifications.sound)
        }
    }

    /// Sends a text from the composer. Returns false when there is nothing to send.
    @discardableResult
    func sendSms(to: [String], body: String, thread: Int64?) -> Bool {
        let text = body.trimmingCharacters(in: .whitespacesAndNewlines)
        let recipients = to.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        guard !text.isEmpty, !recipients.isEmpty, supports(Caps3.sms) else { return false }
        let ref = UUID().uuidString
        let now = Int64(Date().timeIntervalSince1970 * 1000) + phoneClockSkew
        sms.sending[ref] = SmsMessage(id: "pending-\(ref)", thread: thread ?? -1, addr: recipients[0], body: text, date: now,
                                      out: true, status: SmsStatus.pending, sub: sms.sim)
        engine?.send(.smsSend(SmsSendPayload(ref: ref, to: recipients, body: text, thread: thread, sub: sms.sim))) { [weak self] in
            self?.smsSendResult(SmsSendResultPayload(ref: ref, ok: false, reason: "offline"))
        }
        haptic()
        return true
    }

    func smsSendResult(_ r: SmsSendResultPayload) {
        if r.ok {
            // Keep the bubble as "sent" until the provider copy replaces it via SMS_NEW.
            sms.sending[r.ref]?.status = SmsStatus.sent
            let ref = r.ref
            DispatchQueue.main.asyncAfter(deadline: .now() + 20) { [weak self] in self?.sms.sending[ref] = nil }
            return
        }
        sms.sending[r.ref]?.status = SmsStatus.failed
        let why: String
        switch r.reason {
        case "needs_permission": why = "Allow SMS for Tether on your phone"
        case "no_service": why = "Your phone has no signal"
        case "radio_off": why = "Your phone is in airplane mode"
        case "no_sim": why = "That SIM isn't available"
        case "disabled": why = "Texts are off in Tether on your phone"
        case "offline": why = "Not connected"
        case "short_code": why = "Your carrier blocked sending to that short code"
        default: why = "Your phone couldn't send it"
        }
        sms.sendErrors[r.ref] = why
        toast(why)
        Log.info("sms.send_failed", ["reason": r.reason ?? "-"])
    }

    func retrySms(ref: String) {
        guard let m = sms.sending[ref] else { return }
        sms.sending[ref] = nil
        sms.sendErrors[ref] = nil
        sendSms(to: [m.addr], body: m.body, thread: m.thread >= 0 ? m.thread : nil)
    }

    // MARK: - Calls

    func callUpdate(_ c: CallStatePayload) {
        guard settings.callAlerts else { return }
        if let reason = c.reason {
            switch reason {
            case "not_allowed": callNote = "Allow Decline in Tether on your phone (call access)"
            case "needs_dnd_access": callNote = "Allow Do Not Disturb access for Tether on your phone to silence calls"
            case "no_call": callNote = nil
            default: callNote = "Your phone couldn't do that"
            }
            return
        }
        switch c.state {
        case CallStates.ringing:
            let first = call?.id != c.id
            call = c
            if first { callNote = nil }
            notifier.postCall(id: c.id, caller: c.caller, number: c.number)
            if first { Log.info("call.ringing", ["named": "\(c.name != nil)"]) }
        case CallStates.missed:
            notifier.removeCall(id: c.id)
            if call?.id == c.id { call = nil }
            notifier.postMissedCall(id: c.id, caller: c.caller, number: c.number)
        default:
            notifier.removeCall(id: c.id)
            if call?.id == c.id { call = nil }
        }
    }

    func callAction(id: String, action: String) {
        guard supports(Caps3.call) else { return }
        callNote = action == CallActions.silence ? "Silencing…" : "Declining…"
        engine?.send(.callAction(CallActionPayload(id: id, action: action))) { [weak self] in self?.callNote = "Not connected" }
        haptic()
    }

    // MARK: - Now playing

    func nowPlayingUpdate(_ np: NowPlayingPayload) {
        guard settings.nowPlayingEnabled else { return }
        phoneClockSkew = np.ts - Int64(Date().timeIntervalSince1970 * 1000)
        nowPlaying = np.active ? np : nil
        if let h = np.art { artwork = artCache[h] } else { artwork = nil }
        updateMediaKeys()
    }

    func nowPlayingArt(hash: String, data: Data) {
        guard let img = NSImage(data: data) else { return }
        artCache[hash] = img
        if artCache.count > 24 { artCache.remove(at: artCache.startIndex) }
        if nowPlaying?.art == hash { artwork = img }
        mediaKeys?.update(nowPlaying, artwork: artwork, skew: phoneClockSkew)
    }

    /// Position of the phone's track right now (extrapolated between updates).
    func nowPlayingPosition(at date: Date = Date()) -> Int64 {
        guard let np = nowPlaying else { return 0 }
        return np.position(at: Int64(date.timeIntervalSince1970 * 1000), skew: -phoneClockSkew)
    }

    func media(_ cmd: String, pos: Int64? = nil, vol: Float? = nil) {
        guard supports(Caps3.nowPlaying) else { return }
        // Optimistic UI so play/pause and seeking feel instant; the phone's update follows.
        if var np = nowPlaying {
            let now = Int64(Date().timeIntervalSince1970 * 1000) + phoneClockSkew
            switch cmd {
            case MediaCmds.toggle, MediaCmds.play, MediaCmds.pause:
                np.positionMs = nowPlayingPosition()
                np.ts = now
                np.playing = cmd == MediaCmds.toggle ? !np.playing : cmd == MediaCmds.play
            case MediaCmds.seek:
                np.positionMs = pos ?? np.positionMs
                np.ts = now
            case MediaCmds.volume: np.volume = vol
            default: break
            }
            nowPlaying = np
        }
        engine?.send(.mediaCmd(MediaCmdPayload(cmd: cmd, pos: pos, vol: vol)))
    }

    func updateMediaKeys() {
        let want = settings.mediaKeys && settings.nowPlayingEnabled && nowPlaying != nil && !demo
        if want && mediaKeys == nil {
            mediaKeys = MediaKeys { [weak self] cmd, pos in self?.media(cmd, pos: pos) }
        } else if !want, let k = mediaKeys {
            k.stop()
            mediaKeys = nil
        }
        mediaKeys?.update(nowPlaying, artwork: artwork, skew: phoneClockSkew)
    }

    // MARK: - Proximity

    var proximityConfig: ProximityConfig {
        ProximityConfig(distance: settings.proximityDistance, awaySeconds: max(5, settings.proximityAwaySeconds))
    }

    /// Ask the phone for signal readings while the feature is on (and stop when it's off).
    func configureProximity() {
        let on = settings.proximityLock && supports(Caps3.proximity) && !demo
        if isConnected && supports(Caps3.proximity) {
            engine?.send(.proximityCfg(ProximityCfgPayload(on: on, intervalMs: 2000)))
        }
        if on || (settings.proximityLock && proximityMonitor.state != .unknown) {
            if proximityTimer == nil {
                proximityTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.proximityTick() }
            }
        } else {
            proximityTimer?.invalidate()
            proximityTimer = nil
            if !settings.proximityLock { proximity = ProximityStatus() }
        }
    }

    func rssiReading(_ rssi: Int) {
        guard settings.proximityLock else { return }
        proximityMonitor.reading(rssi, now: Self.nowMs)
        proximity.lastReading = Date()
        publishProximity()
    }

    private func proximityLinkLost() {
        // Only an absence after we knew the phone was near counts; never lock on a cold start.
        guard settings.proximityLock, proximityMonitor.state != .unknown else { return }
        proximityMonitor.linkLost(now: Self.nowMs)
        publishProximity()
        configureProximity()
    }

    private func proximityTick() {
        guard settings.proximityLock else { return configureProximity() }
        // Readings stopped although the link is up (phone asleep?): treat as unknown, never as away.
        if isConnected, let last = proximity.lastReading, Date().timeIntervalSince(last) > 20 {
            proximityMonitor.reset()
            proximity.lastReading = nil
        }
        proximityMonitor.tick(now: Self.nowMs)
        publishProximity()
    }

    private func publishProximity() {
        proximity.state = proximityMonitor.state
        proximity.smoothed = proximityMonitor.smoothed
        // Checked only when about to lock (pgrep is a process launch).
        let sharing = proximityMonitor.state == .away && (screen.phase == .live || ScreenLocker.isScreenBeingShared())
        if lockPolicy.shouldLock(state: proximityMonitor.state, enabled: settings.proximityLock, screenSharing: sharing) {
            if ScreenLocker.isLocked() { return }
            Log.info("proximity.lock", ["rssi": proximityMonitor.smoothed.map { "\(Int($0))" } ?? "lost"])
            if ScreenLocker.lock() { proximity.lockedAt = Date() }
        }
    }

    static var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
