import { createClient } from "@supabase/supabase-js";
import * as fs from "fs";
import * as path from "path";
import * as dotenv from "dotenv";

dotenv.config({ path: path.resolve(__dirname, "../.env") });

const SUPABASE_URL = process.env.SUPABASE_URL!;
const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY!;
const USER_EMAIL = process.env.BOOTSTRAP_USER_EMAIL!;
const USER_PASSWORD = process.env.BOOTSTRAP_USER_PASSWORD!;

if (!SUPABASE_URL || !SUPABASE_SERVICE_ROLE_KEY || !USER_EMAIL || !USER_PASSWORD) {
  console.error("Missing required env vars: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, BOOTSTRAP_USER_EMAIL, BOOTSTRAP_USER_PASSWORD");
  process.exit(1);
}

// Service role client for admin operations (create user, bypass RLS for upserts)
const adminClient = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
  auth: { autoRefreshToken: false, persistSession: false },
});

interface Step {
  title: string;
  step_type: string;
  step_order: number;
  config: Record<string, unknown>;
}

interface Task {
  title: string;
  task_type: string;
  verification_type?: string;
  verification_config?: Record<string, unknown>;
  recurrence_type?: string;
  recurrence_config?: Record<string, unknown>;
  is_blocking_condition?: boolean;
  group?: string;
  sort_order?: number;
  steps?: Step[];
}

interface BlockingRule {
  name: string;
  blocked_apps: unknown[];
  blocked_domains: string[];
  condition_task_titles: string[];
  condition_logic: string;
  active_schedule?: Record<string, unknown>;
}

interface BootstrapData {
  user_email: string;
  groups: Array<{ name: string; color?: string; icon?: string; sort_order?: number }>;
  tasks: Task[];
  blocking_rules: BlockingRule[];
}

async function main() {
  const data: BootstrapData = JSON.parse(
    fs.readFileSync(path.resolve(__dirname, "bootstrap.json"), "utf8")
  );

  // ── 1. Ensure test user exists ──────────────────────────────────────────────
  console.log(`\n→ Ensuring user: ${USER_EMAIL}`);

  const { data: userList } = await adminClient.auth.admin.listUsers();
  let userId: string | undefined = userList?.users.find(
    (u) => u.email === USER_EMAIL
  )?.id;

  if (!userId) {
    const { data: created, error } = await adminClient.auth.admin.createUser({
      email: USER_EMAIL,
      password: USER_PASSWORD,
      email_confirm: true,
    });
    if (error) throw new Error(`Failed to create user: ${error.message}`);
    userId = created.user.id;
    console.log(`  Created user ${userId}`);
  } else {
    console.log(`  Found existing user ${userId}`);
  }

  // ── 2. Upsert groups ────────────────────────────────────────────────────────
  console.log("\n→ Upserting groups...");
  const groupIdByName: Record<string, string> = {};

  for (const group of data.groups) {
    const { data: existing } = await adminClient
      .from("groups")
      .select("id")
      .eq("user_id", userId)
      .eq("name", group.name)
      .maybeSingle();

    if (existing) {
      groupIdByName[group.name] = existing.id;
      console.log(`  Existing: ${group.name} (${existing.id})`);
    } else {
      const { data: inserted, error } = await adminClient
        .from("groups")
        .insert({ ...group, user_id: userId })
        .select("id")
        .single();
      if (error) throw new Error(`Failed to insert group "${group.name}": ${error.message}`);
      groupIdByName[group.name] = inserted.id;
      console.log(`  Created:  ${group.name} (${inserted.id})`);
    }
  }

  // ── 3. Upsert tasks + routine steps ────────────────────────────────────────
  console.log("\n→ Upserting tasks...");
  const taskIdByTitle: Record<string, string> = {};

  for (const task of data.tasks) {
    const { steps, group, ...taskFields } = task;

    const row = {
      ...taskFields,
      user_id: userId,
      group_id: group ? groupIdByName[group] ?? null : null,
    };

    const { data: existing } = await adminClient
      .from("tasks")
      .select("id")
      .eq("user_id", userId)
      .eq("title", task.title)
      .maybeSingle();

    let taskId: string;
    if (existing) {
      const { error } = await adminClient
        .from("tasks")
        .update(row)
        .eq("id", existing.id);
      if (error) throw new Error(`Failed to update task "${task.title}": ${error.message}`);
      taskId = existing.id;
      console.log(`  Updated:  ${task.title} (${taskId})`);
    } else {
      const { data: inserted, error } = await adminClient
        .from("tasks")
        .insert(row)
        .select("id")
        .single();
      if (error) throw new Error(`Failed to insert task "${task.title}": ${error.message}`);
      taskId = inserted.id;
      console.log(`  Created:  ${task.title} (${taskId})`);
    }

    taskIdByTitle[task.title] = taskId;

    // Upsert routine steps if present
    if (steps && steps.length > 0) {
      // Delete existing steps and re-insert to handle reordering
      await adminClient.from("routine_steps").delete().eq("task_id", taskId);
      const { error } = await adminClient.from("routine_steps").insert(
        steps.map((s) => ({ ...s, task_id: taskId }))
      );
      if (error) throw new Error(`Failed to insert steps for "${task.title}": ${error.message}`);
      console.log(`    └─ ${steps.length} steps upserted`);
    }
  }

  // ── 4. Upsert blocking rules ────────────────────────────────────────────────
  console.log("\n→ Upserting blocking rules...");

  for (const rule of data.blocking_rules) {
    const { condition_task_titles, ...ruleFields } = rule;

    // Resolve task titles → IDs
    const condition_task_ids = condition_task_titles.map((title) => {
      const id = taskIdByTitle[title];
      if (!id) throw new Error(`Blocking rule "${rule.name}" references unknown task: "${title}"`);
      return id;
    });

    const row = { ...ruleFields, condition_task_ids, user_id: userId };

    const { data: existing } = await adminClient
      .from("blocking_rules")
      .select("id")
      .eq("user_id", userId)
      .eq("name", rule.name)
      .maybeSingle();

    if (existing) {
      const { error } = await adminClient
        .from("blocking_rules")
        .update(row)
        .eq("id", existing.id);
      if (error) throw new Error(`Failed to update rule "${rule.name}": ${error.message}`);
      console.log(`  Updated:  ${rule.name}`);
    } else {
      const { error } = await adminClient
        .from("blocking_rules")
        .insert(row);
      if (error) throw new Error(`Failed to insert rule "${rule.name}": ${error.message}`);
      console.log(`  Created:  ${rule.name}`);
    }
  }

  console.log("\n✓ Bootstrap complete\n");
}

main().catch((err) => {
  console.error("\n✗", err.message);
  process.exit(1);
});
