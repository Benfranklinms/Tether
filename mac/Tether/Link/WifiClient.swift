import Foundation
import Network
import TetherCore

protocol WifiClientDelegate: AnyObject {
    /// A TCP connection to the phone is open; start the Noise session with `token`.
    func wifiOpened(writer: @escaping (Data) -> Void, token: String)
    func wifiRecord(_ record: Data)
    func wifiClosed()
}

/// Mac side of the Wi-Fi fast path. The phone tells us over Bluetooth where it listens
/// (addresses + port + one-time token). We also browse Bonjour for `_tether._tcp` and prefer
/// the service whose TXT `id` matches the paired phone, which survives address changes and
/// works on the phone's own hotspot. The first candidate that connects wins.
final class WifiClient {
    private let queue: DispatchQueue
    private weak var delegate: WifiClientDelegate?
    private var browser: NWBrowser?
    private var bonjour: [String: NWEndpoint] = [:] // TXT id -> endpoint
    private var connection: NWConnection?
    private var deframer = StreamDeframer()
    private var attempt: UUID?
    private var offer: WifiOfferPayload?
    private var expectedId: String?
    private var retries = 0
    /// 0.5.2: retry with backoff for as long as there is an offer and no session, and right
    /// away when this Mac's network path changes (e.g. it joined the phone's hotspot).
    private var retry = FastPathRetry()
    private var pathMonitor: NWPathMonitor?
    private var lastPath: String?
    private var timer: DispatchSourceTimer?

    init(queue: DispatchQueue, delegate: WifiClientDelegate) {
        self.queue = queue
        self.delegate = delegate
        watchPath()
    }

