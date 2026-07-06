-- Per-task filter on which days of the week the task's blocking condition
-- is enforced. Empty array means "every day" (the previous behavior).
-- Non-empty means the task only contributes to blocking on the listed days,
-- while remaining completable on other days.
--
-- Stored as a JSONB array of lowercase weekday abbreviations:
--   ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]

ALTER TABLE tasks
  ADD COLUMN blocking_days_of_week JSONB NOT NULL DEFAULT '[]'::jsonb;
