// swift-tools-version:5.10
// Pure, platform-independent Brainfence logic (recurrence, time gates, blocking
// evaluation, rule time-lock semantics, PowerSync upload normalization).
//
// Kept free of PowerSync / Supabase / AppKit so it can be unit-tested with a
// plain `swift test` and stay a line-for-line port of the Android domain layer.

import PackageDescription

let package = Package(
    name: "BrainfenceCore",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "BrainfenceCore", targets: ["BrainfenceCore"]),
    ],
    targets: [
        .target(
            name: "BrainfenceCore",
            swiftSettings: [.enableExperimentalFeature("StrictConcurrency")]
        ),
        .testTarget(
            name: "BrainfenceCoreTests",
            dependencies: ["BrainfenceCore"]
        ),
    ]
)
