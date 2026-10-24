# Brainfence for macOS

Native Swift + SwiftUI menu-bar app. Same account, tasks and blocking rules as
the Android app, synced through PowerSync; blocks apps and websites on the Mac
until the rules' condition tasks are done.

## Requirements

- macOS 14+
- **Xcode 16.3 or newer** (Swift 6.1 — required by the PowerSync and Supabase
  Swift packages)
- [XcodeGen](https://github.com/yonaskolb/XcodeGen): `brew install xcodegen`
- The repo-root `.env` with `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` and
  `POWERSYNC_URL` (the same file the Android build reads)

## Build & run

```bash
cd macos
make project        # generates Brainfence/Generated/Secrets.generated.swift + Brainfence.xcodeproj
open Brainfence.xcodeproj   # run the "Brainfence" scheme, or:
make build          # Debug build into build/DerivedData
make install        # Release build copied to /Applications and launched
make test-core      # unit tests for the pure logic (no Xcode project needed)
```

Signing uses team `R5878UK933` with automatic signing. Debug builds use the
bundle ID `dev.brainfence.macos.debug` — a separate database, keychain item and
settings — so a dev build never interferes with the installed app.

## Layout

```
BrainfenceCore/   Swift package: pure logic, unit-tested with `swift test`
                  recurrence engine, time gates, blocking evaluator (ports of the
                  Android domain layer), rule time-lock semantics, PowerSync upload
                  normalization, domain matching, task editor, routine sessions,
                  timers, reports, contemplation scheduling, task notifications
Brainfence/
  App/            entry point, AppDelegate (quit guard, single instance), AppModel
                  (composition root), login agent
  Sync/           PowerSync schema, Supabase connector, auth session
  Data/           repositories (tasks, completions, routines, shopping & journal,
                  reports, contemplations, blocking rules), installed apps
  Blocking/       BlockingEngine (evaluation loop), AppEnforcer, WebEnforcer, shield
  Services/       timers (duration / meditation / companion apps), notifications,
                  contemplation scheduler, debug log
  Views/          SwiftUI: sign-in / sign-up, tasks and their screens, task editor,
                  journal, reports, contemplations, blocking rules, debug, settings,
                  menu bar
  LaunchAgents/   launchd agent plist bundled into Contents/Library/LaunchAgents
project.yml       XcodeGen spec (the .xcodeproj is generated, not committed)
```

## How blocking works

**Why not Screen Time?** FamilyControls / ManagedSettings / DeviceActivity are
iOS-only for third-party apps: the macOS SDK marks `AuthorizationCenter`,
`FamilyActivityPicker`, `ManagedSettingsStore`, `ShieldSettings` etc. as
`@available(macOS, unavailable)`. So the Mac enforces blocking itself, the same
way Android does with its AccessibilityService.

- **Evaluation** — `BlockingEngine` runs the same evaluator as Android
  (`evaluateBlocking`): a rule blocks when it is active and its condition tasks
  aren't met; a task only causes blocking once past its `due_at`; unknown
  condition tasks fail closed; `home_only_blocking` tasks block as if at home
  (location is a phone feature, see below); `blocking_days_of_week` is
  honoured. It re-evaluates on every PowerSync change, re-reads everything from
  SQLite every 60 s (the correctness floor, also catching midnight), fires a
  precise timer at the next `due_at` / `available_from`, and refreshes on wake
  and clock / time-zone changes. Expired time-locked rule changes are applied
  on each tick.
- **Apps** — rules list Mac apps in `blocked_apps` as
  `{"platform":"macos","bundle_id":"…"}` next to Android's
  `{"platform":"android","package":"…"}` entries. `AppEnforcer` hides a blocked
  app the moment it becomes active (launch, ⌘-Tab, Dock) and shows the shield;
  running blocked apps are hidden as soon as a rule starts blocking.
- **Websites** — `blocked_domains` (shared with Android, which ignores them)
  are enforced in Safari and Chromium browsers (Chrome, Arc, Brave, Edge,
  Vivaldi, Opera) by reading the active tab URL over Apple Events and
  navigating blocked pages to `about:blank`. Subdomains match. Fails closed:
  while any site is blocked, a browser Brainfence isn't allowed to automate —
  or that can't report its URL (Firefox, Zen, …; toggle in Settings) — is
  blocked as an app.
- **Shield** — floating panel naming the rule(s) and the unmet tasks, with a
  one-click Complete for manual tasks; it notices live when the block lifts.

## Anti-bypass

- **Time-locked edits** — anything that could loosen a rule (removing apps or
  sites, changing condition tasks, any→all logic, deactivating, shortening the
  lock) is staged in `pending_changes` for `config_lock_hours` (default 24 h);
  strictly-tighter edits apply immediately. Only inactive rules can be
  deleted; deleting an active one deactivates it, which is time-locked.
- **Quit guard** — ⌘Q / Quit is refused while anything is blocking (logout and
  shutdown are allowed).
- **Relaunch** — installed in /Applications, the app registers a launchd agent
  (`SMAppService`) that starts it at login and relaunches it if it is
  force-quit or crashes (`KeepAlive.SuccessfulExit = false`). Approve it under
  System Settings → General → Login Items if prompted.
- **Sign-out** stops syncing but keeps local data, so it doesn't lift blocks.

## Sync notes

- `Sync/AppSchema.swift` and `BrainfenceCore/…/SchemaColumns.swift` are
  generated from `shared/schema.json` (`npm run codegen` at the repo root).
- Uploads that Postgres rejects permanently (constraint / type / RLS errors)
  are skipped so they don't block the queue, and listed on the Debug screen.
- The local database lives at
  `~/Library/Application Support/<bundle id>/powersync.db`.

## Location features

GPS tasks, shopping-list arrival reminders and home presence are phone
features (see `FEATURES.md`). The Mac still treats GPS and home-only tasks as
blocking conditions, and shows what they need, but they're completed by the
phone; the block lifts as soon as that completion syncs.

## Not on the Mac yet

Notes, groups and search aren't built on any platform yet (see `FEATURES.md`).
