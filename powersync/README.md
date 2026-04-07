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

## Running the Service

```bash
cd powersync
docker compose up -d
```

Check logs:

```bash
docker compose logs -f
```

The service will be available at `http://localhost:8080`.

## Environment Variables

All variables are read from `../.env` (the repo root `.env`):

| Variable             | Description                                                |
|----------------------|------------------------------------------------------------|
| `SUPABASE_DB_URL`    | Postgres connection string for logical replication         |
| `SUPABASE_JWKS_URL`  | Supabase JWKS endpoint for RS256 JWT validation            |
| `POWERSYNC_URL`      | Public URL of this PowerSync instance (used by clients)    |

## Files

| File                | Purpose                                          |
|---------------------|--------------------------------------------------|
| `docker-compose.yml`| Runs the PowerSync service container             |
| `config.yaml`       | PowerSync service configuration                  |
| `sync-rules.yaml`   | Per-user data sync rules (written in INFRA-005)  |
