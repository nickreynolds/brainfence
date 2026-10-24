# Brainfence

Local-first task and habit app whose tasks gate access to distracting apps and
websites. One Supabase + PowerSync backend, and a fully native client per
platform:

| Client | Directory | Stack | Tests |
| --- | --- | --- | --- |
| Android | `android/` | Kotlin, Jetpack Compose | `./gradlew :app:testDebugUnitTest` |
| Windows | `desktop-app/` | Electron, React, TypeScript | `npm test`, `npm run typecheck` |
| macOS | `macos/` | Swift, SwiftUI | `make test` (`BrainfenceCore`), `make build` |

`README.md` is the architecture spec. `FEATURES.md` tracks what each platform implements.

## Feature parity (required)

**Every feature is implemented on all platforms (Android, Windows, macOS) in
the same piece of work.** A change that adds or changes user-facing behavior on
one client isn't done until the others have it too. The only exceptions:

- **Things a platform can't do.** Get as close as the platform allows, and note the reason in `FEATURES.md`.
- **Location features are mobile-only.** Desktop clients still treat location-based tasks (GPS tasks, home-only blocking) as blocking conditions, but they don't complete them, track home presence or send arrival reminders. Exception: Windows already uses the PC's location for these, and that's allowed to stay. Don't add it to macOS.

If one platform can't be finished in the same change (for example, its toolchain isn't available), say so explicitly and mark the gap ❌ in `FEATURES.md`. Don't leave it for someone to discover.

For every feature change:

1. Implement it on Android, Windows and macOS. Match the existing code and conventions on each platform.
2. If the behavior must agree across clients (anything that reads or writes synced data or decides blocking), define it in `shared/CONTRACTS.md` and add test cases to `shared/fixtures/`. Every client's test suite loads those files.
3. Schema changes go in `shared/schema.json`, color changes in `shared/tokens.json`. Then run `npm run codegen` and commit the generated files. Never edit generated files by hand. A Supabase migration in `supabase/` and the sync rules in `powersync/` usually need updating too.
4. Update `FEATURES.md` (status per platform, and the "Last reviewed" date).

## Cross-platform data rules

- Editors only manage their own platform's entries in `blocked_apps` and
  `companion_apps`. Every other entry must be written back unchanged.
- Loosening a blocking rule is time-locked. Use the shared restrictiveness
  rules; don't add a client-specific shortcut.
- Parse timestamps before comparing; never compare them as strings.

Details for all three: `shared/CONTRACTS.md`.
