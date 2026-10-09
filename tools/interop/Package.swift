// swift-tools-version:5.9
import PackageDescription
let package = Package(
    name: "tether-peer",
    dependencies: [.package(path: "../../mac/TetherCore")],
    targets: [.executableTarget(name: "tether-peer", dependencies: [.product(name: "TetherCore", package: "TetherCore")])]
)
