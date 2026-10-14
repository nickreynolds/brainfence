-- Reports: short self-reports (habits, mood) collected for later analysis.
--
--   metrics             something tracked over time ("Anxiety", "Nicotine"),
--                       shared across reports so every report that asks it
--                       feeds one continuous series
--   metric_options      a metric's 2–5 answer choices, in order. 'ordinal'
--                       metrics are ranked (none < moderate < a lot, 1..5);
--                       'categorical' ones aren't (none / patch / vape)
--   reports             a reusable set of metrics ("Mood", "Habits"), used by a
--                       task (task_type 'report') or a routine step (step_type
--                       'report') via {"report_id": ...} in their config
--   report_metrics      which metrics a report asks, in order
--   report_submissions  one filled-in report, with an optional note
--   metric_entries      one answer. Metric name, option label, position and
--                       option count are snapshotted so renaming, reordering or
--                       deleting options never rewrites history.

CREATE TABLE metrics (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  name        TEXT NOT NULL,
  description TEXT,
  kind        TEXT NOT NULL DEFAULT 'ordinal' CHECK (kind IN ('ordinal', 'categorical')),
  created_at  TIMESTAMPTZ DEFAULT now(),
  updated_at  TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE metric_options (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  metric_id  UUID NOT NULL REFERENCES metrics(id) ON DELETE CASCADE,
  label      TEXT NOT NULL,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ DEFAULT now(),
  updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE reports (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  name         TEXT NOT NULL,
  include_note BOOLEAN NOT NULL DEFAULT true,
  -- Set when added from the app's starter library.
  library_key  TEXT,
  created_at   TIMESTAMPTZ DEFAULT now(),
  updated_at   TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE report_metrics (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  report_id  UUID NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
  metric_id  UUID NOT NULL REFERENCES metrics(id) ON DELETE CASCADE,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE report_submissions (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  report_id       UUID REFERENCES reports(id) ON DELETE SET NULL,
  -- Where it was filled in: a report task, or a report step of a routine
  -- (then task_id is the routine and routine_step_id the step).
  task_id         UUID REFERENCES tasks(id) ON DELETE SET NULL,
  routine_step_id UUID REFERENCES routine_steps(id) ON DELETE SET NULL,
  report_name     TEXT NOT NULL,
  note            TEXT,
  submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE metric_entries (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  submission_id UUID NOT NULL REFERENCES report_submissions(id) ON DELETE CASCADE,
  metric_id     UUID REFERENCES metrics(id) ON DELETE SET NULL,
  option_id     UUID REFERENCES metric_options(id) ON DELETE SET NULL,
  metric_name   TEXT NOT NULL,
  metric_kind   TEXT NOT NULL CHECK (metric_kind IN ('ordinal', 'categorical')),
  option_label  TEXT NOT NULL,
  option_index  INTEGER NOT NULL,  -- 0-based position among the options
  option_count  INTEGER NOT NULL,  -- how many options the metric had
  position      INTEGER NOT NULL,  -- the metric's place in the report when submitted
  recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at    TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_metrics_user_id ON metrics(user_id);
CREATE INDEX idx_metric_options_metric_id ON metric_options(metric_id);
CREATE INDEX idx_reports_user_id ON reports(user_id);
CREATE INDEX idx_report_metrics_report_id ON report_metrics(report_id);
CREATE INDEX idx_report_metrics_metric_id ON report_metrics(metric_id);
CREATE INDEX idx_report_submissions_user_submitted ON report_submissions(user_id, submitted_at DESC);
CREATE INDEX idx_metric_entries_submission_id ON metric_entries(submission_id);
CREATE INDEX idx_metric_entries_metric_recorded ON metric_entries(metric_id, recorded_at);

CREATE TRIGGER trg_metrics_updated_at
  BEFORE UPDATE ON metrics FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_metric_options_updated_at
  BEFORE UPDATE ON metric_options FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_reports_updated_at
  BEFORE UPDATE ON reports FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DO $$
DECLARE
  t TEXT;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'metrics', 'metric_options', 'reports', 'report_metrics',
    'report_submissions', 'metric_entries'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY %I ON %I FOR SELECT USING (user_id = auth.uid())', t || '_select', t);
    EXECUTE format('CREATE POLICY %I ON %I FOR INSERT WITH CHECK (user_id = auth.uid())', t || '_insert', t);
    EXECUTE format('CREATE POLICY %I ON %I FOR UPDATE USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid())', t || '_update', t);
    EXECUTE format('CREATE POLICY %I ON %I FOR DELETE USING (user_id = auth.uid())', t || '_delete', t);
  END LOOP;
END $$;

-- If the PowerSync publication enumerates tables explicitly (not FOR ALL TABLES),
-- the new tables must be added for logical replication to pick them up.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'powersync' AND NOT puballtables) THEN
    ALTER PUBLICATION powersync ADD TABLE
      metrics, metric_options, reports, report_metrics, report_submissions, metric_entries;
  END IF;
END $$;
