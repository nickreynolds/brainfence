# Brainfence — Project Task List

Tasks are ordered roughly by dependency. An agent should work through tasks in order, stopping at any task where `CAN_BE_DONE_BY_CLAUDE: NO` and waiting for a human to complete it and update the STATUS before continuing.

**STATUS values**: `TODO` | `IN_PROGRESS` | `DONE` | `BLOCKED`

---

## Infrastructure & Backend

---

### INFRA-001: Create Supabase Project

**STATUS**: DONE
**CAN_BE_DONE_BY_CLAUDE**: NO

Create a new Supabase project via the Supabase dashboard.

- Project name: `brainfence`
- Region: closest to primary user
- Save the project URL, anon key, and service role key
- Record values in a local `.env` file (not committed)

**Human action required**: Log in to supabase.com, create the project, copy credentials into `brainfence/.env`.

---

### INFRA-002: Apply Postgres Schema

**STATUS**: DONE
**CAN_BE_DONE_BY_CLAUDE**: YES

Write and apply the full Postgres schema from the README (§4.1) as a SQL migration file.

- Create `supabase/migrations/001_initial_schema.sql`
- Include all tables: `groups`, `tasks`, `routine_steps`, `task_completions`, `step_completions`, `blocking_rules`, `notes`
- Include indexes for common query patterns (user_id, task_id, completed_at)
- Include `updated_at` triggers for tables that have that column
- Apply via Supabase CLI: `supabase db push`

**Depends on**: INFRA-001

---

### INFRA-003: Configure Row Level Security (RLS)

**STATUS**: DONE
**CAN_BE_DONE_BY_CLAUDE**: YES

Write RLS policies for all tables so users can only access their own data.

- Enable RLS on every table
- Add policy: `user_id = auth.uid()` for SELECT, INSERT, UPDATE, DELETE on all tables
- For `step_completions` and `routine_steps` (which join via task_id), write policies that traverse the join
- Add policies to `supabase/migrations/002_rls_policies.sql`
- Apply and verify via Supabase dashboard SQL editor

**Depends on**: INFRA-002

---

### INFRA-004: Write PowerSync Self-Hosted Config

**STATUS**: DONE
**CAN_BE_DONE_BY_CLAUDE**: YES

Write the Docker Compose and PowerSync config files for the self-hosted PowerSync service.

- Create `powersync/docker-compose.yml` using the official `journeyapps/powersync-service` image
- Create `powersync/config.yaml` with:
  - Supabase Postgres connection (reads `SUPABASE_DB_URL` from env)
  - JWT validation via Supabase's JWKS endpoint (`{SUPABASE_URL}/auth/v1/.well-known/jwks.json`) — Supabase now uses asymmetric RS256 signing, so no shared secret is needed
  - Sync rules path pointing to `powersync/sync-rules.yaml` (written in INFRA-005)
  - Port configuration (default: 8080)
- Add `POWERSYNC_URL` (the self-hosted instance URL) to `.env.example`
- Add a `README` note in `powersync/` explaining the setup steps

**Note**: Logical replication must be enabled on Supabase Postgres before the service will connect — see INFRA-004b.

---

### INFRA-004b: Deploy Self-Hosted PowerSync

**STATUS**: DONE
**CAN_BE_DONE_BY_CLAUDE**: NO

Start the PowerSync service and verify it connects to Supabase.

- Enable logical replication on the Supabase Postgres instance:
  - Supabase dashboard → Database → Replication → enable logical replication
  - Or via SQL: `ALTER SYSTEM SET wal_level = logical;`
