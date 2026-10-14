-- Contemplations: short passages (e.g. the Five Remembrances) delivered as
-- notifications at random times through the day.
--
--   contemplation_decks       a themed collection with its delivery behaviour
--   contemplations            the passages in a deck
--   contemplation_deliveries  one row per notification shown, with the user's
--                             response (e.g. affirmed). Title/body/attribution
--                             are snapshotted so history survives edits/deletes.
--
-- Scheduling (how many per day, the waking window) is device-local and lives
-- in the app, not here.

CREATE TABLE contemplation_decks (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  name           TEXT NOT NULL,
  description    TEXT,
  -- 'sequential': rotate through items in order across deliveries
  -- 'random':     any item, avoiding an immediate repeat
  ordering       TEXT NOT NULL DEFAULT 'sequential' CHECK (ordering IN ('sequential', 'random')),
  -- What the notification asks of the user. 'none' = just read it;
  -- 'affirm' = an action button (label in response_label) that is logged.
  response_type  TEXT NOT NULL DEFAULT 'none' CHECK (response_type IN ('none', 'affirm')),
  response_label TEXT,
  -- Relative share of delivery slots when several decks are enabled.
  weight         INTEGER NOT NULL DEFAULT 1 CHECK (weight > 0),
  is_enabled     BOOLEAN NOT NULL DEFAULT true,
  -- Set when the deck was added from the app's built-in library, so the app
  -- knows which starter decks the user already has.
  library_key    TEXT,
  sort_order     INTEGER DEFAULT 0,
  created_at     TIMESTAMPTZ DEFAULT now(),
  updated_at     TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE contemplations (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  deck_id     UUID NOT NULL REFERENCES contemplation_decks(id) ON DELETE CASCADE,
  title       TEXT,
  body        TEXT NOT NULL,
  attribution TEXT,
  sort_order  INTEGER DEFAULT 0,
  created_at  TIMESTAMPTZ DEFAULT now(),
  updated_at  TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE contemplation_deliveries (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  deck_id          UUID REFERENCES contemplation_decks(id) ON DELETE SET NULL,
  contemplation_id UUID REFERENCES contemplations(id) ON DELETE SET NULL,
  title            TEXT,
  body             TEXT NOT NULL,
  attribution      TEXT,
  delivered_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  response         TEXT CHECK (response IN ('affirmed')),
  responded_at     TIMESTAMPTZ,
  created_at       TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_contemplation_decks_user_id ON contemplation_decks(user_id);
CREATE INDEX idx_contemplations_user_id ON contemplations(user_id);
CREATE INDEX idx_contemplations_deck_id ON contemplations(deck_id);
CREATE INDEX idx_contemplation_deliveries_user_id ON contemplation_deliveries(user_id);
CREATE INDEX idx_contemplation_deliveries_delivered_at ON contemplation_deliveries(user_id, delivered_at DESC);

CREATE TRIGGER trg_contemplation_decks_updated_at
  BEFORE UPDATE ON contemplation_decks
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_contemplations_updated_at
  BEFORE UPDATE ON contemplations
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE contemplation_decks ENABLE ROW LEVEL SECURITY;
ALTER TABLE contemplations ENABLE ROW LEVEL SECURITY;
ALTER TABLE contemplation_deliveries ENABLE ROW LEVEL SECURITY;

CREATE POLICY contemplation_decks_select ON contemplation_decks FOR SELECT USING (user_id = auth.uid());
CREATE POLICY contemplation_decks_insert ON contemplation_decks FOR INSERT WITH CHECK (user_id = auth.uid());
CREATE POLICY contemplation_decks_update ON contemplation_decks FOR UPDATE USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
CREATE POLICY contemplation_decks_delete ON contemplation_decks FOR DELETE USING (user_id = auth.uid());

CREATE POLICY contemplations_select ON contemplations FOR SELECT USING (user_id = auth.uid());
CREATE POLICY contemplations_insert ON contemplations FOR INSERT WITH CHECK (user_id = auth.uid());
CREATE POLICY contemplations_update ON contemplations FOR UPDATE USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
CREATE POLICY contemplations_delete ON contemplations FOR DELETE USING (user_id = auth.uid());

CREATE POLICY contemplation_deliveries_select ON contemplation_deliveries FOR SELECT USING (user_id = auth.uid());
CREATE POLICY contemplation_deliveries_insert ON contemplation_deliveries FOR INSERT WITH CHECK (user_id = auth.uid());
CREATE POLICY contemplation_deliveries_update ON contemplation_deliveries FOR UPDATE USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
CREATE POLICY contemplation_deliveries_delete ON contemplation_deliveries FOR DELETE USING (user_id = auth.uid());

-- If the PowerSync publication enumerates tables explicitly (not FOR ALL TABLES),
-- the new tables must be added for logical replication to pick them up.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'powersync' AND NOT puballtables) THEN
    ALTER PUBLICATION powersync ADD TABLE contemplation_decks, contemplations, contemplation_deliveries;
  END IF;
END $$;
