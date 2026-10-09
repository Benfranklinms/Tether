import XCTest
@testable import TetherCore

final class VectorTests: XCTestCase {
    func testCacophonyNoiseXX() throws {
        let root = try Vectors.load("noise_xx.json") as? [String: Any]
        let vectors = root?["vectors"] as? [[String: Any]] ?? []
        XCTAssertFalse(vectors.isEmpty)
        for v in vectors {
            let ini = HandshakeState(role: .initiator, localStatic: try KeyPair(privateKey: hex(v["init_static"])),
                                     prologue: hex(v["init_prologue"]), fixedEphemeral: hex(v["init_ephemeral"]))
            let res = HandshakeState(role: .responder, localStatic: try KeyPair(privateKey: hex(v["resp_static"])),
                                     prologue: hex(v["resp_prologue"]), fixedEphemeral: hex(v["resp_ephemeral"]))
            let messages = v["messages"] as? [[String: Any]] ?? []
            for i in 0..<3 {
                let (w, r) = i % 2 == 0 ? (ini, res) : (res, ini)
                let ct = try w.writeMessage(hex(messages[i]["payload"]))
                XCTAssertEqual(Hex.encode(ct), messages[i]["ciphertext"] as? String, "handshake \(i)")
                XCTAssertEqual(try r.readMessage(ct), hex(messages[i]["payload"]))
            }
            let a = try ini.split()
            let b = try res.split()
            XCTAssertEqual(Hex.encode(a.handshakeHash), v["handshake_hash"] as? String)
            XCTAssertEqual(a.handshakeHash, b.handshakeHash)
            for i in 3..<messages.count {
                let p = hex(messages[i]["payload"])
                let initiatorSends = i % 2 == 0
                let ct = try (initiatorSends ? a.send : b.send).encrypt(p)
                XCTAssertEqual(Hex.encode(ct), messages[i]["ciphertext"] as? String, "transport \(i)")
                XCTAssertEqual(try (initiatorSends ? b.receive : a.receive).decrypt(ct), p)
            }
        }
    }

    func testFragmentVectors() throws {
        for v in try Vectors.array("fragments.json") {
            let record = hex(v["record"])
            let f = Fragmenter()
            for _ in 0..<(v["recordId"] as? Int ?? 0) { _ = try f.fragment(Data(), maxFragment: 20) }
            let frags = try f.fragment(record, maxFragment: v["maxFragment"] as? Int ?? 0)
            XCTAssertEqual(frags.map(Hex.encode), v["fragments"] as? [String], v["name"] as? String ?? "")
            let r = Reassembler()
            var out: Data?
            for x in frags { out = r.accept(x) }
            XCTAssertEqual(out, record)
        }
    }

    func testMessageVectors() throws {
        var byName: [String: Message] = [:]
        for v in try Vectors.array("messages.json") {
            byName[v["name"] as? String ?? ""] = try MessageCodec.decode(hex(v["encoded"]))
        }
        XCTAssertEqual(byName["ping"], .ping(t: 1_700_000_000_000))
        XCTAssertEqual(byName["pair_confirm_token"], .pairConfirm(token: "AAECAwQFBgcICQoLDA0ODw"))
        XCTAssertEqual(byName["pair_confirm_plain"], .pairConfirm(token: nil))
        XCTAssertEqual(byName["clip"], .clip(id: "c1", ts: 1_700_000_000_123, text: "héllo, Mac 👋\nline two"))
        XCTAssertEqual(byName["notif"], .notif(NotifPayload(key: "0|com.whatsapp|1|null|10123", pkg: "com.whatsapp",
                                                            app: "WhatsApp", title: "Asha", text: "On my way \"now\"",
                                                            ts: 1_700_000_001_000)))
        XCTAssertEqual(byName["notif_dismiss"], .notifDismiss(key: "k"))
        XCTAssertEqual(byName["file_offer"], .fileOffer(FileOfferPayload(id: "f-1", name: "report.pdf", size: 1_048_576,
                                                                         sha256: String(repeating: "a", count: 64),
                                                                         mime: "application/pdf")))
        XCTAssertEqual(byName["file_chunk"], .fileChunk(id: "f-1", offset: 16384, data: Data((0..<64).map { UInt8($0) })))
        XCTAssertEqual(byName["file_complete"], .fileComplete(id: "f-1", ok: false, reason: "hash"))
        XCTAssertEqual(byName["wifi_offer"], .wifiOffer(WifiOfferPayload(addrs: ["192.168.1.20", "10.0.0.5"], port: 51234,
                                                                          token: "q83vEjRWeJA")))
        XCTAssertEqual(byName["unknown_future"], .unknown(type: 0x7f))
        XCTAssertEqual(byName["extra_fields_ignored"], .pong(t: 5))
    }

    func testSasVectors() throws {
        for v in try Vectors.array("sas.json") {
            XCTAssertEqual(Session.computeSas(hex(v["handshakeHash"])), v["sas"] as? String)
        }
    }

    func testFileNameVectors() throws {
        for v in try Vectors.array("filenames.json") {
            XCTAssertEqual(TransferEngine.safeFileName(v["raw"] as? String ?? ""), v["safe"] as? String, "\(v["raw"] ?? "")")
        }
    }
}
