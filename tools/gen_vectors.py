#!/usr/bin/env python3
"""Generate the shared cross-language test vectors in protocol/vectors/.

Independent Python reference for Tether's framing, message codec and SAS. The Noise
vector is copied verbatim from cacophony (haskell-cryptography/cacophony, public domain).
Both test suites (Kotlin core, Swift TetherCore) read these files.

Usage: python3 tools/gen_vectors.py [path/to/cacophony.txt]
"""
import hashlib, json, os, struct, sys

ROOT = os.path.join(os.path.dirname(__file__), "..", "protocol", "vectors")

def frag(record: bytes, max_fragment: int, record_id: int):
    chunk = max_fragment - 7
    total = max(1, -(-len(record) // chunk))
    out = []
    for i in range(total):
        data = record[i * chunk:(i + 1) * chunk]
        out.append(bytes([1]) + struct.pack(">HHH", record_id, i, total) + data)
    return out

def msg(t: int, header: dict, body: bytes = b"") -> bytes:
    h = json.dumps(header, separators=(",", ":"), ensure_ascii=False).encode()
    return bytes([t]) + struct.pack(">I", len(h)) + h + body

def sas(h: bytes) -> str:
    d = hashlib.sha256(b"tether-sas/1" + h).digest()
    return "%06d" % (int.from_bytes(d[:4], "big") % 1_000_000)

def main():
    os.makedirs(ROOT, exist_ok=True)
    src = sys.argv[1] if len(sys.argv) > 1 else "/tmp/cacophony.txt"
    vs = json.load(open(src))["vectors"]
    xx = [v for v in vs if v.get("protocol_name") == "Noise_XX_25519_ChaChaPoly_SHA256"]
    with open(os.path.join(ROOT, "noise_xx.json"), "w") as f:
        json.dump({"source": "cacophony (public domain)", "vectors": xx}, f, indent=1)

    records = [
        ("empty", b"", 20, 0),
        ("one", bytes(range(13)), 20, 1),
        ("exact", bytes(range(26)), 20, 2),
        ("multi", bytes((i * 7) % 256 for i in range(1000)), 185, 65535),
        ("mtu512", bytes((i * 13) % 256 for i in range(5000)), 512, 42),
    ]
    with open(os.path.join(ROOT, "fragments.json"), "w") as f:
        json.dump([{"name": n, "record": r.hex(), "maxFragment": m, "recordId": i,
                    "fragments": [x.hex() for x in frag(r, m, i)]} for n, r, m, i in records], f, indent=1)

    clip_text = "héllo, Mac 👋\nline two"
    messages = [
        {"name": "ping", "type": 0x10, "fields": {"t": 1700000000000}},
        {"name": "pair_confirm_token", "type": 0x20, "fields": {"token": "AAECAwQFBgcICQoLDA0ODw"}},
        {"name": "pair_confirm_plain", "type": 0x20, "fields": {}},
        {"name": "clip", "type": 0x30, "fields": {"id": "c1", "ts": 1700000000123}, "body": clip_text.encode().hex(),
         "text": clip_text},
        {"name": "notif", "type": 0x40, "fields": {"key": "0|com.whatsapp|1|null|10123", "pkg": "com.whatsapp",
         "app": "WhatsApp", "title": "Asha", "text": "On my way \"now\"", "ts": 1700000001000}},
        {"name": "notif_dismiss", "type": 0x42, "fields": {"key": "k"}},
        {"name": "file_offer", "type": 0x50, "fields": {"id": "f-1", "name": "report.pdf", "size": 1048576,
         "sha256": "a" * 64, "mime": "application/pdf"}},
        {"name": "file_chunk", "type": 0x53, "fields": {"id": "f-1", "offset": 16384}, "body": bytes(range(64)).hex()},
        {"name": "file_complete", "type": 0x56, "fields": {"id": "f-1", "ok": False, "reason": "hash"}},
        {"name": "wifi_offer", "type": 0x60, "fields": {"addrs": ["192.168.1.20", "10.0.0.5"], "port": 51234,
         "token": "q83vEjRWeJA"}},
        {"name": "unknown_future", "type": 0x7f, "fields": {"x": 1}},
        {"name": "extra_fields_ignored", "type": 0x11, "fields": {"t": 5, "future": "ok"}},
    ]
    for m in messages:
        m["encoded"] = msg(m["type"], m["fields"], bytes.fromhex(m.get("body", ""))).hex()
    with open(os.path.join(ROOT, "messages.json"), "w") as f:
        json.dump(messages, f, indent=1, ensure_ascii=False)

    hashes = [bytes(32), bytes(range(32)), hashlib.sha256(b"tether").digest()]
    with open(os.path.join(ROOT, "sas.json"), "w") as f:
        json.dump([{"handshakeHash": h.hex(), "sas": sas(h)} for h in hashes], f, indent=1)

    names = ["report.pdf", "../../etc/passwd", "a\\b\\c.txt", ".hidden", "con:tr*l?\u0001.txt", "", "   ",
             "x" * 300 + ".jpeg"]
    def safe(raw):
        import re
        base = raw.split("/")[-1].split("\\")[-1]
        s = re.sub(r'[\x00-\x1f\x7f/\\:*?"<>|]', "_", base).strip().lstrip(".")
        if len(s) > 180:
            dot = s.rfind(".")
            ext = s[dot:] if dot > 0 and len(s) - dot <= 16 else ""
            s = s[:180 - len(ext)] + ext
        return s if s.strip() else "file"
    with open(os.path.join(ROOT, "filenames.json"), "w") as f:
        json.dump([{"raw": n, "safe": safe(n)} for n in names], f, indent=1, ensure_ascii=False)
    print("wrote vectors to", os.path.abspath(ROOT))

if __name__ == "__main__":
    main()
