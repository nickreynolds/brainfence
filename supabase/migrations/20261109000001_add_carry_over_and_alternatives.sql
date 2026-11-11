-- Carry-over: a recurring task's missed occurrence stays due (and blocking)
-- until it's completed or the next occurrence starts. See shared/CONTRACTS.md
-- (Task occurrences and blocking).
ALTER TABLE tasks ADD COLUMN carry_over BOOLEAN NOT NULL DEFAULT false;

-- Other ways to complete a task, on top of its own task_type /
-- verification_type, e.g. a workout that also completes on arriving at the
-- climbing gym:
--   [{"verification_type": "gps", "label": "Climbing gym",
--     "verification_config": {"latitude": …, "longitude": …, "radius_meters": 150,
--                             "mode": "enter", "min_duration_m": 30}}]
-- See shared/CONTRACTS.md (Completion alternatives).
ALTER TABLE tasks ADD COLUMN completion_alternatives JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Optional picture for a routine step (e.g. which hangboard holds to use).
ALTER TABLE routine_steps ADD COLUMN image_url TEXT;

-- Public bucket for Brainfence's own step images (uploaded by
-- dev/routine-images). Anyone can read; only the service role writes.
INSERT INTO storage.buckets (id, name, public)
VALUES ('routine-images', 'routine-images', true)
ON CONFLICT (id) DO NOTHING;
