// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "RevisionCore",
    platforms: [.iOS(.v18), .macOS(.v14)],
    products: [
        .library(name: "RevisionCore", targets: ["RevisionCore"]),
    ],
    targets: [
        .target(name: "RevisionCore"),
        .testTarget(name: "RevisionCoreTests", dependencies: ["RevisionCore"]),
    ]
)