- Run `docker compose up -d` from `powersync/`
- Verify the service starts and connects (check logs: `docker compose logs -f`)
- Set `POWERSYNC_URL=http://localhost:8080` (or your server's address) in `.env`

**Human action required**: Enable Supabase logical replication, run Docker, verify connection.

**Depends on**: INFRA-004

---

### INFRA-005: Write PowerSync Sync Rules

**STATUS**: DONE
**CAN_BE_DONE_BY_CLAUDE**: YES

Define sync rules so each user only receives their own data.

Create `powersync/sync-rules.yaml`:

```yaml
bucket_definitions:
  user_data:
    parameters: SELECT token_parameters.user_id
    data:
      - SELECT * FROM groups WHERE user_id = bucket.user_id
      - SELECT * FROM tasks WHERE user_id = bucket.user_id
      - SELECT r.* FROM routine_steps r JOIN tasks t ON r.task_id = t.id WHERE t.user_id = bucket.user_id
      - SELECT * FROM task_completions WHERE user_id = bucket.user_id
      - SELECT sc.* FROM step_completions sc JOIN task_completions tc ON sc.task_completion_id = tc.id WHERE tc.user_id = bucket.user_id
      - SELECT * FROM blocking_rules WHERE user_id = bucket.user_id
      - SELECT * FROM notes WHERE user_id = bucket.user_id
```

Deploy to PowerSync and verify sync rules apply.

**Depends on**: INFRA-004b

---

### INFRA-006: Create Bootstrap Dev Script

**STATUS**: DONE
**CAN_BE_DONE_BY_CLAUDE**: YES

Create `dev/bootstrap.ts` and `dev/bootstrap.json` as described in README §8.

- `bootstrap.json`: seed data covering all task types (manual, GPS enter, GPS leave, duration, meditation, time_gate, routine, workout) and a blocking rule
- `bootstrap.ts`: TypeScript script using Supabase JS client that reads the JSON, creates/upserts a test user, inserts all tasks and groups, resolves task IDs for blocking rules, then inserts blocking rules
- Script should be idempotent (upsert by title per user)
- Add `"bootstrap": "npx tsx dev/bootstrap.ts"` to package.json scripts
- Add a `dev/package.json` or root `package.json` with `@supabase/supabase-js` and `tsx`

**Depends on**: INFRA-003

---

### INFRA-007: Verify Bootstrap Script End-to-End

**STATUS**: DONE
**CAN_BE_DONE_BY_CLAUDE**: NO

Run the bootstrap script against the live Supabase project and verify data appears correctly in the dashboard.

```bash
cd dev && npm run bootstrap
```

Check Supabase Table Editor: all tasks, groups, and blocking rules should be visible for the test user.

**Human action required**: Run the script, inspect the dashboard, confirm data looks correct.

**Depends on**: INFRA-006

---

## Android App

---

### ANDROID-001: Scaffold Android Project

**STATUS**: DONE
**CAN_BE_DONE_BY_CLAUDE**: YES

Create the Android project structure.

- Create `android/` directory with a Kotlin + Jetpack Compose project
- Use Android Studio project template: Empty Activity with Compose
- `minSdk 26`, `targetSdk 35`
- Set up Gradle with version catalog (`libs.versions.toml`)
- Add dependencies: Compose BOM, Coroutines, Hilt (DI), Navigation Compose
- Set up basic package structure: `ui/`, `data/`, `domain/`, `service/`

---

### ANDROID-002: Integrate Supabase Auth (Android)

**STATUS**: DONE
**CAN_BE_DONE_BY_CLAUDE**: YES

Add Supabase Auth to the Android app.

- Add `supabase-kt` (Kotlin Supabase client) to Gradle
- Configure with project URL and anon key from `.env`
- Implement email/password sign-in and sign-up screens
- Store session securely using `EncryptedSharedPreferences`
- Expose current user via a `SessionRepository` / StateFlow

**Depends on**: ANDROID-001, INFRA-001

---

### ANDROID-003: Integrate PowerSync SDK (Android)

**STATUS**: DONE
**CAN_BE_DONE_BY_CLAUDE**: YES

Add the PowerSync Kotlin SDK and set up local SQLite sync.

- Add PowerSync Kotlin SDK to Gradle
- Initialize `PowerSyncDatabase` with the schema matching all tables
- Connect to PowerSync service using user JWT from Supabase session
- Verify that after sign-in, local SQLite is populated from the server
- Expose a `DatabaseProvider` singleton (Hilt module)

**Depends on**: ANDROID-002, INFRA-005

---

### ANDROID-004: Basic Task List UI (Android)

**STATUS**: DONE
**CAN_BE_DONE_BY_CLAUDE**: YES

Build a read-only task list screen sufficient for testing blocking logic.

- Query tasks from local SQLite via PowerSync
- Display task title, type, verification type, completion status for today
- Show a visual indicator for tasks that are blocking conditions
- No creation/editing UI needed yet — bootstrap data is used instead
- Implement `TaskRepository` with a `Flow<List<Task>>` query

**Depends on**: ANDROID-003, INFRA-007

---

### ANDROID-005: Task Completion — Manual

**STATUS**: DONE
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement manual task completion flow.

- Tap a task → confirmation dialog → insert `task_completion` record into local SQLite
- PowerSync syncs the completion to Supabase
- Task shows as completed for the current occurrence
- Implement `CompletionRepository.completeTask(taskId, verificationData)`
- Implement recurrence engine: given task + completions, compute current occurrence status (due / completed / not due yet)

**Depends on**: ANDROID-004

---

### ANDROID-006: Recurrence Engine (Android / Kotlin)

**STATUS**: DONE
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement the recurrence calculator as a pure Kotlin function.

- Input: `recurrence_type`, `recurrence_config`, `last_completion_at`, `current_time`
- Output: `OccurrenceStatus` — `DUE` | `COMPLETED` | `NOT_DUE` | `UPCOMING(next_due_at)`
- Cover all recurrence types: null (one-off), daily, weekly, monthly, interval
- Cover the time_gate check: task is only completable during its configured window
- Unit test all cases

**Depends on**: ANDROID-001

---

### ANDROID-007: Foreground Service + Boot Receiver

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Set up the persistent background service.

- Create `BrainfenceService` as a `ForegroundService` with a persistent notification
- Register `BootReceiver` to restart the service on device boot
- Service runs the blocking evaluator every 60 seconds for time-based checks
- Request `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED` permissions in manifest

**Depends on**: ANDROID-003

---

### ANDROID-008: AccessibilityService + Blocking Overlay

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement the core Android app-blocking mechanism.

- Create `BrainfenceAccessibilityService` listening for `TYPE_WINDOW_STATE_CHANGED`
- On foreground app change, check if the package is in any active blocking rule
- If blocked: launch `BlockingActivity` (full-screen, non-dismissable overlay)
- `BlockingActivity` shows: which tasks are required, their status, a complete button for manual tasks
- The overlay is dismissed only when all required tasks are completed

**Depends on**: ANDROID-007, ANDROID-005

---

### ANDROID-009: Enable Accessibility Service on Device

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: NO

Install the APK via ADB and enable the accessibility service.

```bash
adb install android/app/build/outputs/apk/debug/app-debug.apk
# Then: Settings → Accessibility → Brainfence → Enable
```

**Human action required**: Build APK, install via ADB, navigate to Accessibility settings and enable.

**Depends on**: ANDROID-008

---

### ANDROID-010: Test Blocking Flow End-to-End (Android)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: NO

Verify that opening a blocked app (e.g. Twitter) triggers the overlay, and completing tasks dismisses it.

- Open a blocked app → overlay appears
- Complete a manual task → overlay updates or dismisses
- Verify sync: completion appears in Supabase dashboard

**Human action required**: Manual device testing.

**Depends on**: ANDROID-009, INFRA-007

---

### ANDROID-011: GPS Verification — Enter Mode (Android)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement GPS geofence "enter" completion.

- Use `FusedLocationProviderClient` + `GeofencingClient`
- Register a geofence from task's `verification_config` (lat, lng, radius_m)
- On `GEOFENCE_TRANSITION_ENTER`: start a timer for `min_duration_m`
- After the required duration, insert a `task_completion` with GPS proof
- Handle permissions: `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`
- Works while app is in background via the foreground service

**Depends on**: ANDROID-007

---

### ANDROID-012: GPS Verification — Leave Mode (Android)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement GPS geofence "leave" completion.

- Register a geofence for the configured location
- On `GEOFENCE_TRANSITION_EXIT`: immediately mark task as complete
- Insert `task_completion` with `{"departed_at": "..."}` as verification_data
- Update task UI to show "departed" state
- Edge case: user must be inside the geofence first before leaving counts (prevent gaming by never entering)

**Depends on**: ANDROID-011

---

### ANDROID-013: Test GPS Verification on Device

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: NO

Verify GPS enter and leave modes work on a real device with actual location data.

- Test enter mode: walk to/from the configured GPS coordinate
- Test leave mode: start inside geofence, walk away
- Verify completion records appear in Supabase

**Human action required**: Physical device testing with real GPS.

**Depends on**: ANDROID-012

---

### ANDROID-014: Duration Timer Verification (Android)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement duration-based task completion.

- Tapping a duration task starts a timer in the foreground service
- Timer UI shown on the task detail screen
- Timer persists if user navigates away (foreground service keeps it running)
- On completion: insert `task_completion` with `{"actual_seconds": N}`
- If user kills the app, timer is paused and resumes on re-open

**Depends on**: ANDROID-007

---

### ANDROID-015: Meditation Timer (Android)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement the full-screen meditation timer.

- Full-screen `MeditationActivity` with countdown display
- Optional interval bells (plays a chime sound at configured intervals)
- If user navigates away (detected by accessibility service): pause timer and show warning
- Cannot mark complete until full duration elapses
- On completion: insert `task_completion` with pause count

**Depends on**: ANDROID-014, ANDROID-008

---

### ANDROID-016: Time Gate Verification (Android)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement time-of-day gate for task completion.

- Tasks with `verification_type = 'time_gate'` show as greyed out / locked outside their window
- Inside the window: task is completable as manual
- Outside the window: show "Available 07:00–10:00" message, disable complete button
- Apply timezone from `verification_config.timezone`

**Depends on**: ANDROID-005

---

### ANDROID-017: Routine/Workout UI (Android)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Build the step-by-step routine completion UI.

- For tasks with type `routine` or `workout`: show ordered list of steps
- Each step type renders appropriately:
  - `checkbox`: simple check button
  - `weight_reps`: input fields for weight and reps, with +/- set buttons
  - `just_reps`: reps input only
  - `timed`: countdown timer per step
- Pre-fill weight/reps from the most recent `step_completions` for this task
- On "Finish Routine": write one `task_completion` + one `step_completion` per set per step

**Depends on**: ANDROID-005

---

### ANDROID-018: Anti-Bypass — Config Change Time Lock

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement the 24-hour delay on blocking rule modifications.

- When user modifies a `blocking_rule` (adds app, changes conditions, disables rule):
  - Write the change to `pending_changes` JSONB
  - Set `changes_apply_at = now() + 24 hours`
  - Show a notification: "Changes take effect in 24h"
- A background job (foreground service timer) checks `changes_apply_at` and applies pending changes
- Current rule config is the live one until the lock expires

**Depends on**: ANDROID-007

---

### ANDROID-019: Anti-Bypass — Multi-Step Disable

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement the multi-step flow to disable blocking entirely.

- Settings screen: "Disable Brainfence" option
- Step 1: Confirmation dialog ("Are you sure?")
- Step 2: 5-minute countdown before the second confirmation is shown
- Step 3: Final confirmation → blocking disabled
- A disabled state is time-locked (cannot re-disable for 1 hour after re-enabling)

**Depends on**: ANDROID-018

---

## macOS App

---

### MACOS-001: Scaffold macOS Project

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Create the macOS app project.

- Create `macos/` directory with Swift + SwiftUI project
- Deployment target: macOS 14+
- Set up Swift Package Manager with `Package.swift`
- Add entitlements file with placeholders for FamilyControls
- Basic package structure: `Views/`, `Data/`, `Services/`, `Blocking/`

---

### MACOS-002: Integrate Supabase Auth (macOS)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Add Supabase auth to the macOS app.

- Add `supabase-swift` package
- Implement email/password sign-in flow
- Store session in Keychain
- Expose `SessionManager` as an `ObservableObject`

**Depends on**: MACOS-001, INFRA-001

---

### MACOS-003: Integrate PowerSync SDK (macOS)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Add the PowerSync Swift SDK and configure local sync.

- Add PowerSync Swift SDK via SPM
- Initialize `PowerSyncDatabase` with schema matching all tables
- Connect to PowerSync service with Supabase JWT
- Verify data syncs from server to local SQLite

**Depends on**: MACOS-002, INFRA-005

---

### MACOS-004: Basic Task List UI (macOS)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Build a minimal task list for testing blocking logic.

- Query tasks from local SQLite, display in a SwiftUI `List`
- Show title, verification type, today's completion status
- Use bootstrap data; no creation UI yet

**Depends on**: MACOS-003, INFRA-007

---

### MACOS-005: Recurrence Engine (macOS / Swift)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Port the recurrence engine to Swift — same pure function logic as ANDROID-006.

- Input/output identical to Kotlin version
- Cover all recurrence types and time_gate logic
- Unit tests for all cases

**Depends on**: MACOS-001

---

### MACOS-006: Task Completion — Manual (macOS)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement manual task completion on macOS.

- Click task → complete → write `task_completion` to local SQLite
- PowerSync syncs to Supabase
- Integrate recurrence engine to show correct status

**Depends on**: MACOS-004, MACOS-005

---

### MACOS-007: Request FamilyControls Authorization

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement the FamilyControls permission request flow.

- Add `FamilyControls` capability to the entitlements file
- On first launch: call `AuthorizationCenter.shared.requestAuthorization(for: .individual)`
- Handle denied state gracefully (explain what's missing)
- Note: FamilyControls `.individual` authorization requires macOS 13+ and Apple Silicon or Intel Mac with T2 chip

**Depends on**: MACOS-001

---

### MACOS-008: Grant FamilyControls Permission on Device

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: NO

Run the macOS app and grant the FamilyControls permission when prompted.

This must be done on a real Mac — it cannot be automated or simulated. The permission dialog is a system-level prompt.

**Human action required**: Run app, approve the "Screen Time" permission dialog in System Settings.

**Depends on**: MACOS-007

---

### MACOS-009: App + Web Blocking via ManagedSettings (macOS)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement blocking of apps and web domains using ManagedSettings.

- Use `FamilyActivityPicker` (or store app tokens from known bundle IDs) to get `ApplicationToken`s
- Apply `ManagedSettingsStore().shield.applications = Set<ApplicationToken>`
- Apply `ManagedSettingsStore().shield.webDomains = Set<WebDomain>` for domain blocking
- Apply/remove shields based on the blocking evaluator result
- Custom `ShieldConfiguration` showing which tasks are required

**Depends on**: MACOS-008

---

### MACOS-010: Blocking Evaluator — macOS Background Service

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Run the blocking evaluator on a schedule and update shields.

- Register app as a Login Item using `SMAppService.mainApp`
- Use `NSApplication` delegate to intercept quit attempts (show time-locked warning)
- Run evaluator every 60 seconds using a `Timer`
- On evaluator result change: update `ManagedSettingsStore` accordingly

**Depends on**: MACOS-009, MACOS-006

---

### MACOS-011: Test Blocking Flow End-to-End (macOS)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: NO

Verify the macOS blocking flow with real Screen Time permissions.

- Open a blocked app → custom shield overlay appears
- Complete a task → shield is removed
- Verify domain blocking works in Safari

**Human action required**: Manual testing on macOS device with FamilyControls permission granted.

**Depends on**: MACOS-010, INFRA-007

---

### MACOS-012: GPS Verification (macOS)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement GPS geofence verification (enter and leave) on macOS.

- Use `CLLocationManager` with `CLCircularRegion`
- Monitor region entry/exit events
- Same enter/leave logic as ANDROID-011 / ANDROID-012
- Request `NSLocationAlwaysUsageDescription` and `NSLocationWhenInUseUsageDescription`

**Depends on**: MACOS-010

---

### MACOS-013: Duration + Meditation Timer (macOS)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement timer-based verification on macOS.

- Duration timer: runs in background, completion on elapsed time
- Meditation timer: full-screen `NSWindow` (level `.floating`) that stays on top
- On `NSApplicationWillResignActiveNotification`: pause meditation timer
- Same completion logic as Android

**Depends on**: MACOS-006

---

### MACOS-014: Anti-Bypass Features (macOS)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Port the time-lock and multi-step disable to macOS.

- Same 24-hour config change delay as ANDROID-018
- Quit protection: `applicationShouldTerminate` returns `.terminateCancel`, shows time-lock dialog
- Multi-step disable flow in preferences

**Depends on**: MACOS-010

---

### MACOS-015: Routine/Workout UI (macOS)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Build the step-by-step routine UI for macOS using SwiftUI.

- Same step types as Android: checkbox, weight_reps, just_reps, timed
- Pre-fill from last completion
- Sheet or split-view presentation

**Depends on**: MACOS-006

---

## Blocking Logic (Cross-Platform)

---

### LOGIC-001: Blocking Evaluator — TypeScript Reference Implementation

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Write a canonical TypeScript implementation of the blocking evaluator from README §5.1.

Create `shared/blocking-evaluator.ts`:

```typescript
export function shouldBlock(
  rule,
  taskCompletions,
  currentTime,
  currentLocation,
): boolean;
```

This serves as the reference for porting to Kotlin and Swift, and is used directly in the web app and bootstrap scripts.

- Cover: schedule check, condition_logic `all` vs `any`, occurrence matching
- Full unit test suite covering all edge cases

---

### LOGIC-002: Validate Evaluator Against Bootstrap Data

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Write integration tests that run the evaluator against the bootstrap dataset.

- Load bootstrap tasks and rules
- Simulate different times of day, completion states
- Assert correct block/unblock for each scenario
- Confirm GPS leave-mode tasks correctly flip from blocked to unblocked on departure event

**Depends on**: LOGIC-001, INFRA-006

---

## Notes System

---

### NOTES-001: Notes CRUD (All Platforms)

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement basic notes CRUD.

- Android: `NoteListScreen`, `NoteDetailScreen`, `NoteEditScreen` (Compose)
- macOS: equivalent SwiftUI views
- Create/read/update/delete notes, title + markdown body
- Tags and group assignment

**Depends on**: ANDROID-003, MACOS-003

---

### NOTES-002: Markdown Rendering

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Add markdown rendering to the notes editor.

- Android: use `Markwon` library for markdown → Compose rendering
- macOS: use `AttributedString` with CommonMark parser, or `WebKit` for rendering
- Toggle between edit (raw markdown) and preview modes

**Depends on**: NOTES-001

---

### NOTES-003: Wikilink Parsing and Resolution

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement `[[Note Title]]` wikilink support.

- On note save: parse `[[...]]` patterns from content using regex
- Fuzzy-match titles against existing notes (case-insensitive, trim whitespace)
- Store resolved note IDs in `outgoing_links` array
- Unresolved links shown as tappable "Create note" affordances in the editor
- Backlinks query: `SELECT * FROM notes WHERE notes.id = ANY(?::uuid[])` using `outgoing_links`

**Depends on**: NOTES-001

---

### NOTES-004: Full-Text Search

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement full-text search across notes and task titles.

- Use SQLite FTS5 virtual table on the local database
- Create FTS index on `notes(title, content)` and `tasks(title, description)`
- Search UI: search bar with real-time results as user types
- Highlight matching terms in results

**Depends on**: NOTES-001, ANDROID-004, MACOS-004

---

## Web App

---

### WEB-001: Scaffold Web App

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Create the web app (primarily for notes access and admin/testing).

- Create `web/` directory with Vite + React + TypeScript
- Add Tailwind CSS for styling
- Add `@supabase/supabase-js` and PowerSync Web SDK
- Basic auth flow (email/password)
- Simple task list view (read-only)

**Depends on**: INFRA-003

---

### WEB-002: Notes Web App

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: YES

Implement notes CRUD in the web app.

- Note list, create, edit, delete
- Markdown editor (use `@uiw/react-md-editor` or similar)
- Wikilink support (same logic as NOTES-003)
- PowerSync Web SDK for local-first sync

**Depends on**: WEB-001, NOTES-003

---

## Quality & Validation

---

### QA-001: Multi-Device Sync Verification

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: NO

Verify that completing a task on one device appears on another device within a reasonable time.

- Sign in to the same account on Android and macOS
- Complete a task on Android
- Verify it syncs and appears as completed on macOS within ~5 seconds
- Test offline: complete while offline, come back online, verify sync catches up

**Human action required**: Two-device testing.

**Depends on**: ANDROID-005, MACOS-006

---

### QA-002: Blocking Rule Sync Verification

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: NO

Verify that blocking rules sync across devices and that blocking state updates correctly on both.

- Add a blocking rule on macOS; verify Android receives it and starts blocking
- Complete required task on Android; verify macOS shields are removed

**Human action required**: Two-device testing.

**Depends on**: ANDROID-008, MACOS-010, QA-001

---

### QA-003: Anti-Bypass Audit

**STATUS**: TODO
**CAN_BE_DONE_BY_CLAUDE**: NO

Manually attempt to bypass blocking and verify each method is sufficiently difficult.

- Try: uninstalling the app
- Try: killing the foreground service via task manager
- Try: revoking location permission mid-GPS-task
- Try: changing system clock to bypass time gates
- Try: modifying blocking rules (verify 24h delay is enforced)

Document findings and implement fixes.

**Human action required**: Manual adversarial testing.

**Depends on**: ANDROID-018, ANDROID-019, MACOS-014

---
