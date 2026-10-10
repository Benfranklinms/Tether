// Mac-role (responder) Tether peer over stdin/stdout, for Kotlin<->Swift interop tests.
//
// args:   [maxFragment] [legacy]      ("legacy" announces only the 0.1.x caps)
// stdin:  "F <hex>"     BLE fragment from the phone
//         "W <hex>"     Wi-Fi record from the phone
//         "CONFIRM" | "CLIP <text>" | "QUIT"
//         "SEND <hex>"  an encoded message: decoded by the Swift codec, then sent by the Swift controller
//         "ECHO on|off" re-send every app-level message received (decode -> encode round trip)
//         "SENDFILE <name> <kind> <hex>"  queue an outgoing file (Mac -> phone), e.g. a clipboard image (kind "clip")
//         "TAP <viewW> <viewH> <videoW> <videoH> <x> <y> <rot>"  a click in the Mac's screen window
//                       (top-left origin) -> VideoGeometry + GestureClassifier -> SCREEN_INPUT
//         "DRAG <viewW> <viewH> <videoW> <videoH> <x1> <y1> <x2> <y2> <ms> <rot>"
// stdout: "F <hex>" | "W <hex>" | "STATE <state>" | "MSG <desc>" | "RAW <hex>" (received message re-encoded)
//         "PAIRED <name>" | "CLOSED <reason>" | "KEY <b64>" | "TOKEN <b64>" | "CAPS <a,b,c>"
//         "WIFI" (Wi-Fi offer accepted, session starting) | "FILE <id> <kind> <sha> <status>" | "FRAME <seq> <sha> <len>"
import Foundation
import TetherCore

setvbuf(stdout, nil, _IOLBF, 0)
func out(_ s: String) {
    print(s)
    fflush(stdout)
}

/// In-memory files that really verify SHA-256 like the Mac app does.
final class Mem: IdentityStore, OutgoingFiles, IncomingFiles {
    var localKey = KeyPair.generate()
    var pendingLocalKey: KeyPair?
    var peer: PeerRecord?
    var partial: [String: Data] = [:]
    var finished: [String: Data] = [:]

    var outgoing: [String: Data] = [:]
    func read(_ record: TransferRecord, offset: Int64, length: Int) throws -> Data {
        guard let d = outgoing[record.id] else { return Data(count: length) }
        let start = Int(offset)
        return d.subdata(in: start..<min(d.count, start + length))
    }
    func release(_ record: TransferRecord) {}
    func partialLength(_ id: String) -> Int64 { Int64(partial[id]?.count ?? 0) }
    func begin(_ record: TransferRecord) throws { partial[record.id] = Data() }
    func append(_ id: String, _ data: Data) throws { partial[id, default: Data()].append(data) }
    func finish(_ record: TransferRecord) throws -> String {
        let d = partial[record.id] ?? Data()
        guard sha256Hex(d) == record.sha256.lowercased() else { throw IntegrityError(message: "hash mismatch") }
        finished[record.id] = d
        partial[record.id] = nil
        return "mem://\(record.name)"
    }
    func discard(_ id: String) { partial[id] = nil }
}

var echo = false
var controller: TetherController!
let assembler = FrameAssembler()

final class L: ControllerListener {
    func controllerState(_ state: LinkState) {
        out("STATE \(state)")
        if case .connected = state { out("CAPS \(controller.peerCaps.sorted().joined(separator: ","))") }
    }

    func controllerMessage(_ message: Message) {
        out("MSG \(String(describing: message).prefix(300))")
        if let enc = try? MessageCodec.encode(message), enc.count < 4096 { out("RAW \(Hex.encode(enc))") }
        if case .videoFrame(let f) = message, let full = assembler.accept(f) {
            out("FRAME \(full.seq) \(sha256Hex(full.data)) \(full.data.count) key=\(full.key) types=\(AnnexB.nalTypes(full.data))")
        }
        if echo {
            if case .videoFrame = message { return }
            _ = controller.send(message)
        }
    }

    func controllerTransfer(_ record: TransferRecord, important: Bool) {
        guard important, record.direction == .IN, record.status.isFinal else { return }
        out("FILE \(record.id) \(record.kind ?? "-") \(record.sha256) \(record.status.rawValue) via=\(record.via?.rawValue ?? "-")")
    }

    func controllerTransferRemoved(_ id: String) {}
    func controllerPaired(_ peer: PeerRecord) { out("PAIRED \(peer.name)") }
    func controllerUnpaired(byRemote: Bool) {}
    func controllerClosed(reason: CloseReason, byRemote: Bool, wifi: Bool) { out("CLOSED \(reason) remote=\(byRemote) wifi=\(wifi)") }

