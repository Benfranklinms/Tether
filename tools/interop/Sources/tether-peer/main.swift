// Mac-role (responder) Tether peer over stdin/stdout, for Kotlin<->Swift interop tests.
// stdin:  "F <hex>" fragment from the phone | "CONFIRM" | "CLIP <text>" | "QUIT"
// stdout: "F <hex>" fragment to the phone | "STATE <state>" | "MSG <desc>" | "CLOSED <reason>" | "KEY <b64 pub>" | "TOKEN <b64>"
import Foundation
import TetherCore

setvbuf(stdout, nil, _IOLBF, 0)
func out(_ s: String) { print(s); fflush(stdout) }

final class Mem: IdentityStore, OutgoingFiles, IncomingFiles {
    var localKey = KeyPair.generate()
    var pendingLocalKey: KeyPair?
    var peer: PeerRecord?
    func read(_ record: TransferRecord, offset: Int64, length: Int) throws -> Data { Data(count: length) }
    func release(_ record: TransferRecord) {}
    func partialLength(_ id: String) -> Int64 { 0 }
    func begin(_ record: TransferRecord) throws {}
    func append(_ id: String, _ data: Data) throws {}
    func finish(_ record: TransferRecord) throws -> String { "x" }
    func discard(_ id: String) {}
}

final class L: ControllerListener {
    func controllerState(_ state: LinkState) { out("STATE \(state)") }
    func controllerMessage(_ message: Message) { out("MSG \(message)") }
    func controllerTransfer(_ record: TransferRecord, important: Bool) {}
    func controllerTransferRemoved(_ id: String) {}
    func controllerPaired(_ peer: PeerRecord) { out("PAIRED \(peer.name)") }
    func controllerUnpaired(byRemote: Bool) {}
    func controllerClosed(reason: CloseReason, byRemote: Bool, wifi: Bool) { out("CLOSED \(reason) remote=\(byRemote)") }
    func controllerWifiOffer(_ offer: WifiOfferPayload) {}
    func controllerWifiRevoked() {}
    func controllerKeyRotated() {}
}

let args = CommandLine.arguments
let maxFragment = args.count > 1 ? Int(args[1]) ?? 182 : 182
let mem = Mem()
let listener = L()
let controller = TetherController(role: .responder, identity: mem,
                                  localHello: { Hello(name: "Ben’s MacBook Air", platform: "macos", app: "0.1.1") },
                                  outgoing: mem, incoming: mem, listener: listener)
var token = Data(count: PairingURI.tokenLength)
for i in 0..<token.count { token[i] = UInt8.random(in: 0...255) }
out("KEY \(B64.encode(mem.localKey.publicKey))")
out("TOKEN \(B64.encode(token))")
controller.bleConnected(maxFragment: maxFragment, writer: { f in out("F \(Hex.encode(f))") },
                        pairing: PairingContext(acceptNew: true, token: token))
while let line = readLine() {
    if line.hasPrefix("F ") { controller.bleFragment(Hex.decode(String(line.dropFirst(2))) ?? Data()) }
    else if line == "CONFIRM" { controller.confirmPairing() }
    else if line.hasPrefix("CLIP ") { _ = controller.send(.clip(id: "m1", ts: 1, text: String(line.dropFirst(5)))) }
    else if line == "QUIT" { break }
}
