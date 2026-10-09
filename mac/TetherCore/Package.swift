// swift-tools-version:5.9
import PackageDescription

// On macOS the core uses CryptoKit. On Linux (used only to run the tests in a
// container) it uses swift-crypto, which has the same API.
#if os(Linux)
let deps: [Package.Dependency] = [.package(url: "https://github.com/apple/swift-crypto.git", "3.0.0"..<"4.0.0")]
let coreDeps: [Target.Dependency] = [.product(name: "Crypto", package: "swift-crypto")]
#else
let deps: [Package.Dependency] = []
let coreDeps: [Target.Dependency] = []
#endif

let package = Package(
    name: "TetherCore",
    platforms: [.macOS(.v13)],
    products: [.library(name: "TetherCore", targets: ["TetherCore"])],
    dependencies: deps,
    targets: [
        .target(name: "TetherCore", dependencies: coreDeps),
        .testTarget(name: "TetherCoreTests", dependencies: ["TetherCore"]),
    ]
)
