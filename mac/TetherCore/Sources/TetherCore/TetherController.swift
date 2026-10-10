//
// TetherController.swift — transports <-> protocol glue (mirrors TetherController.kt).
// Single-threaded: the app drives it from one serial queue.
//

import Foundation

public protocol IdentityStore: AnyObject {
    var localKey: KeyPair { get set }
    var pendingLocalKey: KeyPair? { get set }
    var peer: PeerRecord? { get set }
}

public enum LinkState: Equatable {
    case offline
    case handshaking
    case pairing(sas: String, peerName: String, auto: Bool)
    case connected(peerName: String, wifi: Bool)
}

public protocol ControllerListener: AnyObject {
    func controllerState(_ state: LinkState)
    func controllerMessage(_ message: Message)
    func controllerTransfer(_ record: TransferRecord, important: Bool)
    func controllerTransferRemoved(_ id: String)
    func controllerPaired(_ peer: PeerRecord)
    func controllerUnpaired(byRemote: Bool)
    func controllerClosed(reason: CloseReason, byRemote: Bool, wifi: Bool)
    func controllerWifiOffer(_ offer: WifiOfferPayload)
    func controllerWifiRevoked()
    func controllerKeyRotated()
    /// An authenticated message couldn't be decoded and was dropped (see SessionListener).
    func controllerUndecodable(type: UInt8, reason: String)
}

public extension ControllerListener {
    func controllerUndecodable(type: UInt8, reason: String) {}
}

/// Single-use tokens for the Wi-Fi bind (phone side; mirrors WifiBindGate.kt).
public final class WifiBindGate {
    private var current: Data?
    public init() {}

    public func mint() -> String {
        var t = Data(count: 16)
        for i in 0..<16 { t[i] = UInt8.random(in: 0...255) }
        current = t
        return B64.encode(t)
    }

    public func revoke() { current = nil }

    public func admit(_ first: Message) -> Bool {
        guard let expected = current, case .wifiBind(let token) = first, let got = B64.decode(token),
              constantTimeEquals(expected, got) else { return false }
        current = nil
        return true
    }
}

public final class TetherController {
    public static let defaultFragment = 20
    public static let handshakeTimeoutMs: Int64 = 15_000
    public static let pairingTimeoutMs: Int64 = 120_000
    public static let handshakeRetryMs: Int64 = 3_000
    public static let maxHandshakeRetries = 3

    private let role: Role
    private let identity: IdentityStore
    private let localHello: () -> Hello
    private weak var listener: ControllerListener?
    private let clock: () -> Int64
    public private(set) var transfers: TransferEngine!

    private var ble: Session?
    private var bleWriter: ((Data) -> Void)?
    private var maxFragment = TetherController.defaultFragment
    private let fragmenter = Fragmenter()
    private let reassembler = Reassembler()
    private var heartbeat = Heartbeat()
    private var bleStartedAt: Int64 = 0
    private var bleDelegate: BleDelegate?
    private var blePairing = PairingContext.closed
    private var blePairingChanged = false
    private var bleLinkUp = false
    private var bleHeard = false
    private var bleFirstSeen = false
    public private(set) var bleHandshakeRetries = 0

    private var wifi: Session?
    private var wifiWriter: ((Data) -> Void)?
    private var wifiBound = false
    private var wifiStartedAt: Int64 = 0
    private var wifiToken: Data?
    private let gate = WifiBindGate()
    private var wifiHeartbeat = Heartbeat()
    private var wifiDelegate: WifiDelegate!
    private var engineDelegate: EngineDelegate!

    public private(set) var state: LinkState = .offline

    public init(role: Role, identity: IdentityStore, localHello: @escaping () -> Hello, outgoing: OutgoingFiles,
                incoming: IncomingFiles, listener: ControllerListener,
                clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.role = role
        self.identity = identity
        self.localHello = localHello
        self.listener = listener
        self.clock = clock
        self.engineDelegate = EngineDelegate()
        self.transfers = TransferEngine(outgoing: outgoing, incoming: incoming, listener: engineDelegate, clock: clock)
        self.wifiDelegate = WifiDelegate()
        engineDelegate.owner = self
        wifiDelegate.owner = self
    }

