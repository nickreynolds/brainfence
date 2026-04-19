-- Add superset_group to routine_steps for grouping exercises into supersets.
-- Steps sharing the same non-null superset_group value are performed together in rounds.
ALTER TABLE routine_steps ADD COLUMN superset_group TEXT;
