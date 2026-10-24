# Cross-platform contracts

Every client (Android, macOS, Windows) reads and writes the same synced rows, so
the rules below must behave identically everywhere. A client that is more lenient
than the others sets the real level of protection for all of them.

Code can't be shared across Kotlin, Swift and TypeScript, so this folder shares
definitions and test cases instead:

| Path | What | Consumed by |
| --- | --- | --- |
| `schema.json` | Synced tables and columns, with which columns hold JSON or booleans | `codegen.mjs` → each client's PowerSync schema and upload column lists |
| `tokens.json` | Color palette (Material 3 roles, light and dark) | `codegen.mjs` → Compose colors, CSS variables, SwiftUI colors |
| `fixtures/*.json` | Test cases for the rules below | Each client's unit tests load them directly |

Run `npm run codegen` from the repo root after editing `schema.json` or
`tokens.json`, and commit the generated files. `npm run codegen:check` fails if
they are out of date. Generated files (don't edit by hand):

| Client | From `schema.json` | From `tokens.json` |
| --- | --- | --- |
| Android | `data/db/AppSchema.kt` (schema + `JSON_ENCODED_COLUMNS`) | `ui/theme/Color.kt` |
| Windows | `src/shared/schema.generated.ts` | `src/renderer/src/tokens.css` |
| macOS | `Brainfence/Sync/AppSchema.swift`, `BrainfenceCore/…/SchemaColumns.swift` | `Brainfence/Theme/Palette.swift` |

The palette starts as the Material 3 baseline that Android was already
rendering (Compose material3 1.3.1 defaults, plus Android's original light
`primary`). Change it in `tokens.json` to restyle every client at once.

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
