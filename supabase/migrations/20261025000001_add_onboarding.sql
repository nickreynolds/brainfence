-- Onboarding: the first-time setup guide, tracked per account so finishing it
-- on one device carries over to the others. See shared/CONTRACTS.md.
--
-- One row per user, keyed by the user's own id (id = user_id), so two devices
-- that both finish the guide offline upsert the same row instead of creating
-- duplicates.
--
--   status              'completed' (finished the guide) or 'dismissed'
--                       (skipped it, or an existing account that never saw it)
--   deferred_steps      starters chosen on a device without location (the Mac):
--                       [{"key": "morning_walk", "available_from": "06:00",
--                         "due_at": "11:00"}, {"key": "groceries"}]. A device
--                       with location finishes them and clears the list.
--   blocking_rule_id    the rule the guide created, so other devices can add
--   meditation_task_id  their own platform's apps to it and its companion apps
--   <platform>_setup_at when that platform last finished its device setup
--                       (blocked apps, companion apps, home location)

CREATE TABLE onboarding (
  id                 UUID PRIMARY KEY,
  user_id            UUID NOT NULL UNIQUE REFERENCES auth.users(id) ON DELETE CASCADE,
  status             TEXT NOT NULL CHECK (status IN ('completed', 'dismissed')),
  deferred_steps     JSONB NOT NULL DEFAULT '[]',
  blocking_rule_id   UUID REFERENCES blocking_rules(id) ON DELETE SET NULL,
  meditation_task_id UUID REFERENCES tasks(id) ON DELETE SET NULL,
  android_setup_at   TIMESTAMPTZ,
  windows_setup_at   TIMESTAMPTZ,
  macos_setup_at     TIMESTAMPTZ,
  completed_at       TIMESTAMPTZ,
  created_at         TIMESTAMPTZ DEFAULT now(),
  updated_at         TIMESTAMPTZ DEFAULT now(),
  CONSTRAINT onboarding_one_row_per_user CHECK (id = user_id)
);

ALTER TABLE onboarding ENABLE ROW LEVEL SECURITY;
CREATE POLICY onboarding_select ON onboarding FOR SELECT USING (user_id = auth.uid());
CREATE POLICY onboarding_insert ON onboarding FOR INSERT WITH CHECK (user_id = auth.uid());
CREATE POLICY onboarding_update ON onboarding FOR UPDATE USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
CREATE POLICY onboarding_delete ON onboarding FOR DELETE USING (user_id = auth.uid());

-- If the PowerSync publication enumerates tables explicitly (not FOR ALL TABLES),
-- the new table must be added for logical replication to pick it up.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'powersync' AND NOT puballtables) THEN
    ALTER PUBLICATION powersync ADD TABLE onboarding;
  END IF;
END $$;
