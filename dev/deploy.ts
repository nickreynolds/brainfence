// Deploys the backend: Supabase migrations, Supabase Auth's redirect URLs,
// PowerSync sync rules on the droplet, then the website (site/) there. Order matters: sync rules (and app
// builds) must not reference a table before its migration has run. See
// powersync/README.md (Operations).
//
//   npm run deploy               push migrations, set auth URLs, upload sync rules, restart PowerSync,
//                                upload the website
//   npm run deploy -- --dry-run  show what would change, without changing it
//   npm run deploy -- --yes      don't ask before pushing migrations
//   npm run deploy -- --skip-migrations | --skip-auth | --skip-sync-rules | --skip-site
//
// Reads from .env:
//   SUPABASE_DB_URL       its password is used for `supabase db push` (or set SUPABASE_DB_PASSWORD)
//   SUPABASE_ACCESS_TOKEN a personal access token (supabase.com/dashboard/account/tokens), for
//                         the auth URLs; without it that step is skipped with a warning
//   POWERSYNC_SSH_IP      the droplet's IP; SSH goes to root@<it>
//   DEPLOY_SSH_TARGET     overrides that (default root@powersync.brainfence.online)
//   DEPLOY_SSH_KEY        optional private key path
//   DEPLOY_REMOTE_DIR     the droplet's clone of this repo, default /root/brainfence

import { spawnSync, SpawnSyncOptionsWithStringEncoding } from "child_process";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import * as dotenv from "dotenv";

const root = path.resolve(__dirname, "..");
dotenv.config({ path: path.join(root, ".env") });

const args = new Set(process.argv.slice(2));
const known = ["--dry-run", "--yes", "--skip-migrations", "--skip-auth", "--skip-sync-rules", "--skip-site"];
for (const a of Array.from(args)) {
  if (!known.includes(a)) fail(`Unknown option ${a}. Options: ${known.join(", ")}`);
}
const dryRun = args.has("--dry-run");

const sshTarget = process.env.DEPLOY_SSH_TARGET
  || (process.env.POWERSYNC_SSH_IP ? `root@${process.env.POWERSYNC_SSH_IP}` : "root@powersync.brainfence.online");
const remoteDir = process.env.DEPLOY_REMOTE_DIR || "/root/brainfence";
const sshKey = process.env.DEPLOY_SSH_KEY;
const compose = `cd ${quote(`${remoteDir}/powersync`)} && docker compose -f docker-compose.prod.yml`;
const localRules = path.join(root, "powersync", "sync-rules.yaml");
const remoteRules = `${remoteDir}/powersync/sync-rules.yaml`;
const localSite = path.join(root, "site");
const remoteSite = `${remoteDir}/site`;

function fail(message: string): never {
  console.error(`\n✗ ${message}`);
  process.exit(1);
}

/** Single-quotes a value for the remote shell. */
function quote(s: string): string {
  return `'${s.replace(/'/g, `'\\''`)}'`;
}

function run(cmd: string, argv: string[], opts: Omit<SpawnSyncOptionsWithStringEncoding, "encoding"> = {}) {
  const r = spawnSync(cmd, argv, { ...opts, encoding: "utf8" });
  if (r.error) fail(`${cmd}: ${r.error.message}`);
  return r;
}

function sshArgv(command: string): string[] {
  const argv = ["-o", "BatchMode=yes", "-o", "ConnectTimeout=10"];
  if (sshKey) argv.push("-i", sshKey);
  argv.push(sshTarget, command);
  return argv;
}

function ssh(command: string, input?: string) {
  return run("ssh", sshArgv(command), { input, stdio: ["pipe", "pipe", "pipe"] });
}

function supabaseEnv(): NodeJS.ProcessEnv {
  let password = process.env.SUPABASE_DB_PASSWORD;
  if (!password && process.env.SUPABASE_DB_URL) {
    try {
      password = decodeURIComponent(new URL(process.env.SUPABASE_DB_URL).password);
    } catch {
      fail("SUPABASE_DB_URL in .env isn't a valid URL");
    }
  }
  if (!password) fail("Set SUPABASE_DB_URL (or SUPABASE_DB_PASSWORD) in .env");
  return { ...process.env, SUPABASE_DB_PASSWORD: password };
}

// ── Preflight: check everything before changing anything ────────────────────

function preflight() {
  console.log("→ Checking prerequisites…");
  if (!args.has("--skip-migrations")) {
    if (run("supabase", ["--version"]).status !== 0) fail("The Supabase CLI isn't installed (brew install supabase/tap/supabase)");
    if (!fs.existsSync(path.join(root, "supabase", ".temp", "project-ref"))) {
      fail("This checkout isn't linked to the Supabase project. Run: supabase link --project-ref <ref>");
    }
  }
  if (!args.has("--skip-sync-rules") || !args.has("--skip-site")) {
    const r = ssh(`test -f ${quote(remoteRules)} && docker compose version >/dev/null`);
    if (r.status !== 0) {
      const err = (r.stderr || "").trim();
      if (/host key verification failed/i.test(err)) {
        fail(`SSH doesn't know the droplet's host key yet. Connect once to accept it: ssh ${sshTarget}`);
      }
      fail(`Can't reach ${sshTarget}, or ${remoteRules} / docker is missing there.\n  ${err}`);
    }
  }
}

