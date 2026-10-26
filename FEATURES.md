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
| ➕ | Allowed exception to the parity policy (see the note) |

Last reviewed: 2026-10-25.

## Account and sync

| Feature | Android | Windows | macOS | Notes |
| --- | :-: | :-: | :-: | --- |
| Email sign-in | ✅ | ✅ | ✅ | |
| Sign-up | ✅ | ✅ | ✅ | |
| Email confirmation link opens the app (`brainfence://auth-callback`) | ✅ | ✅ | ✅ | `shared/CONTRACTS.md` (Email links). Signs in on the device that signed up; elsewhere it says the email is confirmed. |
| Offline-first sync (PowerSync + Supabase) | ✅ | ✅ | ✅ | Schema generated from `shared/schema.json`. |
| Delete account (in the app, plus a web page for requests) | ✅ | ✅ | ✅ | `shared/CONTRACTS.md` (Account deletion). Android: ⋮ menu. Windows and Mac: Settings. Clears local data and ends blocking. Web: `site/delete-account/`. |

## Setup guide

| Feature | Android | Windows | macOS | Notes |
| --- | :-: | :-: | :-: | --- |
| First-run setup guide: how Brainfence works, starter tasks (Morning Routine, Morning Walk, Meditation, Journal, Groceries, Five Remembrances) with adjustable windows, apps and sites to block | ✅ | ✅ | ✅ | `shared/CONTRACTS.md` (Onboarding). Synced `onboarding` table, so finishing on one device carries over. Existing accounts are dismissed quietly. |
| Home and store location in the guide | ✅ | ➕ | ➖ | Android: map picker. Windows: the PC's location or coordinates (allowed exception). The Mac defers Morning Walk and Groceries to a device with location. |
| Device setup on a second device (its apps for the guide's rule, companion apps, contemplation delivery, deferred starters) | ✅ | ✅ | ✅ | The Mac doesn't finish deferred starters (location). |
| Run the guide again from settings | ✅ | ✅ | ✅ | Android: ⋮ menu → Setup guide. Windows and Mac: Settings. |

## Tasks

| Feature | Android | Windows | macOS | Notes |
| --- | :-: | :-: | :-: | --- |
| Task list (Active / Done / Upcoming) | ✅ | ✅ | ✅ | |
| Create and edit tasks (wizard with every task type) | ✅ | ✅ | ✅ | Desktops enter a location as latitude / longitude. |
| Recurrence (daily, selected days, weekly) | ✅ | ✅ | ✅ | |
| Time-of-day gates (`available_from` / `due_at`) | ✅ | ✅ | ✅ | |
| Manual completion | ✅ | ✅ | ✅ | |
| Duration timer tasks | ✅ | ✅ | ✅ | |
| Meditation timer (in-app session, short sit, bells) | ✅ | ✅ | ✅ | |
| Meditation via companion apps | ✅ | ✅ | ✅ | Each platform has its own `companion_apps` entries. |
| Routine / workout runner (weight × reps, checkboxes, timed steps, rest, supersets, pre-fill from last time, report steps, automatic interval routines) | ✅ | ✅ | ✅ | |
| Journal tasks and journal history | ✅ | ✅ | ✅ | |
| Report tasks (metric questionnaires) | ✅ | ✅ | ✅ | |
| Shopping list tasks | ✅ | ✅ | ✅ | Arrival reminders are a location feature (below). |
| Task notifications ("ready", "blocking soon") | ✅ | ✅ | ✅ | |

## Location (mobile)

Desktop apps still respect location-based tasks, so those tasks keep blocking
there. The Mac doesn't complete them. Windows does, from the PC's location, as
an allowed exception. See [Parity policy](#parity-policy).

| Feature | Android | Windows | macOS | Notes |
| --- | :-: | :-: | :-: | --- |
| GPS tasks: arrive / leave / stay | ✅ | ➕ | ➖ | Windows completes them from the Windows location service (allowed exception). The Mac shows them and blocks on them; the phone completes them. |
| Shopping list arrival reminders | ✅ | ➕ | ➖ | Windows reminds on arrival using desktop location (allowed exception). |
| Home location and home-only blocking | ✅ | ➕ | ➖ | Windows tracks presence from desktop location (allowed exception). The Mac treats the user as at home (strict). |

## Blocking

| Feature | Android | Windows | macOS | Notes |
| --- | :-: | :-: | :-: | --- |
| Blocking rules: create and edit, with app picker | ✅ | ✅ | ✅ | |
| Delete rules | ✅ | ✅ | ✅ | Only inactive rules are deleted. Deleting an active rule deactivates it, which is time-locked. |
| Shared blocking evaluator (all/any logic, `due_at`, blocking days, fail closed on unknown tasks) | ✅ | ✅ | ✅ | `shared/CONTRACTS.md` |
| App blocking | ✅ | ✅ | ✅ | Android: AccessibilityService and overlay. Windows: foreground-window polling and overlay. Mac: NSWorkspace and shield panel. |
| Blocking screen lists the rule and its unmet tasks | ✅ | ✅ | ✅ | |
| Complete tasks from the blocking screen | ✅ | ✅ | ✅ | One click for simple tasks; the others open their screen. |
| Website blocking (`blocked_domains`) | ✅ | ✅ | ✅ | Android: browser address bars, read by the accessibility service. Windows: hosts file. Mac: browser tab inspection. Android and the Mac block browsers they can't check while any site is blocked. |
| Blocking status indicator | ✅ | ✅ | ✅ | Android: home card and ongoing notification. Windows: tray. Mac: menu bar. |
| Time-locked loosening edits, cancelling pending changes | ✅ | ✅ | ✅ | `shared/CONTRACTS.md` |
| Other platforms' app entries kept when editing | ✅ | ✅ | ✅ | |
| Starts at login / always running | ✅ | ✅ | ✅ | Android: foreground service and boot receiver. Windows: login item and tray. Mac: launchd agent. |
| Resists quitting | ➖ | 🟡 | ✅ | Android: the system manages the service. Windows asks for confirmation but doesn't relaunch if killed. Mac refuses to quit while blocking and relaunches if killed. **Wanted on every platform:** refusing to quit while blocking, and relaunching if killed. Windows is next (planned). |
| Multi-step disable flow | 📋 | 📋 | 📋 | |

## Contemplations, reports and tools

| Feature | Android | Windows | macOS | Notes |
| --- | :-: | :-: | :-: | --- |
| Contemplation decks, library and scheduled delivery | ✅ | ✅ | ✅ | The schedule is per device. |
| Contemplation notifications with "affirm" | ✅ | ✅ | ✅ | |
| Reports and metrics: editor, library, history | ✅ | ✅ | ✅ | |
| Debug log screen | ✅ | ✅ | ✅ | |

## Planned (no client yet)

| Feature | Android | Windows | macOS | Notes |
| --- | :-: | :-: | :-: | --- |
| Notes: markdown, wikilinks, backlinks | 📋 | 📋 | 📋 | The `notes` table syncs already. README §7. |
| Groups with visibility schedules, and tags | 📋 | 📋 | 📋 | The `groups` table syncs already. |
| Full-text search across notes and tasks | 📋 | 📋 | 📋 | |
| Delete or archive tasks | 📋 | 📋 | 📋 | No client can remove a task yet. |
| Web client (notes and admin) | | | | Lower priority. It would be a fourth platform. |

## Platform-specific (not parity items)

- **Android:** accessibility service setup wizard; exact-alarm and usage-access permission prompts.
- **Windows:** one-time UAC prompt for hosts-file access; Settings screen (launch at login, minimize blocked windows).
- **macOS:** Automation permission per browser; option to block browsers that can't report their URL; notification permission.

## Parity policy

- **Every feature ships on every platform**, with the same behavior. Shared
  rules live in `shared/CONTRACTS.md`, with test cases in `shared/fixtures`.
- **Exceptions** are limited to:
  - Things a platform can't do. Say why in the notes column, and get as close as the platform allows.
  - **Location features are mobile-only.** Desktop apps still treat location-based tasks as conditions, so they block until done, but they don't complete them, track home presence or send arrival reminders. The one allowed exception is Windows, which also uses the PC's location for these.
- A missing platform shows as ❌ here until it ships.
