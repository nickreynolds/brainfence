-- 002_rls_policies.sql
-- Enable RLS and add user-scoped policies for all tables

-- ============================================================
-- groups
-- ============================================================
ALTER TABLE groups ENABLE ROW LEVEL SECURITY;

CREATE POLICY groups_select ON groups FOR SELECT USING (user_id = auth.uid());
CREATE POLICY groups_insert ON groups FOR INSERT WITH CHECK (user_id = auth.uid());
CREATE POLICY groups_update ON groups FOR UPDATE USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
CREATE POLICY groups_delete ON groups FOR DELETE USING (user_id = auth.uid());

-- ============================================================
-- tasks
-- ============================================================
ALTER TABLE tasks ENABLE ROW LEVEL SECURITY;

CREATE POLICY tasks_select ON tasks FOR SELECT USING (user_id = auth.uid());
CREATE POLICY tasks_insert ON tasks FOR INSERT WITH CHECK (user_id = auth.uid());
CREATE POLICY tasks_update ON tasks FOR UPDATE USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
CREATE POLICY tasks_delete ON tasks FOR DELETE USING (user_id = auth.uid());

-- ============================================================
-- routine_steps  (no user_id — traverse via tasks)
-- ============================================================
ALTER TABLE routine_steps ENABLE ROW LEVEL SECURITY;

CREATE POLICY routine_steps_select ON routine_steps FOR SELECT
  USING (EXISTS (SELECT 1 FROM tasks WHERE tasks.id = routine_steps.task_id AND tasks.user_id = auth.uid()));

CREATE POLICY routine_steps_insert ON routine_steps FOR INSERT
  WITH CHECK (EXISTS (SELECT 1 FROM tasks WHERE tasks.id = routine_steps.task_id AND tasks.user_id = auth.uid()));

CREATE POLICY routine_steps_update ON routine_steps FOR UPDATE
  USING (EXISTS (SELECT 1 FROM tasks WHERE tasks.id = routine_steps.task_id AND tasks.user_id = auth.uid()))
  WITH CHECK (EXISTS (SELECT 1 FROM tasks WHERE tasks.id = routine_steps.task_id AND tasks.user_id = auth.uid()));

CREATE POLICY routine_steps_delete ON routine_steps FOR DELETE
  USING (EXISTS (SELECT 1 FROM tasks WHERE tasks.id = routine_steps.task_id AND tasks.user_id = auth.uid()));

-- ============================================================
-- task_completions
-- ============================================================
ALTER TABLE task_completions ENABLE ROW LEVEL SECURITY;

CREATE POLICY task_completions_select ON task_completions FOR SELECT USING (user_id = auth.uid());
CREATE POLICY task_completions_insert ON task_completions FOR INSERT WITH CHECK (user_id = auth.uid());
CREATE POLICY task_completions_update ON task_completions FOR UPDATE USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
CREATE POLICY task_completions_delete ON task_completions FOR DELETE USING (user_id = auth.uid());

-- ============================================================
-- step_completions  (no user_id — traverse via task_completions)
-- ============================================================
ALTER TABLE step_completions ENABLE ROW LEVEL SECURITY;

CREATE POLICY step_completions_select ON step_completions FOR SELECT
  USING (EXISTS (SELECT 1 FROM task_completions WHERE task_completions.id = step_completions.task_completion_id AND task_completions.user_id = auth.uid()));

CREATE POLICY step_completions_insert ON step_completions FOR INSERT
  WITH CHECK (EXISTS (SELECT 1 FROM task_completions WHERE task_completions.id = step_completions.task_completion_id AND task_completions.user_id = auth.uid()));

CREATE POLICY step_completions_update ON step_completions FOR UPDATE
  USING (EXISTS (SELECT 1 FROM task_completions WHERE task_completions.id = step_completions.task_completion_id AND task_completions.user_id = auth.uid()))
  WITH CHECK (EXISTS (SELECT 1 FROM task_completions WHERE task_completions.id = step_completions.task_completion_id AND task_completions.user_id = auth.uid()));

CREATE POLICY step_completions_delete ON step_completions FOR DELETE
  USING (EXISTS (SELECT 1 FROM task_completions WHERE task_completions.id = step_completions.task_completion_id AND task_completions.user_id = auth.uid()));

-- ============================================================
-- blocking_rules
-- ============================================================
ALTER TABLE blocking_rules ENABLE ROW LEVEL SECURITY;

CREATE POLICY blocking_rules_select ON blocking_rules FOR SELECT USING (user_id = auth.uid());
CREATE POLICY blocking_rules_insert ON blocking_rules FOR INSERT WITH CHECK (user_id = auth.uid());
CREATE POLICY blocking_rules_update ON blocking_rules FOR UPDATE USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
CREATE POLICY blocking_rules_delete ON blocking_rules FOR DELETE USING (user_id = auth.uid());

-- ============================================================
-- notes
-- ============================================================
ALTER TABLE notes ENABLE ROW LEVEL SECURITY;

CREATE POLICY notes_select ON notes FOR SELECT USING (user_id = auth.uid());
CREATE POLICY notes_insert ON notes FOR INSERT WITH CHECK (user_id = auth.uid());
CREATE POLICY notes_update ON notes FOR UPDATE USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
CREATE POLICY notes_delete ON notes FOR DELETE USING (user_id = auth.uid());