    public var bleReady: Bool { ble?.state == .established }
    /// The current Bluetooth session (tests).
    var bleSessionForTesting: Session? { ble }
    public var wifiReady: Bool { wifi?.state == .established && wifiBound }
    public var isConnected: Bool { bleReady || wifiReady }

    public func restoreTransfers(_ saved: [TransferRecord]) { transfers.restore(saved) }

    private var liveSession: Session? {
        if let b = ble, b.state == .established { return b }
        if wifiReady { return wifi }
        return nil
    }

    /// Features the connected peer announced in its Hello; empty while offline. A 0.1.x phone
    /// announces only clip/notif/file, so newer features stay hidden until it updates.
    public var peerCaps: Set<String> { Set(liveSession?.remoteHello?.caps ?? []) }

    /// The peer's app version (diagnostics); nil while offline.
    public var peerApp: String? { liveSession?.remoteHello?.app }

    public func peerSupports(_ cap: String) -> Bool { peerCaps.contains(cap) }

    // MARK: Bluetooth

    public func bleConnected(maxFragment: Int, writer: @escaping (Data) -> Void, pairing: PairingContext = .closed) {
        ble?.linkLost()
        self.maxFragment = Self.clampFragment(maxFragment)
        bleWriter = writer
        blePairing = pairing
        bleLinkUp = true
        bleHandshakeRetries = 0
        startBleSession()
    }

    /// Pairing mode opened or closed while a link may already be up. Applies to the next
    /// handshake on this link (the responder restarts its session when the phone retries).
    public func setPairing(_ pairing: PairingContext) {
        blePairing = pairing
        blePairingChanged = true
    }

    private func startBleSession() {
        // Detach the old session first so its close can't reach the app as a link loss.
        let old = ble
        ble = nil
        old?.linkLost()
        reassembler.reset()
        bleStartedAt = clock()
        bleHeard = false
        bleFirstSeen = false
        blePairingChanged = false
        let delegate = BleDelegate()
        delegate.owner = self
        bleDelegate = delegate
        let s = Session(role: role, localStatic: identity.localKey, localHello: localHello(),
                        isTrusted: { [weak self] in self?.trusts($0) ?? false }, pairing: blePairing, listener: delegate)
        delegate.session = s
        ble = s
        // A Bluetooth reconnect while Wi-Fi carries the session is not a visible state change.
        if wifiReady { refreshState() } else { setState(.handshaking) }
        s.start()
    }

    public func setMaxFragment(_ n: Int) { maxFragment = Self.clampFragment(n) }

    /// Fragment size actually used for a reported ATT MTU - 3: never above 512 (GATT value limit).
    public static func clampFragment(_ n: Int) -> Int { min(max(n, defaultFragment), Fragmentation.maxBleFragment) }

    public func bleFragment(_ fragment: Data) {
        guard bleLinkUp else { return }
        // Any fragment proves the radio path is alive, even mid-way through a long record.
        heartbeat.heard(clock())
        guard let record = reassembler.accept(fragment) else { return }
        bleHeard = true
        if role == .responder && Self.isFirstHandshakeMessage(record) {
            // The phone (re)started the handshake: a lost message or a session that already
            // timed out here must not wedge the link, so begin a fresh session for it.
            let cur = ble
            let fresh = cur?.state == .handshaking && !bleFirstSeen && !blePairingChanged
            let restartable = cur == nil || cur?.state == .handshaking || cur?.state == .closed
            if !fresh && restartable {
                bleHandshakeRetries += 1
                startBleSession()
            }
            bleFirstSeen = true
        }
        ble?.receive(record)
    }

    private static func isFirstHandshakeMessage(_ r: Data) -> Bool {
        r.count >= 2 && r[r.startIndex] == RecordKind.handshake && r[r.startIndex + 1] == 1
    }

    public func bleDisconnected() {
        bleLinkUp = false
        ble?.linkLost()
        bleWriter = nil
    }

    public func confirmPairing() { ble?.confirmPairing() }
    public func rejectPairing() { ble?.rejectPairing() }

    // MARK: Wi-Fi

    @discardableResult
    public func offerWifi(addrs: [String], port: Int) -> Bool {
        guard let s = ble, s.state == .established, !addrs.isEmpty else { return false }
        try? s.send(.wifiOffer(WifiOfferPayload(addrs: addrs, port: port, token: gate.mint())))
        return true
    }

