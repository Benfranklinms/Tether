# Kotlin ↔ Swift interop harness

Runs the real Kotlin phone core (initiator) against the real Swift Mac core (responder)
through a pipe, with real fragmentation, for code pairing and QR pairing at several MTUs.

```sh
cd tools/interop && swift build                  # builds .build/debug/tether-peer
cd ../../android && TETHER_SWIFT_PEER=$PWD/../tools/interop/.build/debug/tether-peer \
  ./gradlew :core:test --tests '*InteropTest*'
```

Without `TETHER_SWIFT_PEER` the test is skipped (CI's Android job).