    private static var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// NWPathMonitor: on any interface/address change, restart Bonjour and retry the offer.
    private func watchPath() {
        let mon = NWPathMonitor()
        mon.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let sig = "\(path.status)|" + path.availableInterfaces.map { "\($0.name):\($0.type)" }.joined(separator: ",")
                + "|" + Self.localIPv4().joined(separator: ",")
            guard sig != self.lastPath else { return }
            let first = self.lastPath == nil
            self.lastPath = sig
            guard !first else { return }
            Log.info("wifi.path_changed", ["status": "\(path.status)", "ifaces": "\(path.availableInterfaces.count)"])
            self.restartBrowsing()
            self.retry.networkChanged(now: Self.nowMs)
        }
        mon.start(queue: queue)
        pathMonitor = mon
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 1, repeating: 1)
        t.setEventHandler { [weak self] in self?.tick() }
        t.resume()
        timer = t
    }

    private func tick() {
        guard offer != nil, !isConnected else { return }
        let now = Self.nowMs
        if retry.due(now: now, bleReady: true, wifiReady: false) {
            retry.attempted(now: now)
            Log.info("wifi.retry", ["attempt": "\(retry.attempts)"])
            tryCandidates()
        }
    }

    private func restartBrowsing() {
        guard browser != nil else { return }
        let id = expectedId
        stopBrowsing()
        browse(phoneId: id)
    }

    /// This Mac's IPv4 addresses (to rank the phone's addresses by subnet).
    static func localIPv4() -> [String] {
        var out: [String] = []
        var ifap: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifap) == 0, let first = ifap else { return out }
        defer { freeifaddrs(ifap) }
        var p: UnsafeMutablePointer<ifaddrs>? = first
        while let cur = p {
            let flags = Int32(cur.pointee.ifa_flags)
            if let sa = cur.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET), flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                    out.append(String(cString: host))
                }
            }
            p = cur.pointee.ifa_next
        }
        return out
    }

    var isConnected: Bool { connection?.state == .ready }

    /// Start browsing for the paired phone (TXT id = first 8 bytes of SHA-256 of its key, base64url).
    func browse(phoneId: String?) {
        expectedId = phoneId
        guard browser == nil else { return }
        let params = NWParameters()
        params.includePeerToPeer = false
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: TetherProtocol.bonjourType, domain: nil), using: params)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else { return }
            self.bonjour.removeAll()
            for r in results {
                if case let .bonjour(txt) = r.metadata, let id = txt["id"] { self.bonjour[id] = r.endpoint }
            }
        }
        b.stateUpdateHandler = { state in
            if case let .failed(err) = state { Log.warn("wifi.browse_failed", ["reason": "\(err)"]) }
        }
        b.start(queue: queue)
        browser = b
    }

    func stopBrowsing() {
        browser?.cancel()
        browser = nil
        bonjour.removeAll()
    }

    /// The phone offered a Wi-Fi session. Try Bonjour first, then each address.
    func connect(_ offer: WifiOfferPayload) {
        self.offer = offer
        retries = 0
        retry.linkUp(now: Self.nowMs)
        retry.attempted(now: Self.nowMs)
        tryCandidates()
    }

    func revoke() {
        offer = nil
        retry.linkDown()
        close()
    }

    func close() {
        attempt = nil
        connection?.cancel()
        connection = nil
    }

    private func candidates(_ offer: WifiOfferPayload) -> [NWEndpoint] {
        var list: [NWEndpoint] = []
        if let id = expectedId, let ep = bonjour[id] { list.append(ep) }
        guard let port = NWEndpoint.Port(rawValue: UInt16(clamping: offer.port)) else { return list }
        for a in FastPathRetry.rank(offer.addrs, local: Self.localIPv4()) { list.append(.hostPort(host: NWEndpoint.Host(a), port: port)) }
        return list
    }

    private func tryCandidates() {
        guard let offer else { return }
        close()
        let list = candidates(offer)
        let token = UUID()
        attempt = token
        tryNext(list[...], offer.token, token)
    }

    private func tryNext(_ rest: ArraySlice<NWEndpoint>, _ sessionToken: String, _ attemptId: UUID) {
        guard attempt == attemptId else { return }
        guard let ep = rest.first else {
            retries += 1
            // The 1 s ticker retries on the backoff schedule (and at once after a path change).
            Log.info("wifi.unreachable", ["retry": "\(retries)", "next_s": "\(((retry.nextAt ?? Self.nowMs) - Self.nowMs) / 1000)"])
            return
        }
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = 4
        tcp.enableKeepalive = true
        let c = NWConnection(to: ep, using: NWParameters(tls: nil, tcp: tcp))
        connection = c
        var opened = false
        c.stateUpdateHandler = { [weak self] state in
            guard let self, self.attempt == attemptId else { return }
            switch state {
            case .ready:
                opened = true
                self.retry.connected()
                self.deframer = StreamDeframer()
                Log.info("wifi.connected")
                self.delegate?.wifiOpened(writer: { [weak c] record in
                    c?.send(content: StreamFraming.frame(record), completion: .contentProcessed { err in
                        if let err { Log.warn("wifi.send_failed", ["reason": "\(err)"]) }
                    })
                }, token: sessionToken)
                self.receive(c, attemptId)
            case .failed, .cancelled:
                if opened {
                    self.connection = nil
                    self.retry.wifiLost(now: Self.nowMs)
                    self.delegate?.wifiClosed()
                } else if case .failed = state {
                    c.cancel()
                    self.tryNext(rest.dropFirst(), sessionToken, attemptId)
                }
            case .waiting:
                // No route (e.g. different network). Move on rather than wait forever.
                if !opened {
                    c.cancel()
                    self.tryNext(rest.dropFirst(), sessionToken, attemptId)
                }
            default:
                break
            }
        }
        c.start(queue: queue)
    }

    private func receive(_ c: NWConnection, _ attemptId: UUID) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { [weak self] data, _, done, error in
            guard let self, self.attempt == attemptId else { return }
            if let data, !data.isEmpty {
                do {
                    for r in try self.deframer.feed(data) { self.delegate?.wifiRecord(r) }
                } catch {
                    Log.warn("wifi.bad_stream")
                    c.cancel()
                    return
                }
            }
            if done || error != nil {
                c.cancel()
                return
            }
            self.receive(c, attemptId)
        }
    }
}
