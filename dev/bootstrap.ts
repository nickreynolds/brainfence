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
  active_schedule?: Record<string, unknown> | string;
}

/**
 * When active_schedule is "auto", compute a time window that includes
 * the current time so at least one blocking rule is always testable.
 */
function computeAutoSchedule(): Record<string, unknown> {
  const now = new Date();
  const hour = now.getHours();
  const pad = (n: number) => n.toString().padStart(2, "0");
  const startHour = Math.max(0, hour - 1);
  const endHour = Math.min(23, hour + 3);
  return {
    days: ["mon", "tue", "wed", "thu", "fri", "sat", "sun"],
    start: `${pad(startHour)}:00`,
    end: `${pad(endHour)}:59`,
  };
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

  // ── 1. Find or create user ──────────────────────────────────────────────────
  console.log(`\n→ Looking up user: ${USER_EMAIL}`);

  const { data: userList } = await adminClient.auth.admin.listUsers();
  let userId = userList?.users.find((u) => u.email === USER_EMAIL)?.id;

  if (userId) {
    console.log(`  Found existing user ${userId}`);

    // Delete all table data for this user (order matters for FK constraints)
    console.log("\n→ Deleting existing data (keeping user)...");
    const tables = [
      "step_completions",   // references task_completions
      "task_completions",   // references tasks
      "routine_steps",      // references tasks
      "blocking_rules",
      "notes",
      "tasks",
      "groups",
    ];
    for (const table of tables) {
      if (table === "step_completions") {
        // step_completions doesn't have user_id directly; delete via task_completions join
        const { data: tcIds } = await adminClient
          .from("task_completions")
          .select("id")
          .eq("user_id", userId);
        if (tcIds && tcIds.length > 0) {
          const { error } = await adminClient
            .from("step_completions")
            .delete()
            .in("task_completion_id", tcIds.map((tc: { id: string }) => tc.id));
          if (error) console.warn(`  Warning deleting ${table}: ${error.message}`);
          else console.log(`  Cleared ${table} (${tcIds.length} parent completions)`);
        } else {
          console.log(`  Cleared ${table} (nothing to delete)`);
        }
      } else {
        const { error } = await adminClient.from(table).delete().eq("user_id", userId);
        if (error) console.warn(`  Warning deleting ${table}: ${error.message}`);
        else console.log(`  Cleared ${table}`);
      }
    }
  } else {
    console.log("  No existing user found, creating...");
    const { data: created, error: createErr } = await adminClient.auth.admin.createUser({
      email: USER_EMAIL,
      password: USER_PASSWORD,
      email_confirm: true,
    });
    if (createErr) throw new Error(`Failed to create user: ${createErr.message}`);
    userId = created.user.id;
    console.log(`  Created user ${userId}`);
  }

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

    // Replace "auto" schedule with a computed window centered on the current time
    if (ruleFields.active_schedule === "auto") {
      ruleFields.active_schedule = computeAutoSchedule();
      console.log(`  Schedule for "${rule.name}" set to:`, JSON.stringify(ruleFields.active_schedule));
    }

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
