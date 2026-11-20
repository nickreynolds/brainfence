// Brings the existing "Climb or hangboard" task's steps in line with
// dev/climbing-program.ts, in place: each step (by position) gets the
// program's title, notes and config, and its image is cleared (the apps draw
// the board from the config's holds). Step ids stay the same, so logged sets
// and last-time weights are kept. A step's `sets` is kept too: that's set from
// the app's "Before you start" screen.
//
//   npx tsx dev/update-climbing-task.ts            dry run: show what would change
//   npx tsx dev/update-climbing-task.ts --apply    update the steps

import { createClient } from "@supabase/supabase-js";
import * as path from "path";
import * as dotenv from "dotenv";
import { CLIMBING_PROGRAM } from "./climbing-program";

dotenv.config({ path: path.resolve(__dirname, "../.env") });

const USER_EMAIL = "test@brainfence.dev";
const TITLE = "Climb or hangboard";
const apply = process.argv.includes("--apply");

async function main() {
  const db = createClient(process.env.SUPABASE_URL!, process.env.SUPABASE_SECRET_KEY!, {
    auth: { autoRefreshToken: false, persistSession: false },
  });
  const { data: users, error: usersError } = await db.auth.admin.listUsers();
  if (usersError) throw usersError;
  const user = users.users.find((u) => u.email === USER_EMAIL);
  if (!user) throw new Error(`No user ${USER_EMAIL}`);

  const { data: tasks, error: taskError } = await db.from("tasks").select("id")
    .eq("user_id", user.id).eq("title", TITLE).neq("status", "archived");
  if (taskError) throw taskError;
  const taskId = tasks?.[0]?.id as string | undefined;
  if (!taskId) throw new Error(`No "${TITLE}" task; create it with dev/create-climbing-task.ts`);

  const { data: steps, error: stepsError } = await db.from("routine_steps")
    .select("id, title, step_order, config").eq("task_id", taskId).order("step_order");
  if (stepsError) throw stepsError;
  if ((steps ?? []).length !== CLIMBING_PROGRAM.length) {
    throw new Error(`The task has ${steps?.length ?? 0} steps and the program ${CLIMBING_PROGRAM.length}; update it by hand in the app.`);
  }

  const updates = CLIMBING_PROGRAM.map((p, i) => {
    const current = steps![i];
    const old = (current.config ?? {}) as Record<string, unknown>;
    const config = { ...p.config, ...(typeof old.sets === "number" ? { sets: old.sets } : {}) };
    return { id: current.id, oldTitle: current.title, title: p.title, description: p.description, config };
  });

  console.log(`${apply ? "Updating" : "Would update"} ${updates.length} step(s):`);
  for (const u of updates) {
    console.log(`  ${u.oldTitle === u.title ? u.title : `${u.oldTitle} → ${u.title}`}\n     ${JSON.stringify(u.config)}`);
  }
  if (!apply) {
    console.log("\nDry run; nothing changed. Re-run with --apply.");
    return;
  }

  for (const u of updates) {
    const { error } = await db.from("routine_steps")
      .update({ title: u.title, description: u.description, config: u.config, image_url: null }).eq("id", u.id);
    if (error) throw error;
  }
  await db.from("tasks").update({ updated_at: new Date().toISOString() }).eq("id", taskId);
  console.log(`Done: "${TITLE}" updated in place.`);
}

main().catch((e) => {
  console.error(e instanceof Error ? e.message : e);
  process.exit(1);
});