    public func revokeWifi(reason: String = "network") {
        gate.revoke()
        if bleReady { try? ble?.send(.wifiRevoke(reason: reason)) }
        wifiDisconnected()
    }

    /// A TCP connection is up. Noise roles match Bluetooth (the phone initiates). The Mac
    /// passes the offer's token and sends WIFI_BIND; the phone passes nil and checks it.
    public func wifiConnected(writer: @escaping (Data) -> Void, token: String?) {
        wifi?.linkLost()
        wifiWriter = writer
        wifiBound = false
        wifiStartedAt = clock()
        wifiToken = token.flatMap(B64.decode)
        let s = Session(role: role, localStatic: identity.localKey, localHello: localHello(),
                        isTrusted: { [weak self] in self?.trusts($0) ?? false }, pairing: .closed,
                        listener: wifiDelegate, prologue: TetherProtocol.prologueWifi)
        wifi = s
        s.start()
    }

    public func wifiRecord(_ record: Data) {
        wifiHeartbeat.heard(clock())
        wifi?.receive(record)
    }

    public func wifiDisconnected() { wifi?.linkLost() }

    // MARK: App actions

    /// Send on the best link (large clips prefer Wi-Fi). Returns false if nothing can carry it.
    @discardableResult
    public func send(_ message: Message) -> Bool {
        var isClip = false
        var clipBytes = 0
        if case .clip(_, _, let text) = message {
            isClip = true
            clipBytes = text.utf8.count
        }
        // Screen video never goes over Bluetooth; screen control prefers Wi-Fi for latency.
        var wifiOnly = false
        var prefersWifi = isClip
        switch message {
        case .videoFrame: wifiOnly = true
        case .screenInput, .videoFeedback, .screenStart, .screenStop, .screenState: prefersWifi = true
        // Anything big goes over Wi-Fi when it is up.
        case .smsThreads, .smsMessages, .nowPlayingArt, .callLog, .contacts: prefersWifi = true
        case .fsList, .photos, .thumb, .continueCard, .phoneStatus, .typeText, .albums: prefersWifi = true
        default: break
        }
        if wifiOnly && !wifiReady { return false }
        let useWifi = wifiReady && (wifiOnly || prefersWifi || !bleReady)
        guard let s = useWifi ? wifi : ble, s.state == .established else { return false }
        if isClip && !useWifi && clipBytes > TetherProtocol.maxClipBytes { return false }
        do { try s.send(message) } catch { return false }
        return true
    }

    public func sendFile(_ record: TransferRecord) { transfers.enqueue(record) }
    public func cancelTransfer(_ id: String) { transfers.cancel(id) }
    public func retryTransfer(_ id: String) { transfers.retry(id) }
    public func removeTransfer(_ id: String) { transfers.remove(id) }

    public func unpair() {
        let s = ble?.state == .established ? ble : nil
        try? s?.send(.unpair(reason: "user"))
        identity.peer = nil
        identity.pendingLocalKey = nil
        s?.close(.unpaired)
        wifi?.close(.unpaired)
        listener?.controllerUnpaired(byRemote: false)
    }

    @discardableResult
    public func rotateKey() -> Bool {
        guard let s = ble, s.state == .established else { return false }
        let next = identity.pendingLocalKey ?? KeyPair.generate()
        identity.pendingLocalKey = next
        try? s.send(.keyRotate(key: B64.encode(next.publicKey)))
        return true
    }

    public func tick() {
        let now = clock()
        if let s = ble { tickSession(s, &heartbeat, bleStartedAt, now) }
        if let s = wifi {
            if s.state == .established && !wifiBound && now - wifiStartedAt > Self.handshakeTimeoutMs {
                s.close(.timeout)
            } else {
                tickSession(s, &wifiHeartbeat, wifiStartedAt, now)
            }
        }
    }

    private func tickSession(_ s: Session, _ hb: inout Heartbeat, _ startedAt: Int64, _ now: Int64) {
        switch s.state {
        case .handshaking:
            if s === ble && role == .initiator && !bleHeard && bleHandshakeRetries < Self.maxHandshakeRetries
                && now - startedAt > Self.handshakeRetryMs {
                // Phone: nothing back from the Mac yet. A write may have been lost while the
                // Mac was still setting up the subscription, so send the first message again.
                bleHandshakeRetries += 1
                startBleSession()
            } else if now - startedAt > Self.handshakeTimeoutMs {
                s.close(.timeout)
            }
        case .verifying: if now - startedAt > Self.pairingTimeoutMs { s.close(.timeout) }
        case .established:
            switch hb.tick(now) {
            case .sendPing: try? s.send(.ping(t: now))
            case .dead: s.close(.timeout)
            case .none: break
            }
        default: break
        }
    }

