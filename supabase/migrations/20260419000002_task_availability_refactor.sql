-- Move timing from blocking_rules to tasks.
--
-- Tasks now have:
--   available_from  TEXT  (HH:MM) — when the task becomes completable each day
--   due_at          TEXT  (HH:MM) — when the task becomes overdue / triggers blocking
--
-- Blocking rules no longer have their own schedule window.

-- ── tasks: add availability columns ──────────────────────────────────────────
ALTER TABLE tasks ADD COLUMN available_from TEXT;  -- e.g. "07:00"
ALTER TABLE tasks ADD COLUMN due_at         TEXT;  -- e.g. "10:00"

-- ── blocking_rules: drop schedule ────────────────────────────────────────────
ALTER TABLE blocking_rules DROP COLUMN active_schedule;
