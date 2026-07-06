-- 009_add_shopping_items.sql
-- Shopping list items: one-shot sub-tasks of a task_type='shopping' task.
-- Unlike routine_steps (per-occurrence templates), a shopping item is bought
-- once: completed_at is set and the item disappears from the open list.
-- The parent shopping task itself is never completed.

CREATE TABLE shopping_items (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  task_id      UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  title        TEXT NOT NULL,
  sort_order   INTEGER DEFAULT 0,
  completed_at TIMESTAMPTZ,
  created_at   TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_shopping_items_user_id ON shopping_items(user_id);
CREATE INDEX idx_shopping_items_task_id ON shopping_items(task_id);

ALTER TABLE shopping_items ENABLE ROW LEVEL SECURITY;

CREATE POLICY shopping_items_select ON shopping_items FOR SELECT USING (user_id = auth.uid());
CREATE POLICY shopping_items_insert ON shopping_items FOR INSERT WITH CHECK (user_id = auth.uid());
CREATE POLICY shopping_items_update ON shopping_items FOR UPDATE USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());
CREATE POLICY shopping_items_delete ON shopping_items FOR DELETE USING (user_id = auth.uid());

-- If the PowerSync publication enumerates tables explicitly (not FOR ALL TABLES),
-- the new table must be added for logical replication to pick it up.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'powersync' AND NOT puballtables) THEN
    ALTER PUBLICATION powersync ADD TABLE shopping_items;
  END IF;
END $$;
