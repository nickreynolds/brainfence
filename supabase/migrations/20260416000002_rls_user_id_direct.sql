-- 004_rls_user_id_direct.sql
-- Replace subquery-based RLS policies on routine_steps and step_completions
-- with direct user_id checks now that both tables have a user_id column.

-- ============================================================
-- routine_steps
-- ============================================================
DROP POLICY routine_steps_select ON routine_steps;
DROP POLICY routine_steps_insert ON routine_steps;
DROP POLICY routine_steps_update ON routine_steps;
DROP POLICY routine_steps_delete ON routine_steps;

CREATE POLICY routine_steps_select ON routine_steps FOR SELECT USING (user_id = auth.uid());
CREATE POLICY routine_steps_insert ON routine_steps FOR INSERT WITH CHECK (user_id = auth.uid());
CREATE POLICY routine_steps_update ON routine_steps FOR UPDATE USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
CREATE POLICY routine_steps_delete ON routine_steps FOR DELETE USING (user_id = auth.uid());

-- ============================================================
-- step_completions
-- ============================================================
DROP POLICY step_completions_select ON step_completions;
DROP POLICY step_completions_insert ON step_completions;
DROP POLICY step_completions_update ON step_completions;
DROP POLICY step_completions_delete ON step_completions;

CREATE POLICY step_completions_select ON step_completions FOR SELECT USING (user_id = auth.uid());
CREATE POLICY step_completions_insert ON step_completions FOR INSERT WITH CHECK (user_id = auth.uid());
CREATE POLICY step_completions_update ON step_completions FOR UPDATE USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
CREATE POLICY step_completions_delete ON step_completions FOR DELETE USING (user_id = auth.uid());
