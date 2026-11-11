// Brings the existing "Climb or hangboard" task's steps in line with
// dev/climbing-program.ts and uploads the hold images it uses.
//
//   node dev/routine-images/render.mjs             (re)draw the PNGs first
//   npx tsx dev/update-climbing-task.ts            dry run: show what would change
//   npx tsx dev/update-climbing-task.ts --apply    upload images, replace the steps
//
// Steps are replaced, and step completions cascade-delete with their step, so
// this refuses to run once the workout has been logged unless --force is
// given (the logged weights would be lost).

import { createClient } from "@supabase/supabase-js";
import * as fs from "fs";
import * as path from "path";
import * as dotenv from "dotenv";
import { randomUUID } from "crypto";
import { CLIMBING_PROGRAM, IMAGE_VERSION, ROUTINE_IMAGES_BUCKET } from "./climbing-program";

dotenv.config({ path: path.resolve(__dirname, "../.env") });

const USER_EMAIL = "test@brainfence.dev";
const TITLE = "Climb or hangboard";
const apply = process.argv.includes("--apply");
const force = process.argv.includes("--force");
const pngDir = path.join(__dirname, "routine-images", "png");

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

  const { data: oldSteps, error: stepsError } = await db.from("routine_steps").select("id, title").eq("task_id", taskId);
  if (stepsError) throw stepsError;
  const { count: logged, error: countError } = await db.from("step_completions")
    .select("id", { count: "exact", head: true }).in("routine_step_id", (oldSteps ?? []).map((s) => s.id));
  if (countError) throw countError;
  if ((logged ?? 0) > 0 && !force) {
    throw new Error(`${logged} logged set(s) would be deleted with the old steps. Re-run with --force to replace them anyway.`);
  }

  // Images
  const urls: Record<string, string> = {};
  for (const image of new Set(CLIMBING_PROGRAM.flatMap((s) => (s.image ? [s.image] : [])))) {
    const file = path.join(pngDir, `${image}.png`);
    if (!fs.existsSync(file)) throw new Error(`Missing ${file}: run node dev/routine-images/render.mjs`);
    const objectPath = `bm2000/${IMAGE_VERSION}/${image}.png`;
    urls[image] = db.storage.from(ROUTINE_IMAGES_BUCKET).getPublicUrl(objectPath).data.publicUrl;
    if (apply) {
      const { error } = await db.storage.from(ROUTINE_IMAGES_BUCKET)
        .upload(objectPath, fs.readFileSync(file), { contentType: "image/png", upsert: true, cacheControl: "31536000" });
      if (error) throw error;
      console.log(`Uploaded ${objectPath}`);
    }
  }

  const now = new Date().toISOString();
  const newSteps = CLIMBING_PROGRAM.map((s, i) => ({
    id: randomUUID(),
    user_id: user.id,
    task_id: taskId,
    title: s.title,
    step_order: i,
    step_type: "timed_sets",
    config: s.config,
    superset_group: null,
    description: s.description,
    image_url: s.image ? urls[s.image] : null,
    created_at: now,
  }));

  console.log(`${apply ? "Replacing" : "Would replace"} ${oldSteps?.length ?? 0} step(s) with:`);
  for (const s of newSteps) console.log(`  ${s.step_order + 1}. ${s.title} ${JSON.stringify(s.config)}${s.image_url ? `\n     ${s.image_url}` : ""}`);
  if (!apply) {
    console.log("\nDry run; nothing changed. Re-run with --apply.");
    return;
  }

  const { error: deleteError } = await db.from("routine_steps").delete().eq("task_id", taskId);
  if (deleteError) throw deleteError;
  const { error: insertError } = await db.from("routine_steps").insert(newSteps);
  if (insertError) throw insertError;
  await db.from("tasks").update({ updated_at: now }).eq("id", taskId);
  console.log(`Done: "${TITLE}" now has ${newSteps.length} steps.`);
}

main().catch((e) => {
  console.error(e instanceof Error ? e.message : e);
  process.exit(1);
});