    // MARK: internals

    private func trusts(_ key: Data) -> Bool { identity.peer?.trusts(key) ?? false }

    private lazy var bleChannel = Channel(transport: .BLE) { [weak self] m in
        guard let s = self?.ble, s.state == .established else { return }
        try? s.send(m)
    }

    private lazy var wifiChannel = Channel(transport: .WIFI) { [weak self] m in
        guard let s = self?.wifi, s.state == .established else { return }
        try? s.send(m)
    }

    fileprivate func refreshState() {
        let peer = identity.peer
        if let s = ble, s.state == .verifying {
            state = .pairing(sas: s.sas ?? "", peerName: s.remoteHello?.name ?? "", auto: false)
        } else if isConnected, let p = peer {
            state = .connected(peerName: p.name, wifi: wifiReady)
        } else if ble?.state == .handshaking {
            state = .handshaking
        } else {
            state = .offline
        }
        listener?.controllerState(state)
    }

    fileprivate func setState(_ s: LinkState) {
        state = s
        listener?.controllerState(s)
    }

    fileprivate func updateEngineChannel() {
        if wifiReady {
            if transfers.transport != .WIFI { transfers.switchTo(wifiChannel) }
        } else if bleReady {
            if transfers.transport != .BLE { transfers.switchTo(bleChannel) }
        } else if transfers.isConnected {
            transfers.onDisconnected()
        }
    }

    fileprivate func handleCommon(_ s: Session, _ message: Message, _ channel: Channel) {
        switch message {
        case .ping(let t): try? s.send(.pong(t: t))
        case .pong: break
        case .unpair:
            identity.peer = nil
            identity.pendingLocalKey = nil
            listener?.controllerUnpaired(byRemote: true)
            s.close(.unpaired)
            wifi?.close(.unpaired)
        case .keyRotate(let key):
            if let k = B64.decode(key), k.count == KeyPair.keyLength, let peer = identity.peer {
                identity.peer = peer.withPendingKey(k)
                try? s.send(.keyRotateAck(key: key))
            }
        case .keyRotateAck(let key):
            if let pending = identity.pendingLocalKey, B64.encode(pending.publicKey) == key {
                identity.localKey = pending
                identity.pendingLocalKey = nil
                listener?.controllerKeyRotated()
            }
        case .wifiOffer(let offer):
            if channel.transport == .BLE { listener?.controllerWifiOffer(offer) }
        case .wifiRevoke:
            listener?.controllerWifiRevoked()
        case .wifiBind:
            break
        default:
            if !transfers.handle(message, from: channel) { listener?.controllerMessage(message) }
        }
    }

    // MARK: delegates

    fileprivate final class EngineDelegate: TransferListener {
        weak var owner: TetherController?
        func transferUpdated(_ record: TransferRecord, important: Bool) {
            owner?.listener?.controllerTransfer(record, important: important)
        }
        func transferRemoved(_ id: String) { owner?.listener?.controllerTransferRemoved(id) }
    }

