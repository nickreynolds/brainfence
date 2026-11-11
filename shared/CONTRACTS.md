# Cross-platform contracts

Every client (Android, macOS, Windows) reads and writes the same synced rows, so
the rules below must behave identically everywhere. A client that is more lenient
than the others sets the real level of protection for all of them.

Code can't be shared across Kotlin, Swift and TypeScript, so this folder shares
definitions and test cases instead:

| Path | What | Consumed by |
| --- | --- | --- |
| `schema.json` | Synced tables and columns, with which columns hold JSON or booleans | `codegen.mjs` → each client's PowerSync schema and upload column lists |
| `tokens.json` | Color palette, light and dark: Material 3 roles plus blocking-state roles | `codegen.mjs` → Compose colors, CSS variables and a TypeScript map, SwiftUI colors |
| `fixtures/*.json` | Test cases for the rules below | Each client's unit tests load them directly |

Run `npm run codegen` from the repo root after editing `schema.json` or
`tokens.json`, and commit the generated files. `npm run codegen:check` fails if
they are out of date. Generated files (don't edit by hand):

| Client | From `schema.json` | From `tokens.json` |
| --- | --- | --- |
| Android | `data/db/AppSchema.kt` (schema + `JSON_ENCODED_COLUMNS`) | `ui/theme/Color.kt` |
| Windows | `src/shared/schema.generated.ts` | `src/renderer/src/tokens.css`, `src/shared/tokens.generated.ts` |
| macOS | `Brainfence/Sync/AppSchema.swift`, `BrainfenceCore/…/SchemaColumns.swift` | `Brainfence/Theme/Palette.swift` |

### Colors

The palette is the **Foil** direction from branding round 4
(`branding/src/schemes.mjs`, shown in the Brand Lab gallery), built from the
app icon's Static colors. Surfaces and buttons are greys, near-black on light
and near-white on dark, and the neon colors carry meaning. Change
`tokens.json` to restyle every client at once.

`color` holds the Material 3 roles. `blocking` holds three app families, each
with `X`, `onX`, `XContainer` and `onXContainer`. Every client uses them for
the same things:

| Family | Means | Used for |
| --- | --- | --- |
| `blocked` (magenta) | Something is blocked right now | The block screen and overlay, the "N blocked" status card, "Blocking now" on a rule, lock icons, overdue tasks that are blocking |
| `unlocked` (lime) | Nothing is blocked, or a gating task is done | The "All clear" card, an active rule that isn't blocking, the check on a task done today, the sync dot when connected |
| `pending` (cyan) | A loosening change is waiting out its time lock | The pending-change notice and icon on a rule |

Rules for using them:

- On an `XContainer` fill, text and icons use `onXContainer`, never `X`.
- `error` is only for real errors (failed saves, sign-in problems), not for blocking.
- `primaryContainer` is the brand lime in both modes. Anything drawn on it uses
  `onPrimaryContainer`, including secondary text, which would be unreadable in
  dark mode in `onSurface` or `onSurfaceVariant`.

## Platform-tagged app entries

`blocking_rules.blocked_apps` and the meditation `verification_config.companion_apps`
hold entries from every platform in one array:

| Platform | Entry | Identity |
| --- | --- | --- |
| Android | `{"platform": "android", "package": "com.twitter.android"}` | `android:<package>` |
| Android (legacy) | `"com.twitter.android"` (bare string) | `android:<string>` |
| macOS | `{"platform": "macos", "bundle_id": "com.hnc.Discord"}` | `macos:<bundle_id>` |
| Windows | `{"platform": "windows", "exe": "discord.exe"}` | `windows:<exe, lowercased>` |
| Anything else | `{"platform": "<p>", …}` | `<p>:<entry as JSON, keys sorted, no whitespace>` |

Entries may carry extra fields (such as a display `name`); they don't change the identity.

- An editor only manages its own platform's entries. On save it **must** write
  every other entry back unchanged, including platforms it doesn't recognize.
- Sets of entries are compared by identity (`fixtures/blocked-app-keys.json`).

## Rule edits and the time-lock

Editing an existing rule goes through `pending_changes`: a JSON object with any of
`name`, `blocked_apps`, `blocked_domains`, `condition_task_ids`, `condition_logic`,
`is_active` (0/1) and `config_lock_hours`. If the change can only make blocking
stricter it is applied immediately. Otherwise it is stored in `pending_changes`
with `changes_apply_at = now + config_lock_hours`, and any client applies it once
that time has passed.

A change is **stricter** only if every field it sets passes these checks
(`fixtures/rule-restrictiveness.json`):

- `blocked_apps`: the new set contains every current entry (by identity, across all platforms).
- `blocked_domains`: the new set contains every current domain (after normalizing).
- `condition_logic`: not `"all"` → `"any"`.
- `condition_task_ids`, using the logic the rule will have after the change. Unchanged sets are always fine; otherwise:
  - The rule has no conditions now (it always blocks): any change is weaker.
  - `"all"`: the new set must contain every current task.
  - `"any"`: the new set must be a subset of the current one, since each task is a way to unblock. An empty set is stricter, because the rule then always blocks.
- `is_active`: not 1 → 0.
- `config_lock_hours`: not lowered.
- `name`: neutral.

## Domains

`blocked_domains` entries are normalized before use and before comparing
(`fixtures/domains.json`): lowercase; drop the scheme, path, query, fragment,
user info and port; drop a leading `*.` and then `www.`; drop trailing dots.
An entry with no dot left is ignored. A host is blocked if it equals a
blocked domain or is a subdomain of it.

## Timestamps

Clients write ISO-8601 UTC (`2026-04-16T14:03:00.123Z`). Rows replicated from
Postgres arrive in its text form (`2026-04-16 14:03:00.123+00`). Always parse
before comparing; never compare the strings (`' ' < 'T'`, so a string comparison
can apply a time-locked change up to a day early). Text without an offset is UTC
(`fixtures/timestamps.json`).

## Task occurrences and blocking

Every surface that asks "is this task due, done or blocking?" (the task list,
the blocking evaluator, notifications, GPS monitoring, task screens) uses one
pure function of the task, its latest completion and the current local time
(`fixtures/task-status.json`). It returns:

| Field | Meaning |
| --- | --- |
| `scheduled` | An occurrence is open now: due today, or carried over from an earlier day |
| `completed` | The open occurrence is done (with no open occurrence: completed today) |
| `carried_over` | The open occurrence started on an earlier day |
| `phase` | `BEFORE_START`, `ACTIVE`, `PAST_DUE` from the `available_from` / `due_at` window, or null with no window. A carried-over occurrence is always `PAST_DUE`. With no open occurrence it's null. |

**Which occurrence is open**, for today's local date T:

- **One-off** (`recurrence_type` null): always open; completed once any completion exists.
- **`interval`**: always open; completed if completed today (unchanged).
- **`daily`, `weekly`, `monthly`**: a date is *scheduled* when the recurrence
  lands on it (`daily` with `days` only on those weekdays; `weekly` on its
  `day`, in the weeks its `interval` picks; `monthly` on its `week`'s `day`).
  If T is scheduled, T's occurrence is open. Otherwise, if the task has
  `carry_over = 1`, the latest scheduled date before T is open, unless it falls
  before the local date of the task's `created_at`. Otherwise nothing is open,
  and the task isn't due today. A config that can't be parsed counts as
  scheduled every day (fail closed).
- The open occurrence on date D is **completed** when the latest completion's
  local date is on or after D. So a carried-over task is done by completing it
  on any day from D on, and the next scheduled date opens a fresh occurrence,
  dropping one that was never done.

**A rule condition is unmet** (the task is causing a block) exactly when the
task is `scheduled`, not `completed`, its `phase` is null or `PAST_DUE`, today
is one of its `blocking_days_of_week` (if any are set), and it isn't
`home_only_blocking` with the user known to be away. A condition task that
can't be found is unmet (fail closed).

So a carried-over task blocks all day, every blocking day, until it's done or
its next occurrence starts. A recurring task that isn't scheduled today never
blocks.

**Task list.** *Active*: scheduled, not completed, not `BEFORE_START`.
*Done*: completed today. *Upcoming*: completed recurring tasks (showing the
next occurrence), scheduled tasks before their window opens, and recurring
tasks with no open occurrence (showing the next one). A carried-over row says
when it was due ("Overdue since Monday").

## Completion alternatives

`tasks.completion_alternatives` is a JSON array of other ways to complete a
task, on top of its own `task_type` / `verification_type`. Completing any one
of them completes the task. Each entry:

```json
{"verification_type": "gps", "label": "Climbing gym",
 "verification_config": {"latitude": 40.7069, "longitude": -73.924,
                         "radius_meters": 150, "mode": "enter", "min_duration_m": 30}}
```

- `verification_config` has the same shape as a task's config of that type
  (`lat`/`latitude`, `lng`/`longitude`, `radius_m`/`radius_meters` are all
  accepted). `label` is optional, for display.
- Supported types: **`gps`** with `mode` `enter` (the default) or `leave`.
  Clients ignore entries they don't support, and editors write them back
  unchanged (`fixtures/completion-alternatives.json`).
- A device that does GPS (Android, and Windows as its allowed exception)
  monitors a task's GPS alternatives exactly like a GPS task: while the task is
  `scheduled`, not `completed` and not `BEFORE_START`. Completing through one
  writes a normal `task_completions` row whose `verification_data` is the GPS
  proof plus `"method": "completion_alternative"` and `"alternative": 0` (its
  index).
- The Mac doesn't complete them (location is mobile-only) but shows them:
  "Or be at Climbing gym for 30 min (your phone completes this)".
- Task rows and the task's own screen say "or" with the place: e.g. a workout
  shows "Do the workout, or be at Climbing gym".

## Timed sets (`timed_sets` steps)

An automatic interval step. `config`:

| Key | Default | Meaning |
| --- | --- | --- |
| `reps` | 1 | Hangs (work periods) per side per set |
| `work_seconds` | 10 | Length of each hang |
| `rest_seconds` | 20 | Rest between hangs |
| `sets` | 1 | Sets |
| `sides` | 1 | 2 = one arm at a time: each set is done left, then right, at the same weight |
| `side_rest_seconds` | `rest_seconds` | Rest between the left and right halves of a set |
| `set_rest_seconds` | `rest_seconds` | Rest after each set, including before the next step |
| `weight` | none | `"added"`: weight added to bodyweight, negative means assisted (hangs). `"load"`: the weight lifted, never negative (pinch block, lifts). |
| `weight_prompt` | `"start"` | When the runner asks for the weight: `"start"` = once, before the routine starts; `"each_set"` = right before each set |

The step has `sets × sides × reps` entries, one per hang, ordered by set, then
side, then hang. Entry *i* is in set ⌊*i* / (reps × sides)⌋ and on side
⌊*i* / reps⌋ mod sides (0 = left). After hang *i* the runner rests (`fixtures/timed-sets.json`):

- nothing after the routine's last hang;
- `set_rest_seconds` when *i* + 1 is a multiple of reps × sides (end of a set);
- `side_rest_seconds` when *i* + 1 is a multiple of reps (switching sides);
- `rest_seconds` otherwise.

**Weights.** With `weight_prompt: "start"`, pressing Start shows every such step
with its weight (pre-filled) to confirm before anything runs. Resuming a paused
routine doesn't ask again. With `"each_set"`, the runner stops before the first
hang of each set (after the rest, with no countdown in that rest) and asks for
that set's weight, then counts down. A set's weight applies to every later set
of the step too, until changed. Pre-filled weights come from the last
completion's row with the same `set_number`.

Each hang is saved as one `step_completions` row with `set_number` = its
1-based position and `data` `{"completed": true, "work_seconds": 7}`, plus
`"weight"` (the set's weight) for weighted steps and `"side": "left" | "right"`
for two-sided steps.

## Step images

`routine_steps.image_url` is an optional `https` URL to a PNG (for example the
holds to use on a hangboard), shown with the step and on the automatic
runner's current step. Brainfence's own images live in the public Supabase
Storage bucket `routine-images` (`dev/routine-images/`).

## Onboarding

The setup guide (first-time user experience) teaches how Brainfence works and
creates a starter set of tasks, a blocking rule and the Five Remembrances deck.
Its state is the synced `onboarding` table: one row per user with
`id = user_id`, so devices that finish offline upsert the same row.

### Where a device goes (`fixtures/onboarding.json` → `route`)

Decided from the `onboarding` row, the number of active tasks, and the platform.
Android and Windows **can locate** (they have a home location). The Mac can't.

| Situation | Route |
| --- | --- |
| The first sync hasn't finished | `wait`: show nothing yet, since the row may still be on its way |
| No row, and the account has tasks | `auto_dismiss`: write a `dismissed` row and show nothing (an existing user) |
| No row, no tasks | `guide`: the full setup guide |
| `dismissed` | `none` |
| `completed`, this platform can locate and `deferred_steps` isn't empty | `device_setup` |
| `completed`, and this platform's `<platform>_setup_at` is null | `device_setup` |
| Anything else | `none` |

"Run setup guide" in settings opens the full guide whatever the row says.

### Starters (`fixtures/onboarding.json` → `plan`)

Each starter can be turned on or off, and its `available_from` / `due_at`
changed. Everything else is fixed. All starters are `status = 'active'`, with
`tags = []`, `blocking_rule_ids = []`, `blocking_days_of_week = []` and
`home_only_blocking = 0`.

| Key | Order | Title | task_type / verification_type | Recurrence | Blocks | Window | verification_config |
| --- | :-: | --- | --- | --- | :-: | --- | --- |
| `morning_routine` | 0 | Morning Routine | `routine` / null | daily | yes | 06:00–09:00 | `{}`; steps Make Bed (checkbox), Take Pills (checkbox), Reverse Plank (timed, 30 s) |
| `morning_walk` | 1 | Morning Walk | `simple` / `gps` | daily | yes | 06:00–11:00 | leave home: `latitude`, `longitude`, `radius_meters` from the home location, `mode: "leave"`, `min_duration_m: 0` |
| `meditation` | 2 | Meditation | `simple` / `meditation` | daily | yes | 06:00–19:00 | `duration_seconds: 600`, `short_sit_seconds: 120`, `companion_apps` from this platform |
| `afternoon_mood` | 3 | Afternoon Mood | `report` / `report` | daily | no | 16:00–19:00 | `{"report_id"}`: the account's `mood` library report (see below) |
| `journaling` | 4 | Journal | `journal` / `journal` | daily | no | 18:00–23:00 | `{}` |
| `groceries` | 5 | Groceries | `shopping` / `gps` | none | no | none | the store's `latitude` / `longitude`, `radius_meters: 100`, `mode: "notify"`, `notify_window` every day 08:00–21:00 |
| `five_remembrances` | | | the `five_remembrances` library deck, unless the account already has one | | | | |

- `sort_order` is the order column, so starters keep their order even when created later.
- Every starter is on when the guide opens except `afternoon_mood`, which the
  user turns on.
- **`afternoon_mood`** records the Mood report: the plan task's `report` is
  `"mood"` and its `verification_config` is `{}`. When writing it, the guide
  sets `report_id` to the account's report with `library_key = 'mood'`,
  copying the library's Mood report (and its metrics) in the same write if
  there's none.
- **Location starters** (`morning_walk` needs a home, `groceries` a store) are
  *deferred* when the location isn't known, which is always the case on the
  Mac. A deferred starter isn't created; it goes in `deferred_steps` as
  `{"key", "available_from", "due_at"}` (the times only for starters that have
  a window). A device that can locate finishes it in device setup by building
  a plan from those entries.
- **The rule** is named "Distractions" with `condition_logic: "all"`. Its
  conditions are the blocking starters created *now*, in order. It holds this
  platform's chosen apps and the chosen domains (normalized, deduplicated). It is
  created only if it would block something and has at least one condition. A
  rule with no conditions would always block.
- Adding the Five Remembrances also turns contemplation delivery on for this
  device, since the schedule is per device.

### Writing the row

- **Finishing the guide** upserts `status: 'completed'`, `completed_at`,
  `deferred_steps` (replaced) and this platform's `<platform>_setup_at = now`.
  `blocking_rule_id` and `meditation_task_id` take the new ids, keeping the old
  ones when nothing new was made. If either is new, the other platforms'
  `_setup_at` is cleared so they offer device setup again.
- **Skipping the guide** upserts `status: 'dismissed'` without touching the other columns.
- **Device setup** offers whichever of these apply, and then sets
  `<platform>_setup_at = now`. On a device that can locate, `deferred_steps`
  keeps only the starters the user kept but still has no location for
  ("later"). Skipping device setup clears them.
  The options:
  - **This platform's apps for the guide's rule.** Adding apps is a stricter
    change (see the time-lock above), so it applies at once.
  - **This platform's companion apps for the guide's meditation task.** Other
    platforms' entries are kept.
  - **Home location and the deferred starters**, on a device that can locate.
    A deferred blocking starter is added to the guide's rule as a condition
    once it's created. That goes through the normal rule-change path: under
    `"all"` adding a condition is stricter, so it applies at once.
  - **Contemplation delivery on this device**, when the account has an enabled deck.

## Email links

Sign-up asks Supabase to send the confirmation link to
`brainfence://auth-callback`, which every client registers as a URL scheme.
All clients use the PKCE flow. The project's Site URL and Redirect URLs must
include it (`npm run deploy` sets both when `SUPABASE_ACCESS_TOKEN` is in
`.env`). Supabase confirms the email *before* redirecting, so a link that can't
sign the user in has still confirmed them.

A link is classified like this (`fixtures/auth-callback.json`):

| Link carries | Kind | The client |
| --- | --- | --- |
| `error_description` or `error` (query or fragment) | `error` | Shows "That link didn't work: *message*. Sign up again with the same email to get a new one." |
| `code` (query) | `code` | Exchanges it for a session. If that fails (e.g. the link was opened on a different device than the one that signed up), shows "Your email is confirmed. Sign in to continue." |
| `access_token` and `refresh_token` (fragment) | `tokens` | Signs in with them. Shows the "confirmed" notice if that fails. |
| Anything else | `none` | Shows "Your email is confirmed. Sign in to continue." |

Other `brainfence://` URLs (and other schemes) aren't auth callbacks.

## Feedback

Every client has **Send feedback** in Settings, and the setup guide's last page
introduces it with a button to send some there and then. Both open the same
short form: a text box, a note that the message is saved with the account
along with the platform and app version, and Send.

A note is one row in `feedback`, an insert-only table: it's queued locally
(so it can be sent offline) and uploaded, but never stored on the device or
synced down. Nobody reads it back in the app; it's read from the Supabase
dashboard.

| Column | Value |
| --- | --- |
| `id` | a new UUID |
| `user_id` | the signed-in user |
| `message` | the text with surrounding whitespace trimmed (`fixtures/feedback.json`) |
| `platform` | `android`, `windows` or `macos` |
| `app_version` | the app's version name |
| `source` | `settings` or `setup_guide` |
| `created_at` | now |

Send is disabled while the trimmed text is empty or longer than 4000
characters, counted in Unicode code points as Postgres does (an emoji is one).
Account deletion removes feedback with everything else.

## Account deletion

Every client offers **Delete account** next to Sign out (Google Play requires
it for apps with accounts). It asks for confirmation, saying that this
permanently deletes the account and all of its data on every device, and
can't be undone. Then, in this order:

1. Call the `delete_account()` database function (RPC, no arguments). It
   deletes the caller's `auth.users` row; every table's `user_id` cascades.
   This needs a connection. If it fails, nothing else happens and the client
   shows the error.
2. Clear the local database (PowerSync `disconnectAndClear`). Blocking stops
   because no rules are left.
3. Sign out locally only (the server session went with the user).

Other devices still signed in to the account lose their session when it next
refreshes, and keep whatever their own sign-out keeps.

Without the app, people can ask for deletion at
`https://brainfence.online/delete-account` (`site/`), which is the URL given to
Google Play.
