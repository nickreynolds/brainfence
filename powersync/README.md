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
SUPABASE_ANON_KEY=your-anon-key
SUPABASE_SERVICE_ROLE_KEY=your-service-role-key
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
