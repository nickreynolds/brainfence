# PowerSync Self-Hosted

This directory contains the Docker Compose and config files to run a self-hosted PowerSync service that syncs Supabase Postgres to client devices.

## Prerequisites

- Docker + Docker Compose
- Supabase project with **logical replication enabled** (see below)
- `.env` file in the repo root with all required variables (see `.env.example`)

## Enable Logical Replication on Supabase

PowerSync requires Postgres logical replication to stream changes. Enable it once in the Supabase dashboard:

1. Go to **Supabase dashboard → Database → Replication**
2. Enable logical replication

Or via SQL in the Supabase SQL editor:

```sql
ALTER SYSTEM SET wal_level = logical;
```

A database restart may be required after changing `wal_level`.

## Environment Variables

All variables are read from `../.env` (the repo root `.env`):

| Variable             | Description                                                |
|----------------------|------------------------------------------------------------|
| `SUPABASE_DB_URL`    | Postgres connection string for logical replication         |
| `PS_JWKS_URL`        | Supabase JWKS endpoint for RS256 JWT validation            |
| `PS_MONGO_URI`       | MongoDB connection string (set automatically by compose)   |
| `POWERSYNC_URL`      | Public URL of this PowerSync instance (used by clients)    |

## Files

| File                    | Purpose                                          |
|-------------------------|--------------------------------------------------|
| `docker-compose.yml`    | Local dev: PowerSync + MongoDB                   |
| `docker-compose.prod.yml` | Production: PowerSync + MongoDB + Caddy (HTTPS) |
| `config.yaml`           | PowerSync service configuration                  |
| `sync-rules.yaml`       | Per-user data sync rules                         |
| `Caddyfile`             | Reverse proxy config for production HTTPS        |

---

## Local Development

### With Android Emulator

The emulator maps `10.0.2.2` to the host machine's localhost. The app is already configured for this.

```bash
cd powersync
docker compose up -d
```

PowerSync will be available at `http://localhost:8080`.

### With a Real Device (on the same Wi-Fi network)

A real device can't reach `localhost` or `10.0.2.2`. Instead, use your machine's LAN IP.

1. Find your machine's LAN IP:

```bash
# macOS
ipconfig getifaddr en0
# Example output: 192.168.1.42
```

2. Start PowerSync normally:

```bash
cd powersync
docker compose up -d
```

3. Update the Android app to use your LAN IP. In `DatabaseModule.kt`, change the URL:

```kotlin
@Provides
@Named("powerSyncUrl")
fun providePowerSyncUrl(): String =
    "http://192.168.1.42:8080"  // your machine's LAN IP
```

4. Add your LAN IP to `network_security_config.xml` (cleartext HTTP is blocked by default on Android 9+):

```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">10.0.2.2</domain>
        <domain includeSubdomains="false">192.168.1.42</domain>
    </domain-config>
</network-security-config>
```

5. Rebuild and install the app.

> **Note:** Your LAN IP may change. If the app stops syncing, check your IP again.

---

## Production Deployment

For real-device use outside your local network, deploy PowerSync to a VPS with HTTPS.

### Hosting Recommendations

PowerSync + MongoDB need a small always-on server. Recommended options:

| Provider       | Plan                | Cost      | Notes                                      |
|----------------|---------------------|-----------|--------------------------------------------|
| **Hetzner**    | CX22 (2 vCPU, 4 GB) | ~$4.50/mo | Cheapest. EU or US datacenters.            |
| **DigitalOcean** | Basic Droplet (1 vCPU, 2 GB) | $6/mo | Simple setup, good docs.          |
| **Linode**     | Nanode (1 vCPU, 2 GB) | $5/mo   | Similar to DigitalOcean.                   |

Fly.io and Railway are also options but are more complex for stateful services (MongoDB). A basic VPS with Docker is the simplest path.

### Step-by-Step: Deploy to a VPS

#### 1. Provision the server