// ── 1. Migrations ───────────────────────────────────────────────────────────

function deployMigrations() {
  console.log(`\n→ Supabase migrations${dryRun ? " (dry run)" : ""}`);
  const argv = ["db", "push"];
  if (dryRun) argv.push("--dry-run");
  if (args.has("--yes")) argv.push("--yes");
  const r = run("supabase", argv, { cwd: root, env: supabaseEnv(), stdio: "inherit" });
  if (r.status !== 0) fail("supabase db push failed; sync rules were not touched");
}

// ── 2. Auth redirect URLs ───────────────────────────────────────────────────

// Email links open the apps (shared/CONTRACTS.md, Email links). Supabase only
// redirects to allow-listed URLs, and falls back to the Site URL otherwise.
const AUTH_CALLBACK_URL = "brainfence://auth-callback";

async function deployAuthUrls() {
  console.log(`\n→ Supabase Auth redirect URLs${dryRun ? " (dry run)" : ""}`);
  const token = process.env.SUPABASE_ACCESS_TOKEN;
  if (!token) {
    console.warn("  ⚠ SUPABASE_ACCESS_TOKEN isn't set, so this was skipped. Set Site URL and Redirect URLs to");
    console.warn(`    ${AUTH_CALLBACK_URL} in the dashboard (Authentication → URL Configuration), or add a token.`);
    return;
  }
  const ref = fs.readFileSync(path.join(root, "supabase", ".temp", "project-ref"), "utf8").trim();
  const endpoint = `https://api.supabase.com/v1/projects/${ref}/config/auth`;
  const headers = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };

  const res = await fetch(endpoint, { headers });
  if (!res.ok) fail(`Reading the auth config failed: HTTP ${res.status} ${await res.text()}`);
  const config = (await res.json()) as { site_url?: string; uri_allow_list?: string };
  const allowList = (config.uri_allow_list ?? "").split(",").map((u) => u.trim()).filter(Boolean);
  const changes: Record<string, string> = {};
  if (config.site_url !== AUTH_CALLBACK_URL) changes.site_url = AUTH_CALLBACK_URL;
  if (!allowList.includes(AUTH_CALLBACK_URL)) changes.uri_allow_list = [...allowList, AUTH_CALLBACK_URL].join(",");
  if (Object.keys(changes).length === 0) {
    console.log("  Already up to date.");
    return;
  }
  if (changes.site_url) console.log(`  Site URL: ${config.site_url || "(none)"} → ${changes.site_url}`);
  if (changes.uri_allow_list) console.log(`  Redirect URLs: add ${AUTH_CALLBACK_URL}`);
  if (dryRun) return;

  const patch = await fetch(endpoint, { method: "PATCH", headers, body: JSON.stringify(changes) });
  if (!patch.ok) fail(`Updating the auth config failed: HTTP ${patch.status} ${await patch.text()}`);
  console.log("  Updated.");
}

// ── 3. Sync rules ───────────────────────────────────────────────────────────

