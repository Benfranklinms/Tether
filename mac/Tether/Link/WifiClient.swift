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

    init(queue: DispatchQueue, delegate: WifiClientDelegate) {
        self.queue = queue
        self.delegate = delegate
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
        tryCandidates()
    }

    func revoke() {
        offer = nil
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
        for a in offer.addrs { list.append(.hostPort(host: NWEndpoint.Host(a), port: port)) }
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
            Log.info("wifi.unreachable", ["retry": "\(retries)"])
            if retries <= 3 {
                queue.asyncAfter(deadline: .now() + .seconds(retries * 3)) { [weak self] in self?.tryCandidates() }
            }
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