Create a VPS with Ubuntu 22.04+ (or Debian 12+). SSH in:

```bash
ssh root@your-server-ip
```

#### 2. Install Docker

```bash
curl -fsSL https://get.docker.com | sh
```

#### 3. Point a domain to your server

Add a DNS A record pointing to your server's IP:

```
powersync.yourdomain.com → 1.2.3.4
```

Caddy (included in the production compose file) will automatically obtain a Let's Encrypt TLS certificate.

#### 4. Clone the repo and configure

```bash
git clone <your-repo-url> brainfence
cd brainfence
```

Create the `.env` file with your production values:

```bash
cp .env.example .env
nano .env
```

Set the following:

```env
# Supabase (same values as local dev)
SUPABASE_URL=https://yourproject.supabase.co
SUPABASE_PUBLISHABLE_KEY=your-publishable-key
SUPABASE_SECRET_KEY=your-secret-key
SUPABASE_DB_URL=postgresql://postgres:yourpassword@db.yourproject.supabase.co:5432/postgres

# PowerSync
PS_DB_URL=postgresql://postgres:yourpassword@db.yourproject.supabase.co:5432/postgres
PS_MONGO_URI=mongodb://mongo:27017/powersync
PS_JWKS_URL=https://yourproject.supabase.co/auth/v1/.well-known/jwks.json

# IMPORTANT: Set this to your public HTTPS URL
POWERSYNC_URL=https://powersync.yourdomain.com

# Caddy domain (used by docker-compose.prod.yml)
CADDY_DOMAIN=powersync.yourdomain.com
```

#### 5. Start the production stack

```bash
cd powersync
docker compose -f docker-compose.prod.yml up -d
```

This starts three services:
- **MongoDB** — PowerSync's internal state store
- **PowerSync** — the sync service (port 8080 internally)
- **Caddy** — reverse proxy with automatic HTTPS (ports 80/443)

Check that everything is healthy:

```bash
docker compose -f docker-compose.prod.yml logs -f
```

#### 6. Verify it's working

```bash
curl https://powersync.yourdomain.com/
```

If you get a JSON response (even a 404 with a PowerSync error code), the service is running and reachable over HTTPS.

#### 7. Update the Android app

In `DatabaseModule.kt`:

```kotlin
@Provides
@Named("powerSyncUrl")
fun providePowerSyncUrl(): String =
    "https://powersync.yourdomain.com"
```

Since you're now using HTTPS, you can remove the cleartext traffic exceptions from `network_security_config.xml` (or leave them for local dev).

Rebuild and install:

```bash
cd android && ./gradlew installDebug
```

### Updating PowerSync

To update to the latest PowerSync image:

```bash
cd powersync
docker compose -f docker-compose.prod.yml pull powersync
docker compose -f docker-compose.prod.yml up -d powersync
```

### Monitoring

Check service health:

```bash
# All container statuses
docker compose -f docker-compose.prod.yml ps

# PowerSync logs
docker compose -f docker-compose.prod.yml logs -f powersync

# MongoDB logs
docker compose -f docker-compose.prod.yml logs -f mongo
```

### Firewall

Make sure ports 80 and 443 are open on your VPS. Most providers have this open by default, but if you're using `ufw`:

```bash
ufw allow 80/tcp
ufw allow 443/tcp
```

Do **not** expose port 8080 or 27017 publicly — Caddy handles external traffic on 80/443 and proxies to PowerSync internally.

---

## Operations How-To (our production droplet)