function deploySyncRules() {
  console.log(`\n→ PowerSync sync rules on ${sshTarget}${dryRun ? " (dry run)" : ""}`);
  const local = fs.readFileSync(localRules, "utf8");
  const current = ssh(`cat ${quote(remoteRules)}`);
  if (current.status !== 0) fail(`Couldn't read ${remoteRules}: ${current.stderr.trim()}`);
  if (current.stdout === local) {
    console.log("  Already up to date; PowerSync not restarted.");
    return;
  }

  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "brainfence-deploy-"));
  const remoteCopy = path.join(tmp, "droplet-sync-rules.yaml");
  fs.writeFileSync(remoteCopy, current.stdout);
  run("diff", ["-u", "--label", "droplet", "--label", "local", remoteCopy, localRules], { stdio: "inherit" });
  fs.rmSync(tmp, { recursive: true, force: true });
  if (dryRun) return;

  // Write beside the file and rename, so PowerSync never reads half a file.
  const upload = ssh(`cat > ${quote(`${remoteRules}.tmp`)} && mv ${quote(`${remoteRules}.tmp`)} ${quote(remoteRules)}`, local);
  if (upload.status !== 0) fail(`Upload failed: ${upload.stderr.trim()}`);
  console.log("  Uploaded. Restarting PowerSync…");

  const since = ssh("date -u +%Y-%m-%dT%H:%M:%SZ").stdout.trim();
  const restart = ssh(`${compose} restart powersync`);
  if (restart.status !== 0) fail(`Restart failed: ${restart.stderr.trim()}`);

  // Give the service time to load the rules, then look for complaints.
  spawnSync("sleep", ["15"]);
  const state = ssh(`${compose} ps powersync --format '{{.State}}'`).stdout.trim();
  const logs = ssh(`${compose} logs --no-color --since ${quote(since)} powersync`);
  const problems = (logs.stdout + logs.stderr)
    .split("\n")
    .filter((l) => /\b(error|fatal|invalid|failed)\b/i.test(l));
  if (state !== "running" || problems.length > 0) {
    console.error(`\n  PowerSync is ${state || "not running"}. Log lines since the restart:`);
    for (const l of problems.slice(-30)) console.error(`  ${l}`);
    fail(`Check the sync rules. Full logs: ssh ${sshTarget} "${compose} logs --tail=100 powersync"`);
  }
  console.log("  PowerSync restarted cleanly.");

  // scp'd files leave the droplet's clone with local edits; say so before a `git pull` there conflicts.
  const drift = ssh(`git -C ${quote(remoteDir)} status --short -- powersync`).stdout.trim();
  if (drift) {
    console.log(`  Note: the droplet's checkout now differs from its git HEAD (${drift}).`);
    console.log("  That clears up once this change is committed, pushed and pulled there.");
  }
}

// ── 4. Website ──────────────────────────────────────────────────────────────

function deploySite() {
  console.log(`\n→ Website (site/) on ${sshTarget}${dryRun ? " (dry run)" : ""}`);
  const files = fs.readdirSync(localSite, { recursive: true, withFileTypes: true }).filter((f) => f.isFile());
  if (dryRun) {
    console.log(`  Would replace ${remoteSite} with the ${files.length} files in site/.`);
    return;
  }

  // Caddy serves the folder through a bind mount, so replace its contents
  // rather than the folder itself.
  const tar = spawnSync("tar", ["-czf", "-", "-C", localSite, "."], { maxBuffer: 256 * 1024 * 1024 });
  if (tar.status !== 0) fail(`tar failed: ${tar.stderr.toString().trim()}`);
  const upload = spawnSync("ssh", sshArgv(
    `mkdir -p ${quote(remoteSite)} && find ${quote(remoteSite)} -mindepth 1 -delete && tar -xzf - -C ${quote(remoteSite)}`,
  ), { input: tar.stdout });
  if (upload.status !== 0) fail(`Upload failed: ${upload.stderr.toString().trim()}`);
  console.log(`  Uploaded ${files.length} files.`);

  // Older droplet checkouts' Caddy doesn't mount site/ yet.
  if (ssh(`${compose} exec -T caddy test -d /srv/site`).status !== 0) {
    console.log("  Caddy isn't serving it yet. Once this repo's powersync/ changes are pushed, run once:");
    console.log(`    ssh ${sshTarget} "git -C ${quote(remoteDir)} pull && ${compose} up -d caddy"`);
  }
}

async function main() {
  preflight();
  if (!args.has("--skip-migrations")) deployMigrations();
  if (!args.has("--skip-auth")) await deployAuthUrls();
  if (!args.has("--skip-sync-rules")) deploySyncRules();
  if (!args.has("--skip-site")) deploySite();
  console.log(dryRun ? "\n✓ Dry run finished; nothing was changed." : "\n✓ Deployed.");
}

void main();
