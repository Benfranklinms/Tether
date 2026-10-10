import AppKit
import CryptoKit
import Foundation
import TetherCore

/// Owns the protocol and the radios on one serial queue. Everything the UI needs is pushed
/// to `AppModel` on the main thread; UI intents come back through the methods here.
final class Engine: ControllerListener, PeripheralDelegate, WifiClientDelegate {
    let queue = DispatchQueue(label: "tether.engine", qos: .userInitiated)
    private weak var model: AppModel?
    private let identity: FileIdentityStore
    private let files: MacFiles
    private var controller: TetherController!
    private var peripheral: Peripheral!
    private var wifi: WifiClient!
    private var ticker: DispatchSourceTimer?
    private var tickPeriod = 0
    private var pairingToken: Data?
    private var settings: MacSettings
    private let transfersFile = JSONFile<[TransferRecord]>("transfers.json")
    private var transfers: [TransferRecord] = []
    private var lastTransferWrite = Date.distantPast
    private var wakeObserver: NSObjectProtocol?
    private var screenSink: ScreenSink?

    init(model: AppModel, settings: MacSettings) {
        self.model = model
        self.settings = settings
        identity = FileIdentityStore()
        let downloads = settings.downloadURL
        let media = settings.mediaURL
        files = MacFiles(downloadFolder: { downloads })
        files.mediaFolder = { media }
        transfers = transfersFile.read() ?? []
    }

    var myFingerprint: String { fingerprint(identity.localKey.publicKey) }
    var peer: PeerRecord? { identity.peer }
    var savedTransfers: [TransferRecord] { transfers }

