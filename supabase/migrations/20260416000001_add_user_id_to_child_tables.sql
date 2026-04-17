-- 003_add_user_id_to_child_tables.sql
-- Add user_id to routine_steps and step_completions so PowerSync can
-- sync them without JOINs (PowerSync requires single-table SELECTs).

-- ============================================================
-- routine_steps — derive user_id from tasks
-- ============================================================
ALTER TABLE routine_steps
  ADD COLUMN user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE;

UPDATE routine_steps
   SET user_id = t.user_id
  FROM tasks t
 WHERE routine_steps.task_id = t.id;

ALTER TABLE routine_steps
  ALTER COLUMN user_id SET NOT NULL;

CREATE INDEX idx_routine_steps_user_id ON routine_steps(user_id);

-- ============================================================
-- step_completions — derive user_id from task_completions
-- ============================================================
ALTER TABLE step_completions
  ADD COLUMN user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE;

UPDATE step_completions
   SET user_id = tc.user_id
  FROM task_completions tc
 WHERE step_completions.task_completion_id = tc.id;

ALTER TABLE step_completions
  ALTER COLUMN user_id SET NOT NULL;

CREATE INDEX idx_step_completions_user_id ON step_completions(user_id);
