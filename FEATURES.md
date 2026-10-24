# Brainfence features and platform status

Every feature should work on every platform. The only exceptions are things a
platform can't do and location features, which belong on mobile (see
[Parity policy](#parity-policy)). This file tracks where each platform stands.
Update it in the same change as any feature work.

| Mark | Meaning |
| --- | --- |
| ✅ | Implemented |
| 🟡 | Partly implemented (see the note) |
| ❌ | Missing: a parity gap to close |
| ➖ | Not applicable on this platform (reason in the note) |
| 📋 | Planned, not started on any platform |
| ⚠️ | Implemented, but differs from the parity policy (see the note) |

Last reviewed: 2026-10-24.

## Account and sync

| Feature | Android | Windows | macOS | Notes |
| --- | :-: | :-: | :-: | --- |
| Email sign-in | ✅ | ✅ | ✅ | |
| Sign-up | ✅ | ✅ | ❌ | Mac only signs in to an existing account. |
| Offline-first sync (PowerSync + Supabase) | ✅ | ✅ | ✅ | Schema generated from `shared/schema.json`. |

## Tasks

| Feature | Android | Windows | macOS | Notes |
| --- | :-: | :-: | :-: | --- |
| Task list (Active / Done / Upcoming) | ✅ | ✅ | 🟡 | Mac has a Today view only. |
| Create, edit and delete tasks | ✅ | ✅ | ❌ | |
| Recurrence (daily, selected days, weekly) | ✅ | ✅ | ✅ | |
| Time-of-day gates (`available_from` / `due_at`) | ✅ | ✅ | ✅ | |
| Manual completion | ✅ | ✅ | ✅ | |
| Duration timer tasks | ✅ | ✅ | ❌ | |
| Meditation timer (in-app session, bells) | ✅ | ✅ | ❌ | |
| Meditation via companion apps | ✅ | ✅ | ❌ | Each platform has its own `companion_apps` entries. |
| Routine / workout runner (weight × reps, checkboxes, timed steps, rest, supersets, pre-fill from last time, report steps) | ✅ | ✅ | ❌ | |
| Journal tasks and journal history | ✅ | ✅ | ❌ | |
| Report tasks (metric questionnaires) | ✅ | ✅ | ❌ | |
| Shopping list tasks | ✅ | ✅ | ❌ | Arrival reminders are a location feature (below). |
| Task notifications ("ready", "blocking soon") | ✅ | ✅ | ❌ | |

## Location (mobile)

Desktop apps still respect location-based tasks, so those tasks keep blocking
there. They just can't complete them. See [Parity policy](#parity-policy).

| Feature | Android | Windows | macOS | Notes |
| --- | :-: | :-: | :-: | --- |
| GPS tasks: arrive / leave / stay | ✅ | ⚠️ | ➖ | Windows completes them from the Windows location service, which the policy reserves for mobile. |
| Shopping list arrival reminders | ✅ | ⚠️ | ➖ | Windows reminds on arrival using desktop location. |
| Home location and home-only blocking | ✅ | ⚠️ | ➖ | Windows tracks presence from desktop location. The Mac treats the user as at home (strict). |

## Blocking

| Feature | Android | Windows | macOS | Notes |
| --- | :-: | :-: | :-: | --- |
| Blocking rules: create and edit, with app picker | ✅ | ✅ | ✅ | |
| Delete rules | ⚠️ | ✅ | ❌ | Windows only deletes inactive rules (deactivating is time-locked). Android deletes active rules instantly, which skips the time-lock. Mac can't delete. |
| Shared blocking evaluator (all/any logic, `due_at`, blocking days, fail closed on unknown tasks) | ✅ | ✅ | ✅ | `shared/CONTRACTS.md` |
| App blocking | ✅ | ✅ | ✅ | Android: AccessibilityService and overlay. Windows: foreground-window polling and overlay. Mac: NSWorkspace and shield panel. |
| Blocking screen lists the rule and its unmet tasks | ✅ | ✅ | ✅ | |
| Complete tasks from the blocking screen | ✅ | ✅ | 🟡 | Mac: manual tasks only. |
| Website blocking (`blocked_domains`) | ❌ | ✅ | ✅ | Windows: hosts file. Mac: browser tab inspection. Android is deferred; a local VPN (`VpnService`) could do it. |
| Blocking status indicator | ✅ | ✅ | ✅ | Android: home card and ongoing notification. Windows: tray. Mac: menu bar. |
| Time-locked loosening edits, cancelling pending changes | ✅ | ✅ | ✅ | `shared/CONTRACTS.md` |
| Other platforms' app entries kept when editing | ✅ | ✅ | ✅ | |
| Starts at login / always running | ✅ | ✅ | ✅ | Android: foreground service and boot receiver. Windows: login item and tray. Mac: launchd agent. |
| Resists quitting | ➖ | 🟡 | ✅ | Android: the system manages the service. Windows asks for confirmation but doesn't relaunch if killed. Mac refuses to quit while blocking and relaunches if killed. |
| Multi-step disable flow | 📋 | 📋 | 📋 | |

## Contemplations, reports and tools

| Feature | Android | Windows | macOS | Notes |
| --- | :-: | :-: | :-: | --- |
| Contemplation decks, library and scheduled delivery | ✅ | ✅ | ❌ | |
| Contemplation notifications with "affirm" | ✅ | ✅ | ❌ | |
| Reports and metrics: editor, library, history | ✅ | ✅ | ❌ | |
| Debug log screen | ✅ | ✅ | 🟡 | Mac Settings lists rejected uploads only. |

## Planned (no client yet)

| Feature | Android | Windows | macOS | Notes |
| --- | :-: | :-: | :-: | --- |
| Notes: markdown, wikilinks, backlinks | 📋 | 📋 | 📋 | The `notes` table syncs already. README §7. |
| Groups with visibility schedules, and tags | 📋 | 📋 | 📋 | The `groups` table syncs already. |
| Full-text search across notes and tasks | 📋 | 📋 | 📋 | |
| Web client (notes and admin) | | | | Lower priority. It would be a fourth platform. |

## Platform-specific (not parity items)

- **Android:** accessibility service setup wizard; exact-alarm and usage-access permission prompts.
- **Windows:** one-time UAC prompt for hosts-file access; Settings screen (launch at login, minimize blocked windows).
- **macOS:** Automation permission per browser; option to block browsers that can't report their URL.

## Parity policy

- **Every feature ships on every platform**, with the same behavior. Shared
  rules live in `shared/CONTRACTS.md`, with test cases in `shared/fixtures`.
- **Exceptions** are limited to:
  - Things a platform can't do. Say why in the notes column, and get as close as the platform allows.
  - **Location features are mobile-only.** Desktop apps still treat location-based tasks as conditions, so they block until done, but they don't complete them, track home presence or send arrival reminders.
- A missing platform shows as ❌ here until it ships.
