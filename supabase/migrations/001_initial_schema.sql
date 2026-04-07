-- 001_initial_schema.sql
-- Core tables for Brainfence

-- ============================================================
-- groups
-- ============================================================
CREATE TABLE groups (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id             UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  name                TEXT NOT NULL,
  color               TEXT,
  icon                TEXT,
  visibility_schedule JSONB,
  sort_order          INTEGER DEFAULT 0,
  created_at          TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_groups_user_id ON groups(user_id);

-- ============================================================
-- tasks
-- ============================================================
CREATE TABLE tasks (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id               UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  title                 TEXT NOT NULL,
  description           TEXT,
  task_type             TEXT NOT NULL DEFAULT 'simple',
  status                TEXT NOT NULL DEFAULT 'active',

  recurrence_type       TEXT,
  recurrence_config     JSONB DEFAULT '{}',

  verification_type     TEXT DEFAULT 'manual',
  verification_config   JSONB DEFAULT '{}',

  tags                  TEXT[] DEFAULT '{}',
  group_id              UUID REFERENCES groups(id) ON DELETE SET NULL,
  sort_order            INTEGER DEFAULT 0,

  is_blocking_condition BOOLEAN DEFAULT false,
  blocking_rule_ids     UUID[] DEFAULT '{}',

  created_at            TIMESTAMPTZ DEFAULT now(),
  updated_at            TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_tasks_user_id   ON tasks(user_id);
CREATE INDEX idx_tasks_group_id  ON tasks(group_id);
CREATE INDEX idx_tasks_status    ON tasks(user_id, status);

-- ============================================================
-- routine_steps
-- ============================================================
CREATE TABLE routine_steps (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id     UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  title       TEXT NOT NULL,
  step_order  INTEGER NOT NULL,
  step_type   TEXT NOT NULL DEFAULT 'checkbox',
  config      JSONB DEFAULT '{}',
  created_at  TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_routine_steps_task_id ON routine_steps(task_id);

-- ============================================================
-- task_completions
-- ============================================================
CREATE TABLE task_completions (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id           UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  user_id           UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  completed_at      TIMESTAMPTZ DEFAULT now(),
  occurrence_date   TIMESTAMPTZ,
  verification_data JSONB DEFAULT '{}',
  created_at        TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_task_completions_task_id      ON task_completions(task_id);
CREATE INDEX idx_task_completions_user_id      ON task_completions(user_id);
CREATE INDEX idx_task_completions_completed_at ON task_completions(completed_at);
CREATE INDEX idx_task_completions_user_task    ON task_completions(user_id, task_id, completed_at DESC);

-- ============================================================
-- step_completions
-- ============================================================
CREATE TABLE step_completions (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_completion_id  UUID NOT NULL REFERENCES task_completions(id) ON DELETE CASCADE,
  routine_step_id     UUID NOT NULL REFERENCES routine_steps(id) ON DELETE CASCADE,
  set_number          INTEGER DEFAULT 1,
  data                JSONB NOT NULL DEFAULT '{}',
  completed_at        TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_step_completions_task_completion_id ON step_completions(task_completion_id);
CREATE INDEX idx_step_completions_routine_step_id    ON step_completions(routine_step_id);

-- ============================================================
-- blocking_rules
-- ============================================================
CREATE TABLE blocking_rules (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  name              TEXT NOT NULL,
  blocked_apps      JSONB DEFAULT '[]',
  blocked_domains   TEXT[] DEFAULT '{}',
  condition_task_ids UUID[] DEFAULT '{}',
  condition_logic   TEXT DEFAULT 'all',
  active_schedule   JSONB,
  config_lock_hours INTEGER DEFAULT 24,
  pending_changes   JSONB,
  changes_apply_at  TIMESTAMPTZ,
  is_active         BOOLEAN DEFAULT true,
  created_at        TIMESTAMPTZ DEFAULT now(),
  updated_at        TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_blocking_rules_user_id ON blocking_rules(user_id);

-- ============================================================
-- notes
-- ============================================================
CREATE TABLE notes (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  title          TEXT NOT NULL,
  content        TEXT DEFAULT '',
  tags           TEXT[] DEFAULT '{}',
  group_id       UUID REFERENCES groups(id) ON DELETE SET NULL,
  outgoing_links UUID[] DEFAULT '{}',
  created_at     TIMESTAMPTZ DEFAULT now(),
  updated_at     TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_notes_user_id  ON notes(user_id);
CREATE INDEX idx_notes_group_id ON notes(group_id);

-- ============================================================
-- updated_at triggers
-- ============================================================
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tasks_updated_at
  BEFORE UPDATE ON tasks
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_blocking_rules_updated_at
  BEFORE UPDATE ON blocking_rules
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_notes_updated_at
  BEFORE UPDATE ON notes
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