    func start() {
        queue.async { [self] in
            controller = TetherController(
                role: .responder, identity: identity, localHello: { Hello(name: Host.current().localizedName ?? "Mac",
                                                                      platform: "macos", app: Engine.version, caps: Caps.current) },
                outgoing: files, incoming: files, listener: self
            )
            controller.restoreTransfers(transfers)
            files.sweep(live: Set(transfers.map(\.id)))
            peripheral = Peripheral(queue: queue, delegate: self)
            wifi = WifiClient(queue: queue, delegate: self)
            updateAdvertising()
            Log.info("engine.start", ["version": Engine.version, "macOS": ProcessInfo.processInfo.operatingSystemVersionString])
        }
        // Advertising can silently stop across sleep; start it again on wake.
        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: nil
        ) { [weak self] _ in
            guard let self else { return }
            self.queue.async {
                Log.info("engine.wake")
                self.peripheral?.refreshAdvertising()
            }
        }
    }

    static var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev" }

    // MARK: intents

    func apply(_ s: MacSettings) {
        queue.async { [self] in
            settings = s
            let downloads = s.downloadURL
            let media = s.mediaURL
            files.downloadFolder = { downloads }
            files.mediaFolder = { media }
            if !s.wifiFastPath { wifi?.revoke(); controller?.wifiDisconnected() }
        }
    }

    /// Open pairing mode; returns the QR contents via `done` on the main thread.
    func startPairing(_ done: @escaping (String) -> Void) {
        queue.async { [self] in
            var token = Data(count: PairingURI.tokenLength)
            for i in 0..<token.count { token[i] = UInt8.random(in: 0...255) }
            pairingToken = token
            controller.setPairing(PairingContext(acceptNew: true, token: token))
            updateAdvertising()
            peripheral.refreshAdvertising()
            let uri = PairingURI(key: identity.localKey.publicKey, token: token, name: Host.current().localizedName ?? "Mac").encode()
            Log.info("pair.open")
            DispatchQueue.main.async { done(uri) }
        }
    }

    func stopPairing() {
        queue.async { [self] in
            pairingToken = nil
            controller.setPairing(.closed)
            if case .pairing = controller.state { controller.rejectPairing() }
            updateAdvertising()
            Log.info("pair.close")
        }
    }

    func confirmPairing() { queue.async { self.controller.confirmPairing() } }
    func rejectPairing() { queue.async { self.controller.rejectPairing() } }

    func sendClip(_ text: String, manual: Bool) {
        queue.async { [self] in
            let ok = controller.send(.clip(id: UUID().uuidString, ts: Self.now(), text: text))
            if manual {
                let msg = ok ? "Sent to your phone" : (controller.isConnected ? "Too large for Bluetooth" : "Not connected")
                DispatchQueue.main.async { self.model?.toast(msg) }
            }
            Log.info("clip.sent", ["ok": "\(ok)", "bytes": "\(text.utf8.count)"])
        }
    }

    func sendFiles(_ urls: [URL]) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            for u in urls {
                do {
                    let rec = try MacFiles.stage(u, now: Self.now())
                    queue.async { self.controller.sendFile(rec) }
                } catch {
                    Log.error("file.stage_failed", error)
                    DispatchQueue.main.async { self.model?.toast("Couldn't read \(u.lastPathComponent)") }
                }
            }
        }
    }

    func cancel(_ id: String) { queue.async { self.controller.cancelTransfer(id) } }
    func retry(_ id: String) { queue.async { self.controller.retryTransfer(id) } }
    func remove(_ id: String) { queue.async { self.controller.removeTransfer(id) } }

    // MARK: 0.2.0 features

    /// Fire a notification button on the phone (reply text for an inline-reply button).
    func notifAction(key: String, action: String, text: String?) {
        queue.async { [self] in
            let ok = controller.send(.notifAction(NotifActionPayload(key: key, action: action, text: text)))
            Log.info("notify.action_sent", ["ok": "\(ok)", "reply": "\(text != nil)"])
            if !ok { DispatchQueue.main.async { self.model?.actionResult(key: key, ok: false, reason: "offline") } }
        }
    }

    /// Fire-and-forget send for 0.3.0 requests; `failed` runs on the main queue when offline.
    func send(_ message: Message, failed: (() -> Void)? = nil) {
        queue.async { [self] in
            let ok = controller.send(message)
            if !ok { Log.info("send.offline", ["type": "\(message.type)"]) }
            if !ok, let failed { DispatchQueue.main.async(execute: failed) }
        }
    }

    func ring(_ on: Bool) {
        queue.async { [self] in
            let ok = controller.send(on ? .ring(timeoutS: 60) : .ringStop(reason: "user"))
            Log.info(on ? "ring.request" : "ring.stop_request", ["ok": "\(ok)"])
            if !ok { DispatchQueue.main.async { self.model?.toast("Not connected") } }
        }
    }

    /// Ask the phone to share its screen; frames arrive on Wi-Fi only.
    func startScreen(maxSize: Int) {
        queue.async { [self] in
            screenSink?.reset()
            let ok = controller.send(.screenStart(ScreenStartPayload(maxSize: maxSize, fps: 60, bitrate: 8_000_000)))
            Log.info("screen.request", ["ok": "\(ok)", "wifi": "\(controller.wifiReady)"])
            if !ok { DispatchQueue.main.async { self.model?.screenUpdate(ScreenStatePayload(state: "ended", reason: "Not connected")) } }
        }
    }

    func stopScreen() {
        queue.async { [self] in
            _ = controller.send(.screenStop(reason: "user"))
            Log.info("screen.stop_request")
        }
    }

    func screenInput(_ input: ScreenInputPayload) {
        queue.async { [self] in _ = controller.send(.screenInput(input)) }
    }

    /// Wire the video sink: frames go straight to it (never via the main thread).
    func attach(sink: ScreenSink) {
        queue.async { [self] in
            screenSink = sink
            sink.onFeedback = { [weak self] fb in
                self?.queue.async { _ = self?.controller.send(.videoFeedback(fb)) }
            }
        }
    }

    func dismissOnPhone(_ key: String) {
        queue.async { [self] in
            if settings.notifications.dismissSync { controller.send(.notifDismiss(key: key)) }
        }
    }

    func unpair() {
        queue.async { [self] in
            controller.unpair()
            afterUnpair()
        }
    }

    func rotateKey() {
        queue.async { [self] in
            let ok = controller.rotateKey()
            DispatchQueue.main.async { self.model?.toast(ok ? "Rotating keys…" : "Connect your phone first") }
        }
    }

    // MARK: ControllerListener

    func controllerState(_ state: LinkState) {
        Log.info("link.state", ["state": Self.describe(state), "retries": "\(controller?.bleHandshakeRetries ?? 0)"])
        retick(state)
        let peer = identity.peer
        if case .connected = state {
            wifi.browse(phoneId: peer.map { Self.wifiId($0.keyBytes) })
        }
        let caps = controller?.peerCaps ?? []
        let app = controller?.peerApp
        let wifiUp = controller?.wifiReady ?? false
        if case .connected = state { Log.info("peer.caps", ["caps": caps.sorted().joined(separator: ","), "app": app ?? "-"]) }
        DispatchQueue.main.async { self.model?.apply(link: state, peer: peer, caps: caps, peerApp: app, wifi: wifiUp) }
    }

    func controllerMessage(_ message: Message) {
        if case .videoFrame(let f) = message {
            screenSink?.receive(f)
            return
        }
        let s = settings
        DispatchQueue.main.async {
            guard let m = self.model else { return }
            switch message {
            case .clip(_, _, let text): if s.receiveClipboard { m.receiveClip(text) }
            case .notif(let n): m.receiveNotification(n)
            case .notifRemoved(let key): m.notificationRemoved(key)
            case .notifActionResult(let r): m.actionResult(key: r.key, ok: r.ok, reason: r.reason)
            case .battery(let b): m.batteryUpdate(b)
            case .ringState(let on, let reason): m.ringUpdate(on, reason: reason)
            case .screenState(let st): m.screenUpdate(st)
            case .screenStop(let reason): m.screenUpdate(ScreenStatePayload(state: ScreenStates.stopped, reason: reason))
            case .smsThreads(let p): m.smsThreads(p)
            case .smsMessages(let p): m.smsMessages(p)
            case .smsNew(let msg): m.smsNew(msg)
            case .smsSendResult(let r): m.smsSendResult(r)
            case .sims(let sims): m.sms.sims = sims
            case .callState(let c): m.callUpdate(c)
            case .nowPlaying(let np): m.nowPlayingUpdate(np)
            case .nowPlayingArt(let hash, let data): m.nowPlayingArt(hash: hash, data: data)
            case .rssi(let rssi, _): m.rssiReading(rssi)
            default: Log.info("msg.ignored", ["type": "\(message.type)"])
            }
        }
    }

    func controllerUndecodable(type: UInt8, reason: String) {
        Log.error("msg.decode_failed", nil, ["type": "\(type)", "reason": String(reason.prefix(240))])
        DispatchQueue.main.async { self.model?.undecodable(type: type) }
    }

    func controllerTransfer(_ record: TransferRecord, important: Bool) {
        if let i = transfers.firstIndex(where: { $0.id == record.id }) { transfers[i] = record } else { transfers.insert(record, at: 0) }
        if transfers.count > 200 { transfers.removeLast(transfers.count - 200) }
        if important || Date().timeIntervalSince(lastTransferWrite) > 2 {
            lastTransferWrite = Date()
            transfersFile.write(transfers)
        }
        let snapshot = transfers
        let done = important && record.direction == .IN && record.status == .DONE
        let isMedia = record.kind != nil
        let notify = done && (isMedia ? settings.mediaNotify : settings.notifyOnReceive)
        if done && isMedia { Log.info("media.received", ["kind": record.kind ?? "-", "size": "\(record.size)"]) }
        DispatchQueue.main.async {
            guard let m = self.model else { return }
            m.transfers = snapshot
            if notify { if isMedia { m.mediaReceived(record) } else { m.fileReceived(record) } }
        }
    }

    func controllerTransferRemoved(_ id: String) {
        transfers.removeAll { $0.id == id }
        transfersFile.write(transfers)
        let snapshot = transfers
        DispatchQueue.main.async { self.model?.transfers = snapshot }
    }

    func controllerPaired(_ peer: PeerRecord) {
        pairingToken = nil
        controller.setPairing(.closed)
        updateAdvertising()
        Log.info("pair.done", ["peer": fingerprint(peer.keyBytes)])
        DispatchQueue.main.async { self.model?.paired(peer) }
    }

    func controllerUnpaired(byRemote: Bool) {
        if byRemote { afterUnpair() }
    }

    private func afterUnpair() {
        wifi.revoke()
        wifi.stopBrowsing()
        updateAdvertising()
        DispatchQueue.main.async { self.model?.unpaired() }
    }

    func controllerClosed(reason: CloseReason, byRemote: Bool, wifi isWifi: Bool) {
        Log.info("link.closed", ["reason": "\(reason)", "remote": "\(byRemote)", "wifi": "\(isWifi)"])
        if isWifi {
            wifi.close()
            return
        }
        let msg: String?
        switch reason {
        case .untrusted: msg = identity.peer == nil ? nil : "A phone that isn't paired tried to connect"
        case .pairingRejected: msg = "Pairing was declined"
        case .versionMismatch: msg = "Update Tether on both devices"
        case .timeout: msg = "Your phone stopped responding"
        default: msg = nil
        }
        if let msg { DispatchQueue.main.async { self.model?.lastError = msg } }
    }

    func controllerWifiOffer(_ offer: WifiOfferPayload) {
        guard settings.wifiFastPath, identity.peer != nil else { return }
        Log.info("wifi.offer", ["addrs": "\(offer.addrs.count)"])
        wifi.connect(offer)
    }

    func controllerWifiRevoked() {
        wifi.revoke()
        controller.wifiDisconnected()
    }

    func controllerKeyRotated() {
        peripheral.dropCentral()
        DispatchQueue.main.async {
            self.model?.toast("New keys in use")
            self.model?.myFingerprint = self.myFingerprint
        }
    }

    // MARK: PeripheralDelegate

    func linkUp(maxFragment: Int) {
        let ctx = pairingToken.map { PairingContext(acceptNew: true, token: $0) } ?? .closed
        controller.bleConnected(maxFragment: maxFragment, writer: { [weak self] f in self?.peripheral.send(f) }, pairing: ctx)
    }

    func fragment(_ data: Data) { controller.bleFragment(data) }

    func linkDown() { controller.bleDisconnected() }

    func bluetooth(_ status: BluetoothStatus) {
        DispatchQueue.main.async { self.model?.bluetooth = status }
        if status == .on { updateAdvertising() }
    }

    func radio(_ diag: RadioDiag) {
        Log.info("ble.radio", ["diag": diag.description])
        DispatchQueue.main.async { self.model?.radio = diag }
    }

    // MARK: WifiClientDelegate

    func wifiOpened(writer: @escaping (Data) -> Void, token: String) { controller.wifiConnected(writer: writer, token: token) }
    func wifiRecord(_ record: Data) { controller.wifiRecord(record) }
    func wifiClosed() { controller.wifiDisconnected() }

    // MARK: internals

    private func updateAdvertising() {
        peripheral?.setAdvertising(identity.peer != nil || pairingToken != nil)
    }

    private func retick(_ s: LinkState) {
        let period: Int
        switch s {
        case .handshaking, .pairing: period = 1
        case .connected: period = 5
        case .offline: period = 0
        }
        guard period != tickPeriod else { return }
        tickPeriod = period
        ticker?.cancel()
        ticker = nil
        guard period > 0 else { return }
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + .seconds(period), repeating: .seconds(period), leeway: .milliseconds(250))
        t.setEventHandler { [weak self] in self?.controller.tick() }
        t.resume()
        ticker = t
    }

    static func describe(_ s: LinkState) -> String {
        switch s {
        case .offline: return "offline"
        case .handshaking: return "handshaking"
        case .pairing(_, _, let auto): return auto ? "pairing-auto" : "pairing"
        case .connected(_, let w): return w ? "connected-wifi" : "connected"
        }
    }

    static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// Matches the phone's mDNS TXT `id`: base64url of the first 8 bytes of SHA-256(phone key).
    static func wifiId(_ key: Data) -> String { B64.encode(Data(SHA256.hash(data: key)).prefix(8)) }
}
