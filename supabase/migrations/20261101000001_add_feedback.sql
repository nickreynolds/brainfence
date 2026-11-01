-- Feedback: notes users send from the app (Settings → Send feedback, and the
-- setup guide's last page). See shared/CONTRACTS.md (Feedback).
--
-- Clients write it through an insert-only PowerSync table: a note is queued
-- locally (so it can be sent offline) and uploaded, but never synced back down,
-- so there is no sync rule for it. Read it from the Supabase dashboard.
--
--   message      what the user wrote: trimmed, 1–4000 characters
--   platform     'android', 'windows' or 'macos'
--   app_version  the sending app's version name
--   source       where it was sent from: 'settings' or 'setup_guide'

CREATE TABLE feedback (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  message     TEXT NOT NULL CHECK (char_length(message) BETWEEN 1 AND 4000),
  platform    TEXT NOT NULL CHECK (platform IN ('android', 'windows', 'macos')),
  app_version TEXT,
  source      TEXT NOT NULL CHECK (source IN ('settings', 'setup_guide')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_feedback_created_at ON feedback(created_at DESC);

-- Uploads are upserts, which Postgres checks against the select and update
-- policies too, so these match every other table's.
ALTER TABLE feedback ENABLE ROW LEVEL SECURITY;
CREATE POLICY feedback_select ON feedback FOR SELECT USING (user_id = auth.uid());
CREATE POLICY feedback_insert ON feedback FOR INSERT WITH CHECK (user_id = auth.uid());
CREATE POLICY feedback_update ON feedback FOR UPDATE USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
CREATE POLICY feedback_delete ON feedback FOR DELETE USING (user_id = auth.uid());
