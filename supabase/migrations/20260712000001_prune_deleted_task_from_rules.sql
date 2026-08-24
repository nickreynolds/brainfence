-- Keep blocking_rules.condition_task_ids free of dangling references.
--
-- A blocking rule references its condition tasks by id in the UUID[] column
-- condition_task_ids. Nothing previously removed an id from that array when the
-- task itself was deleted, leaving a rule pointing at a task that no longer
-- exists. The client evaluator now fails closed on an unresolvable condition
-- task (so blocking never silently lifts) — which means a dangling reference
-- would make the rule block forever with no way to satisfy it.
--
-- This trigger removes a task's id from every rule that references it at delete
-- time, regardless of which client issued the delete. The change to
-- blocking_rules replicates back down through PowerSync, so devices re-evaluate
-- against the pruned rule automatically.

CREATE OR REPLACE FUNCTION prune_deleted_task_from_rules()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  UPDATE blocking_rules
     SET condition_task_ids = array_remove(condition_task_ids, OLD.id)
   WHERE user_id = OLD.user_id
     AND OLD.id = ANY(condition_task_ids);
  RETURN OLD;
END;
$$;

CREATE TRIGGER trg_prune_deleted_task_from_rules
  AFTER DELETE ON tasks
  FOR EACH ROW EXECUTE FUNCTION prune_deleted_task_from_rules();

-- One-time backfill: strip any references that are already dangling (tasks that
-- were deleted before this trigger existed). Only touches rows that actually
-- contain a dangling id.
UPDATE blocking_rules br
   SET condition_task_ids = COALESCE(
         (SELECT array_agg(tid)
            FROM unnest(br.condition_task_ids) AS tid
           WHERE EXISTS (SELECT 1 FROM tasks t WHERE t.id = tid)),
         '{}'::uuid[]
       )
 WHERE EXISTS (
         SELECT 1
           FROM unnest(br.condition_task_ids) AS tid
          WHERE NOT EXISTS (SELECT 1 FROM tasks t WHERE t.id = tid)
       );