    fileprivate final class BleDelegate: SessionListener {
        weak var owner: TetherController?
        /// The session this delegate belongs to; callbacks from a replaced session are ignored.
        weak var session: Session?

        private var live: TetherController? {
            guard let o = owner, let s = session, o.ble === s else { return nil }
            return o
        }

        func sessionOutput(_ record: Data) {
            guard let o = live, let w = o.bleWriter else { return }
            do {
                for f in try o.fragmenter.fragment(record, maxFragment: o.maxFragment) { w(f) }
            } catch {
                o.ble?.close(.protocolError)
            }
        }

        func sessionPairingRequest(sas: String, remote: Hello, remoteKey: Data, autoConfirmed: Bool) {
            live?.setState(.pairing(sas: sas, peerName: remote.name, auto: autoConfirmed))
        }

        func sessionEstablished(remote: Hello, remoteKey: Data, newlyPaired: Bool) {
            guard let o = live else { return }
            let now = o.clock()
            o.heartbeat.reset(now)
            if newlyPaired {
                let rec = PeerRecord.paired(name: remote.name, platform: remote.platform, key: remoteKey, now: now)
                o.identity.peer = rec
                o.identity.pendingLocalKey = nil
                o.listener?.controllerPaired(rec)
            } else {
                o.identity.peer = o.identity.peer?.afterHandshake(remoteKey, remoteName: remote.name)
            }
            o.updateEngineChannel()
            o.refreshState()
            if o.identity.pendingLocalKey != nil { o.rotateKey() }
        }

        func sessionMessage(_ message: Message) {
            guard let o = live, let s = o.ble else { return }
            o.handleCommon(s, message, o.bleChannel)
        }

        func sessionUndecodable(type: UInt8, reason: String) {
            live?.listener?.controllerUndecodable(type: type, reason: reason)
        }

        func sessionClosed(reason: CloseReason, byRemote: Bool) {
            guard let o = live else { return }
            o.ble = nil
            // bleWriter stays: the radio link may still be up and the phone may retry.
            o.reassembler.reset()
            o.gate.revoke()
            o.updateEngineChannel()
            o.refreshState()
            o.listener?.controllerClosed(reason: reason, byRemote: byRemote, wifi: false)
        }
    }

    fileprivate final class WifiDelegate: SessionListener {
        weak var owner: TetherController?

        func sessionOutput(_ record: Data) { owner?.wifiWriter?(record) }

        func sessionPairingRequest(sas: String, remote: Hello, remoteKey: Data, autoConfirmed: Bool) {}

        func sessionEstablished(remote: Hello, remoteKey: Data, newlyPaired: Bool) {
            guard let o = owner else { return }
            o.wifiHeartbeat.reset(o.clock())
            if let token = o.wifiToken {
                try? o.wifi?.send(.wifiBind(token: B64.encode(token)))
                bind()
            }
        }

        func sessionMessage(_ message: Message) {
            guard let o = owner, let s = o.wifi else { return }
            if !o.wifiBound {
                if o.wifiToken == nil && o.gate.admit(message) { bind() } else { s.close(.untrusted) }
                return
            }
            o.handleCommon(s, message, o.wifiChannel)
        }

        func sessionUndecodable(type: UInt8, reason: String) {
            owner?.listener?.controllerUndecodable(type: type, reason: reason)
        }

        func sessionClosed(reason: CloseReason, byRemote: Bool) {
            guard let o = owner else { return }
            let wasBound = o.wifiBound
            o.wifi = nil
            o.wifiWriter = nil
            o.wifiBound = false
            o.wifiToken = nil
            o.updateEngineChannel()
            o.refreshState()
            if wasBound || reason != .linkLost { o.listener?.controllerClosed(reason: reason, byRemote: byRemote, wifi: true) }
        }

        private func bind() {
            guard let o = owner else { return }
            o.wifiBound = true
            o.updateEngineChannel()
            o.refreshState()
        }
    }
}

/// Record framing on a TCP stream: u32 length (BE) || record.
public enum StreamFraming {
    public static func frame(_ record: Data) -> Data {
        var out = Data(capacity: 4 + record.count)
        out.appendU32(UInt32(record.count))
        out.append(record)
        return out
    }
}

/// Accumulates TCP bytes and yields whole records.
public final class StreamDeframer {
    private var buffer = Data()
    private let maxRecord: Int

    public init(maxRecord: Int = TetherProtocol.maxTcpRecord) { self.maxRecord = maxRecord }

    /// Returns the complete records in `bytes`; throws on a corrupt length.
    public func feed(_ bytes: Data) throws -> [Data] {
        buffer.append(bytes)
        var out: [Data] = []
        while buffer.count >= 4 {
            let b = [UInt8](buffer.prefix(4))
            let len = Int(b[0]) << 24 | Int(b[1]) << 16 | Int(b[2]) << 8 | Int(b[3])
            guard len >= 1, len <= maxRecord else { throw ProtocolError("bad record length \(len)") }
            guard buffer.count >= 4 + len else { break }
            out.append(Data(buffer[(buffer.startIndex + 4)..<(buffer.startIndex + 4 + len)]))
            buffer = Data(buffer.dropFirst(4 + len))
        }
        return out
    }
}
