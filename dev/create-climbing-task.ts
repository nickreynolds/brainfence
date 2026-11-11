// Creates the Monday "Climb or hangboard" task for the main account: a workout
// (dev/climbing-program.ts: Beastmaker 2000 Repeaters plus a pinch block) that also
// completes by spending 30 minutes at the climbing gym, carries over until
// done, and blocks at home Mon–Fri once it's overdue.
//
//   npx tsx dev/create-climbing-task.ts            dry run: print what would be written
//   npx tsx dev/create-climbing-task.ts --apply    create the task and its steps
//   npx tsx dev/create-climbing-task.ts --apply --add-to-rule
//                                                  …and add it to the "Block" rule
//
// Needs the 20261109000001 migration (carry_over, completion_alternatives,
// routine_steps.image_url). Re-running is safe: an existing task with this
// title is left alone.

import { createClient } from "@supabase/supabase-js";
import * as path from "path";
import * as dotenv from "dotenv";
import { randomUUID } from "crypto";
import { CLIMBING_PROGRAM } from "./climbing-program";

dotenv.config({ path: path.resolve(__dirname, "../.env") });

const USER_EMAIL = "test@brainfence.dev";
const TITLE = "Climb or hangboard";
const RULE_NAME = "Block";
const apply = process.argv.includes("--apply");
const addToRule = process.argv.includes("--add-to-rule");

const GYM = { latitude: 40.70692050156327, longitude: -73.92399868732575 };

async function main() {
  const db = createClient(process.env.SUPABASE_URL!, process.env.SUPABASE_SECRET_KEY!, {
    auth: { autoRefreshToken: false, persistSession: false },
  });
  const { data: users, error: usersError } = await db.auth.admin.listUsers();
  if (usersError) throw usersError;
  const user = users.users.find((u) => u.email === USER_EMAIL);
  if (!user) throw new Error(`No user ${USER_EMAIL}`);

  const { data: existing } = await db.from("tasks").select("id").eq("user_id", user.id).eq("title", TITLE).neq("status", "archived");
  let taskId = existing?.[0]?.id as string | undefined;

  const now = new Date().toISOString();
  const task = {
    id: randomUUID(),
    user_id: user.id,
    title: TITLE,
    description: "Bouldering gym on Monday, or the full hangboard workout at home.",
    task_type: "workout",
    status: "active",
    recurrence_type: "weekly",
    recurrence_config: { day: "mon" },
    carry_over: true,
    verification_type: null,
    verification_config: { execution_mode: "automatic", countdown_seconds: 5 },
    completion_alternatives: [
      {
        verification_type: "gps",
        label: "Climbing gym",
        verification_config: { ...GYM, radius_meters: 150, mode: "enter", min_duration_m: 30 },
      },
    ],
    tags: [],
    sort_order: 0,
    is_blocking_condition: true,
    blocking_rule_ids: [],
    available_from: "12:00",
    due_at: "21:00",
    home_only_blocking: true,
    blocking_days_of_week: ["mon", "tue", "wed", "thu", "fri"],
    created_at: now,
    updated_at: now,
  };
  // Images are uploaded and linked by dev/update-climbing-task.ts.
  const stepRows = CLIMBING_PROGRAM.map((s, i) => ({
    id: randomUUID(),
    user_id: user.id,
    task_id: task.id,
    title: s.title,
    step_order: i,
    step_type: "timed_sets",
    config: s.config,
    superset_group: null,
    description: s.description,
    image_url: null,
    created_at: now,
  }));

  if (taskId) {
    console.log(`"${TITLE}" already exists (${taskId}); not creating it again.`);
  } else if (!apply) {
    console.log("Dry run. Would create:\n", JSON.stringify(task, null, 2));
    console.log(stepRows.map((s) => `  ${s.step_order + 1}. ${s.title} ${JSON.stringify(s.config)}`).join("\n"));
  } else {
    const { error } = await db.from("tasks").insert(task);
    if (error) throw error;
    const { error: stepsError } = await db.from("routine_steps").insert(stepRows);
    if (stepsError) throw stepsError;
    taskId = task.id;
    console.log(`Created "${TITLE}" (${taskId}) with ${stepRows.length} steps.`);
  }

  if (!addToRule) return;
  const { data: rules, error: rulesError } = await db.from("blocking_rules")
    .select("id, condition_task_ids, condition_logic").eq("user_id", user.id).eq("name", RULE_NAME);
  if (rulesError) throw rulesError;
  const rule = rules?.[0];
  if (!rule) throw new Error(`No rule named ${RULE_NAME}`);
  // Adding a condition to an "all" rule only makes it stricter, so it applies
  // at once (shared/CONTRACTS.md: Rule edits and the time-lock).
  if (rule.condition_logic !== "all") throw new Error(`${RULE_NAME} isn't an "all" rule; adding a condition would loosen it`);
  const ids: string[] = rule.condition_task_ids ?? [];
  if (!taskId) {
    console.log(`Dry run. Would add the new task to "${RULE_NAME}".`);
  } else if (ids.includes(taskId)) {
    console.log(`Already a condition of "${RULE_NAME}".`);
  } else if (!apply) {
    console.log(`Dry run. Would add ${taskId} to "${RULE_NAME}".`);
  } else {
    const { error } = await db.from("blocking_rules")
      .update({ condition_task_ids: [...ids, taskId], updated_at: new Date().toISOString() }).eq("id", rule.id);
    if (error) throw error;
    console.log(`Added to "${RULE_NAME}".`);
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