PowerSync runs on a DigitalOcean droplet behind `powersync.brainfence.online`, from a clone of this repo at `/root/brainfence`. The containers read `powersync/` from that clone (it's mounted at `/config`), so **config changes only take effect once the droplet's copy is updated** — rebooting the droplet alone won't pick them up.

### Get onto the droplet

```bash
ssh root@powersync.brainfence.online
cd /root/brainfence/powersync
```

If SSH fails, use **DigitalOcean dashboard → the droplet → Access → Launch Droplet Console**.

Unsure where the config is mounted from? `docker inspect powersync-powersync-1 --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{"\n"}}{{end}}'` — the line ending in `/config` is the directory.

### Roll out a schema change (new table or column)

Order matters: the server must know about a table **before** an app build writes to it, or uploads to it fail and can hold up the upload queue (including task completions).

**Shortcut:** `npm run deploy` (repo root) does steps 1 and 3 in that order. It pushes migrations with `supabase db push`, then uploads `sync-rules.yaml` over SSH if it changed, restarts PowerSync and checks its logs. `npm run deploy -- --dry-run` shows the pending migrations and the sync-rules diff without changing anything. It needs this checkout linked (`supabase link`), the DB password via `SUPABASE_DB_URL` in `.env`, and SSH access to the droplet (see `.env.example` for overrides).

1. **Migration** — add a file under `supabase/migrations/`, then from the repo root: `supabase db push`
2. **Client schema** — add the table/columns to `android/.../data/db/AppSchema.kt`. Any JSONB or `TEXT[]`/`UUID[]` column must also go in `JSON_ENCODED_COLUMNS` in `SupabasePowerSyncConnector.kt`, or writes to it silently revert.
3. **Sync rules** — add the table to `sync-rules.yaml`, get it onto the droplet, and restart:
   ```bash
   # Preferred: commit + push, then on the droplet
   git pull
   # Or copy just the file from your machine (repo root)
   scp powersync/sync-rules.yaml root@powersync.brainfence.online:/root/brainfence/powersync/
   ```
   ```bash
   # On the droplet
   docker compose -f docker-compose.prod.yml restart powersync
   docker compose -f docker-compose.prod.yml logs --tail=100 -f powersync   # check for sync-rules errors
   ```
4. **App** — `cd android && ./gradlew installDebug` (phone connected via USB).

If you `scp`'d files, the droplet's checkout now has local edits; before the next `git pull` there, run `git checkout -- <file>` (or `git stash`) so the pull doesn't conflict.

### Restart vs. `up -d`

- `docker compose -f docker-compose.prod.yml restart powersync` — reloads `config.yaml` / `sync-rules.yaml`.
- `docker compose -f docker-compose.prod.yml up -d` — needed after editing `docker-compose.prod.yml` itself (recreates the changed containers), and also starts anything that has stopped. Data lives in named volumes (`mongo_data`, `caddy_data`), so recreating containers is safe.

### Disk full ("no space left on device")

Check: `df -h /`, then find what's big:

```bash
du -sh /var/lib/docker/containers/*/*-json.log 2>/dev/null | sort -h | tail -5   # container logs
docker ps -a --format '{{.ID}}  {{.Names}}'                                     # map log IDs to containers
docker system df                                                                # images / volumes
```

Safe ways to free space:

```bash
truncate -s 0 /var/lib/docker/containers/<id>*/*-json.log   # empty a huge container log
docker image prune -a -f                                     # old, unused images
journalctl --vacuum-size=100M
apt-get clean
```

Then `docker compose -f docker-compose.prod.yml up -d`.

**Never** run `docker volume prune` or `docker system prune --volumes` — they delete `mongo_data` (PowerSync would re-replicate from Postgres) and `caddy_data` (HTTPS certificates).

History: in Sept 2026 the disk filled because MongoDB's container log reached 18 GB (its 5-second healthcheck logged every connection, and Docker keeps logs forever by default). `docker-compose.prod.yml` now caps every container's logs at 3 × 10 MB and runs mongod with `--quiet`, so this shouldn't recur — if it does, check `docker system df` for volume growth instead.

### If sync is down, is data at risk?

No. Supabase Postgres is the source of truth; MongoDB only holds PowerSync's derived sync state. The app is local-first, so changes made while PowerSync is down are queued on the device and upload once it's back.