    func controllerWifiOffer(_ offer: WifiOfferPayload) {
        controller.wifiConnected(writer: { r in out("W \(Hex.encode(r))") }, token: offer.token)
        out("WIFI")
    }

    func controllerWifiRevoked() { controller.wifiDisconnected() }
    func controllerKeyRotated() {}
}

let args = CommandLine.arguments
let maxFragment = args.count > 1 ? Int(args[1]) ?? 182 : 182
let legacy = args.contains("legacy")
let v03 = args.contains("v03") // a 0.3.x Mac: revision 3 caps, no calling (0.4) caps
let v04 = args.contains("v04") // a 0.4.x Mac: revision 4 caps, none of 0.5
let future = args.contains("future") // a hypothetical protocol v2 build: must be refused cleanly
let mem = Mem()
let listener = L()
controller = TetherController(role: .responder, identity: mem,
                              localHello: {
                                  let caps = legacy ? Caps.v1 : v03 ? Caps.current.filter { !Caps4.all.contains($0) && !Caps5.all.contains($0) }
                                      : v04 ? Caps.current.filter { !Caps5.all.contains($0) } : Caps.current
                                  var h = Hello(name: "Ben’s MacBook Air", platform: "macos",
                                                app: legacy ? "0.1.1" : v03 ? "0.3.1" : v04 ? "0.4.3" : "0.5.0", caps: caps)
                                  if future { h.v = 2 }
                                  return h
                              },
                              outgoing: mem, incoming: mem, listener: listener)
var token = Data(count: PairingURI.tokenLength)
for i in 0..<token.count { token[i] = UInt8.random(in: 0...255) }
out("KEY \(B64.encode(mem.localKey.publicKey))")
out("TOKEN \(B64.encode(token))")
controller.bleConnected(maxFragment: maxFragment, writer: { f in out("F \(Hex.encode(f))") },
                        pairing: PairingContext(acceptNew: true, token: token))

func geometry(_ p: [Double]) -> VideoGeometry { VideoGeometry(view: CGSizeLite(p[0], p[1]), video: CGSizeLite(p[2], p[3])) }

while let line = readLine() {
    let parts = line.split(separator: " ").map(String.init)
    switch parts.first ?? "" {
    case "F": controller.bleFragment(Hex.decode(parts[1]) ?? Data())
    case "W": controller.wifiRecord(Hex.decode(parts[1]) ?? Data())
    case "CONFIRM": controller.confirmPairing()
    case "CLIP": _ = controller.send(.clip(id: "m1", ts: 1, text: String(line.dropFirst(5))))
    case "ECHO": echo = parts.count > 1 && parts[1] == "on"
    case "SEND":
        do {
            let m = try MessageCodec.decode(Hex.decode(parts[1]) ?? Data())
            out("SENT \(controller.send(m))")
        } catch {
            out("DECODE_ERROR \(error)")
        }
    case "TAP":
        let p = parts.dropFirst().compactMap(Double.init)
        let g = geometry(p)
        if let n = g.normalized(x: p[4], y: p[5]) {
            let input = GestureClassifier.classify(down: n, up: n, durationMs: 80, aspect: p[2] / p[3], rotation: Int(p[6]))
            out("SENT \(controller.send(.screenInput(input)))")
        } else {
            out("LETTERBOX")
        }
    case "DRAG":
        let p = parts.dropFirst().compactMap(Double.init)
        let g = geometry(p)
        if let a = g.normalized(x: p[4], y: p[5], clamp: true), let b = g.normalized(x: p[6], y: p[7], clamp: true) {
            let input = GestureClassifier.classify(down: a, up: b, durationMs: Int64(p[8]), aspect: p[2] / p[3], rotation: Int(p[9]))
            out("SENT \(controller.send(.screenInput(input)))")
        }
    case "SENDFILE":
        let data = Hex.decode(parts[3]) ?? Data()
        let id = "mac-\(parts[1])"
        mem.outgoing[id] = data
        controller.sendFile(TransferRecord(id: id, direction: .OUT, name: parts[1], size: Int64(data.count), sha256: sha256Hex(data),
                                           mime: parts[2] == "clip" ? "image/png" : "application/octet-stream", status: .QUEUED,
                                           createdAt: 1, kind: parts[2] == "-" ? nil : parts[2]))
        out("QUEUED \(id)")
    case "QUIT": exit(0)
    default: break
    }
}
