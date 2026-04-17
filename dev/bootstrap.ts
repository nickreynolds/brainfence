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

  // ── 1. Delete existing user (cascades to all data) ─────────────────────────
  console.log(`\n→ Cleaning up existing user: ${USER_EMAIL}`);

  const { data: userList } = await adminClient.auth.admin.listUsers();
  const existingUserId = userList?.users.find(
    (u) => u.email === USER_EMAIL
  )?.id;

  if (existingUserId) {
    const { error } = await adminClient.auth.admin.deleteUser(existingUserId);
    if (error) throw new Error(`Failed to delete user: ${error.message}`);
    console.log(`  Deleted user ${existingUserId} (all data cascaded)`);
  } else {
    console.log("  No existing user found");
  }

  // ── 2. Create fresh user ───────────────────────────────────────────────────
  console.log(`\n→ Creating user: ${USER_EMAIL}`);

  const { data: created, error: createErr } = await adminClient.auth.admin.createUser({
    email: USER_EMAIL,
    password: USER_PASSWORD,
    email_confirm: true,
  });
  if (createErr) throw new Error(`Failed to create user: ${createErr.message}`);
  const userId = created.user.id;
  console.log(`  Created user ${userId}`);

  // ── 3. Insert groups ───────────────────────────────────────────────────────
  console.log("\n→ Inserting groups...");
  const groupIdByName: Record<string, string> = {};

  for (const group of data.groups) {
    const { data: inserted, error } = await adminClient
      .from("groups")
      .insert({ ...group, user_id: userId })
      .select("id")
      .single();
    if (error) throw new Error(`Failed to insert group "${group.name}": ${error.message}`);
    groupIdByName[group.name] = inserted.id;
    console.log(`  Created: ${group.name} (${inserted.id})`);
  }

  // ── 4. Insert tasks + routine steps ────────────────────────────────────────
  console.log("\n→ Inserting tasks...");
  const taskIdByTitle: Record<string, string> = {};

  for (const task of data.tasks) {
    const { steps, group, ...taskFields } = task;

    const row = {
      ...taskFields,
      user_id: userId,
      group_id: group ? groupIdByName[group] ?? null : null,
    };

    const { data: inserted, error } = await adminClient
      .from("tasks")
      .insert(row)
      .select("id")
      .single();
    if (error) throw new Error(`Failed to insert task "${task.title}": ${error.message}`);
    const taskId = inserted.id;
    taskIdByTitle[task.title] = taskId;
    console.log(`  Created: ${task.title} (${taskId})`);

    if (steps && steps.length > 0) {
      const { error: stepErr } = await adminClient.from("routine_steps").insert(
        steps.map((s) => ({ ...s, task_id: taskId, user_id: userId }))
      );
      if (stepErr) throw new Error(`Failed to insert steps for "${task.title}": ${stepErr.message}`);
      console.log(`    └─ ${steps.length} steps inserted`);
    }
  }

  // ── 5. Insert blocking rules ───────────────────────────────────────────────
  console.log("\n→ Inserting blocking rules...");

  for (const rule of data.blocking_rules) {
    const { condition_task_titles, ...ruleFields } = rule;

    const condition_task_ids = condition_task_titles.map((title) => {
      const id = taskIdByTitle[title];
      if (!id) throw new Error(`Blocking rule "${rule.name}" references unknown task: "${title}"`);
      return id;
    });

    const row = { ...ruleFields, condition_task_ids, user_id: userId };

    const { error } = await adminClient.from("blocking_rules").insert(row);
    if (error) throw new Error(`Failed to insert rule "${rule.name}": ${error.message}`);
    console.log(`  Created: ${rule.name}`);
  }

  console.log("\n✓ Bootstrap complete\n");
}

main().catch((err) => {
  console.error("\n✗", err.message);
  process.exit(1);
});
