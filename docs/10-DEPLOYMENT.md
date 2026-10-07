# 10 - Deployment, Operations and Go-live

Scope: how Nexora is built, shipped, run, backed up, monitored and rolled back, and how the business's notebook data is moved
into the system. Decisions: ADR-021 (secrets), ADR-022 (observability), ADR-023 (deployment target, accepted by
`PLAN_DECISIONS.md` section 4: Docker compose on a VPS with Caddy; CI/CD on GitHub Actions). Security controls referenced here
are defined in `09-SECURITY.md`; stack versions in `03-ARCHITECTURE.md` section 2.

All files introduced by this document are created in Phase 8 (task numbers refer to `ROADMAP.md`) unless marked *exists*.

## 1. Environments

| | dev | test | prod |
|---|---|---|---|
| Where | developer machine | CI runner and local `./gradlew test` | one VPS (2 vCPU, 4 GB RAM, 40 GB SSD minimum) |
| Profile | `dev` (default via `spring.profiles.default`) | `dev` + Testcontainers (the file `application-test.yml` is only for manual runs) | `prod` (`SPRING_PROFILES_ACTIVE=prod`) |
| Database | `docker compose up -d` in `Nexora-backend/` (*exists*: `postgres:17`, host port 5431) | throwaway `postgres:17-alpine` per JVM | `postgres:17` container, volume `postgres_data`, **no published port** |
| Swagger UI / API docs | on | on | **off** (404) |
| Logs | text, `com.nexora` DEBUG | WARN | JSON, INFO |
| Secrets | dev defaults in `application-dev.yml` | none needed | environment file on the VPS only |
| TLS | none | none | Caddy, automatic certificates |
| Data | disposable | disposable | real; backed up nightly |

## 2. Repository layout added for deployment

```
Nexora-backend/Dockerfile                   multi-stage image (section 3)
Nexora-backend/.env.example                 dev variables (section 5.1, *exists*, completed)
deploy/docker-compose.prod.yml              app + postgres + caddy (section 4)
deploy/Caddyfile                            reverse proxy + TLS (section 4)
deploy/.env.prod.example                    prod variables (section 5.2)
deploy/postgres/10-roles.sh                 creates the two DB roles on first start (section 4.3)
deploy/scripts/deploy.sh                    pull, migrate, start, smoke test, auto-rollback (section 7)
deploy/scripts/host-check.sh                every 5 minutes: liveness/disk/memory check, restart on failure (section 11)
deploy/scripts/backup.sh                    nightly encrypted dump + upload (section 9)
deploy/scripts/restore-drill.sh             monthly restore rehearsal (section 9.3)
.github/workflows/ci.yml                    build/test/audit/image (*exists*, extended in section 6)
.github/workflows/deploy.yml                manual-approval deploy (section 6)
.github/dependabot.yml                      weekly dependency PRs
docs/RUNBOOK.md                             copy of sections 7-10 in step-by-step form (task 8.8)
```
Two build changes (task 8.2): `bootJar { archiveFileName = 'app.jar' }` in `build.gradle` (stable file name for the image) and
`springBoot { buildInfo() }` (version shown at `/actuator/info`).

## 3. Dockerfile (`Nexora-backend/Dockerfile`)

Verified on 2026-10-07 with Spring Boot 4.1.1: `java -Djarmode=tools -jar app.jar extract --layers --destination <dir>` produces the
directories `dependencies/lib`, `application/<thin jar with Main-Class and a Class-Path of lib/...>`, `spring-boot-loader` and
`snapshot-dependencies` (the last two are empty for this project). The runtime image `eclipse-temurin:21-jre` is Ubuntu-based and already
contains `curl` (used by the health check).

```dockerfile
# ---------- stage 1: build the jar (JDK) ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# copy only the files that define dependencies first, so Docker caches the download layer
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null

# now the sources (changes often) and build; tests already ran in CI before this image is built
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

# split the fat jar into layers: dependencies change rarely, application code changes often
RUN java -Djarmode=tools -jar build/libs/app.jar extract --layers --destination /extracted

# ---------- stage 2: runtime (JRE only, no compiler, no build tools) ----------
FROM eclipse-temurin:21-jre
# dedicated unprivileged user: the process never runs as root
RUN groupadd --system --gid 10001 nexora && useradd --system --uid 10001 --gid nexora --no-create-home nexora
WORKDIR /app

# order = least to most frequently changing, so a code change rebuilds only the last layer
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

USER nexora
EXPOSE 8080

# readiness = the JVM is up AND the database answers (Actuator readiness group)
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

# JAVA_OPTS comes from the environment file; exec makes java PID 1 so it receives SIGTERM (graceful shutdown)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
```
Application settings that go with it (added to `application-prod.yml`, task 8.2): `server.shutdown: graceful`,
`spring.lifecycle.timeout-per-shutdown-phase: 30s`, `management.endpoint.health.probes.enabled: true`,
`management.endpoint.health.show-details: never`, `server.forward-headers-strategy: framework`,
`logging.structured.format.console: ecs`.
Image properties to verify in CI: runs as UID 10001 (`docker run --rm IMAGE id -u` prints `10001`), size below 350 MB, healthy within 60 s.

## 4. Production compose stack

### 4.1 `deploy/docker-compose.prod.yml`
```yaml
name: nexora

services:
  postgres:
    image: postgres:17
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${DB_NAME}
      POSTGRES_USER: postgres                    # admin account, used only to initialise and to maintain
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      OWNER_DB_PASSWORD: ${OWNER_DB_PASSWORD}    # read by deploy/postgres/10-roles.sh
      APP_DB_PASSWORD: ${APP_DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./postgres/10-roles.sh:/docker-entrypoint-initdb.d/10-roles.sh:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d ${DB_NAME}"]
      interval: 10s
      timeout: 3s
      retries: 10
    networks: [backend]                          # no ports published: only reachable by the app
    mem_limit: 1g
    logging: { driver: json-file, options: { max-size: "10m", max-file: "5" } }

  app:
    image: ${IMAGE_REPO}:${IMAGE_TAG}            # e.g. ghcr.io/<owner>/nexora-backend:<git sha>
    restart: unless-stopped
    env_file: .env.prod
    depends_on:
      postgres: { condition: service_healthy }
    read_only: true                              # container filesystem is immutable
    tmpfs: [/tmp]
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
    mem_limit: 768m
    stop_grace_period: 40s
    networks: [backend, edge]
    logging: { driver: json-file, options: { max-size: "10m", max-file: "5" } }

  caddy:
    image: caddy:2
    restart: unless-stopped
    depends_on:
      app: { condition: service_healthy }
    ports: ["80:80", "443:443", "443:443/udp"]
    environment:
      APP_DOMAIN: ${APP_DOMAIN}
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data                         # certificates: must persist or Let's Encrypt limits are hit
      - caddy_config:/config
    networks: [edge]
    mem_limit: 128m
    logging: { driver: json-file, options: { max-size: "10m", max-file: "5" } }

networks:
  backend: { internal: true }                    # no outbound internet for postgres/app-to-db traffic
  edge: {}

volumes:
  postgres_data:
  caddy_data:
  caddy_config:
```
Note: the `app` service is on both networks (needs the DB and Caddy) but publishes no host port, so only Caddy reaches it.
The app needs outbound internet only for future integrations; if none is required, `edge` can also be marked `internal: true` and Caddy
given a third public network. The MVP has no outbound integration, so `edge` stays default for certificate issuance by Caddy only.

### 4.2 `deploy/Caddyfile`
```caddyfile
{$APP_DOMAIN} {
    encode zstd gzip
    # TLS certificates are obtained and renewed automatically; HTTP is redirected to HTTPS by default
    header {
        Strict-Transport-Security "max-age=31536000"
        -Server
    }
    request_body {
        max_size 12MB            # 10 MiB documents + headroom; the app enforces 1 MiB for JSON (09-SECURITY section 5)
    }
    reverse_proxy app:8080 {
        health_uri      /actuator/health/readiness
        health_interval 10s
        # Caddy adds X-Forwarded-For/Proto/Host; the app trusts them only because it is reachable solely from this network
    }
    log {
        output file /data/access.log {
            roll_size 10MB
            roll_keep 5
        }
        format json              # Authorization, Cookie and Set-Cookie headers are redacted by Caddy by default
    }
}
```

### 4.3 `deploy/postgres/10-roles.sh` (runs once, when the data directory is empty)
```bash
#!/bin/bash
set -euo pipefail
# two roles: owner = DDL (Flyway only), app = DML (the running application)
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE ROLE nexora_owner LOGIN PASSWORD '${OWNER_DB_PASSWORD}';
CREATE ROLE nexora_app   LOGIN PASSWORD '${APP_DB_PASSWORD}';
ALTER DATABASE ${POSTGRES_DB} OWNER TO nexora_owner;
ALTER SCHEMA public OWNER TO nexora_owner;
GRANT CONNECT ON DATABASE ${POSTGRES_DB} TO nexora_app;
GRANT USAGE ON SCHEMA public TO nexora_app;
-- every table/sequence Flyway creates later is usable by the app (the ledger REVOKEs are in migrations, 09-SECURITY section 10.3)
ALTER DEFAULT PRIVILEGES FOR ROLE nexora_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nexora_app;
ALTER DEFAULT PRIVILEGES FOR ROLE nexora_owner IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO nexora_app;
SQL
```

## 5. Environment files

### 5.1 `Nexora-backend/.env.example` (dev; copy to `.env`, which is git-ignored)
```dotenv
# --- database (docker-compose.yml reads DB_PASSWORD; the app reads all of them) ---
DB_URL=jdbc:postgresql://localhost:5431/nexora_db
DB_USERNAME=postgres
DB_PASSWORD=nexora_dev
# --- server ---
SERVER_PORT=8080
# --- security (dev-only values; prod uses deploy/.env.prod.example) ---
JWT_SECRET=dev-only-secret-change-me-at-least-32-bytes-long
BOOTSTRAP_OWNER_USERNAME=owner
BOOTSTRAP_OWNER_PASSWORD=DevOwner#2026pass
```
Use: `set -a; source .env; set +a; ./gradlew bootRun` (Spring does not read `.env` itself). `DB_PASSWORD` must stay `nexora_dev`
unless both the compose file and the variable are changed together.

### 5.2 `deploy/.env.prod.example` (copy to `deploy/.env.prod` on the VPS, mode 600; also loaded by compose for `${...}` substitution via `--env-file`)
```dotenv
# --- routing / image ---
APP_DOMAIN=nexora.example.com            # DNS A record must point to the VPS before first start
IMAGE_REPO=ghcr.io/OWNER/nexora-backend  # replace OWNER with the GitHub account or organisation
IMAGE_TAG=0000000                        # the git sha (or release tag) to run; written by deploy.sh
SPRING_PROFILES_ACTIVE=prod
JAVA_OPTS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError
# --- database ---
DB_NAME=nexora_db
POSTGRES_PASSWORD=                       # >= 24 random chars (admin; used only for init and maintenance)
OWNER_DB_PASSWORD=                       # >= 24 random chars (Flyway)
APP_DB_PASSWORD=                         # >= 24 random chars (application)
DB_URL=jdbc:postgresql://postgres:5432/nexora_db?options=-c%20lock_timeout=5000%20-c%20statement_timeout=30000
DB_USERNAME=nexora_app
DB_PASSWORD=                             # equals APP_DB_PASSWORD
FLYWAY_URL=jdbc:postgresql://postgres:5432/nexora_db   # no timeout options: migrations may run longer than 30 s
FLYWAY_USER=nexora_owner
FLYWAY_PASSWORD=                         # equals OWNER_DB_PASSWORD
# --- security (09-SECURITY section 9) ---
JWT_SECRET=                              # openssl rand -base64 48
BOOTSTRAP_OWNER_USERNAME=                # needed only on the very first start
BOOTSTRAP_OWNER_PASSWORD=                # delete these two lines after the first successful start
# --- backups (section 9) ---
AGE_PUBLIC_KEY=                          # age1... recipient public key; the private key is NOT on the server
RCLONE_REMOTE=nexora-backups:nexora-db   # rclone remote:bucket with a write-only credential
HEALTHCHECK_PING_URL=                    # https://hc-ping.com/<uuid> dead-man's-switch check
```
Empty values above are filled on the server by hand; the file is never committed. `docker compose config` is run in CI with dummy values to prove the file set is complete.

## 6. CI/CD pipeline (GitHub Actions)

### 6.1 `.github/workflows/ci.yml` (extends the existing file)
Triggers: `pull_request`, `push` to `main`, and a nightly `schedule` (`cron: '17 22 * * *'`) for the dependency audit.

| Job | Runs on | Steps | Fails when |
|---|---|---|---|
| `build` | PR, push | checkout; Temurin 21; `gradle/actions/setup-gradle@v4`; `./gradlew build` (compile, all tests on Testcontainers, JaCoCo verification, `bootJar`); upload JUnit and JaCoCo reports (always) | any test fails; line coverage on `application`/`domain` below 80 %; compilation error |
| `audit` | PR, push, nightly | `./gradlew dependencyCheckAnalyze` with `NVD_API_KEY` secret; upload report | any dependency with CVSS >= 7 without a valid suppression |
| `secrets` | PR, push | `gitleaks/gitleaks-action@v2` | a secret pattern is found in the diff |
| `compose-check` | PR, push | `docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod.example config` | the compose file is invalid or a variable is undefined |
| `image` | push to `main`, needs `build`, `audit`, `secrets` | `docker/setup-buildx-action@v3`; `docker/login-action@v3` to `ghcr.io` with `GITHUB_TOKEN` (`permissions: packages: write`); `docker/build-push-action@v6` (context `Nexora-backend`) tags `ghcr.io/${{ github.repository_owner }}/nexora-backend:${{ github.sha }}` and `:main`; Trivy scan (`aquasecurity/trivy-action`, `severity: HIGH,CRITICAL`, `ignore-unfixed: true`, `exit-code: 1`) before the push step | image scan finds a fixable HIGH/CRITICAL issue; container does not become healthy in the smoke step (`docker run` with a Testcontainers-style PostgreSQL service and `curl /actuator/health/readiness`) |

Every action is pinned to a major version (`@v4`, `@v3`, `@v6`, `@v2`). Gate for merging a pull request: `build`, `audit`, `secrets`, `compose-check` green
(branch protection on `main`, "require status checks").

### 6.2 `.github/workflows/deploy.yml` (manual approval)
```yaml
name: Deploy
on:
  workflow_dispatch:
    inputs:
      image_tag:
        description: "Image tag to deploy (git sha or release tag)"
        required: true
jobs:
  deploy:
    runs-on: ubuntu-latest
    environment: production          # GitHub Environment with "Required reviewers" = the owner => deployment waits for approval
    concurrency: production-deploy   # never two deploys at once
    steps:
      - uses: actions/checkout@v4
      - name: Install SSH key
        run: |
          install -m 700 -d ~/.ssh
          echo "${{ secrets.DEPLOY_SSH_KEY }}" > ~/.ssh/id_ed25519 && chmod 600 ~/.ssh/id_ed25519
          ssh-keyscan -H "${{ secrets.DEPLOY_HOST }}" >> ~/.ssh/known_hosts
      - name: Copy deploy files
        run: scp -r deploy/docker-compose.prod.yml deploy/Caddyfile deploy/postgres deploy/scripts "${{ secrets.DEPLOY_USER }}@${{ secrets.DEPLOY_HOST }}:/opt/nexora/"
      - name: Run deploy script
        run: ssh "${{ secrets.DEPLOY_USER }}@${{ secrets.DEPLOY_HOST }}" "cd /opt/nexora && ./scripts/deploy.sh ${{ inputs.image_tag }}"
```
Secrets (GitHub Environment `production`): `DEPLOY_SSH_KEY` (a key authorised only for the deploy user, restricted to the commands above via `authorized_keys`
`command=`/`from=`), `DEPLOY_HOST`, `DEPLOY_USER`. The server pulls the image from GHCR with a read-only token stored once with `docker login`.

## 7. Release procedure and rollback

### 7.1 `deploy/scripts/deploy.sh <image_tag>` (behaviour, step by step)
1. `set -euo pipefail`; refuse to run if `<image_tag>` is empty; read `IMAGE_TAG` currently in `.env.prod` into `PREVIOUS_TAG`.
2. **Pre-deploy backup:** run `scripts/backup.sh --label pre-deploy-<tag>` (section 9.1). If it fails, stop: no deploy without a fresh backup.
3. Write `IMAGE_TAG=<tag>` into `.env.prod`; `docker compose --env-file .env.prod -f docker-compose.prod.yml pull app`.
4. `docker compose ... up -d` (Compose restarts only changed services; the app container runs Flyway on start, with the `nexora_owner` credentials and `FLYWAY_URL`).
5. Wait up to 120 s for the `app` container status `healthy`.
6. Smoke test: `curl -fsS https://$APP_DOMAIN/actuator/health` must return `{"status":"UP"}`; `curl -s -o /dev/null -w '%{http_code}' https://$APP_DOMAIN/api/v1/parties` must print `401`.
7. On any failure in steps 4-6: set `IMAGE_TAG=$PREVIOUS_TAG`, `up -d`, wait healthy, exit non-zero (the workflow run fails and the owner is told). Record the tag in `deploy/.deploy-history`.

### 7.2 Flyway migration policy on deploy
- Migrations run automatically at application start (`spring.flyway.enabled: true`) using `FLYWAY_URL`/`FLYWAY_USER`/`FLYWAY_PASSWORD` (`nexora_owner`, connection without the 5 s lock and 30 s statement timeouts); `ddl-auto: validate` makes the app refuse to start if code and schema disagree.
- `spring.flyway.validate-on-migrate: true`, `spring.flyway.clean-disabled: true`, `baseline-on-migrate: false`. A merged migration is immutable (ADR-016); a changed checksum stops startup.
- **Expand/contract rule:** every migration must work with both the new and the previous application version, so rolling back the app never needs a schema rollback. Adding columns/tables/indexes is allowed in one release; dropping or renaming a column needs two releases (stop using it, then remove it).
- Large index builds use `CREATE INDEX CONCURRENTLY` in a migration marked `executeInTransaction=false`.
- The pre-deploy backup in 7.1 step 2 is the safety net for a migration that corrupts data.
- Every pull request that adds a migration is tested twice in CI: on an empty database (Testcontainers) and on a database produced by the previous release's migrations.

### 7.3 Rollback
| Situation | Action | Target time |
|---|---|---|
| New app version unhealthy or smoke test failed | automatic: `deploy.sh` re-deploys `PREVIOUS_TAG` (works because of expand/contract) | < 3 min |
| Bug found after release, no data damage | manual: workflow `Deploy` with the previous tag, approve | < 10 min |
| Migration damaged data | stop the app (`docker compose stop app`); restore the `pre-deploy-*` backup into a fresh database (section 9.3 procedure), verify with the reconciliation queries (section 9.4), redeploy the previous tag against it; incident note in `docs/RUNBOOK.md` | RTO <= 2 h |
| Compromised JWT secret | rotate secret (09-SECURITY section 9) and revoke refresh tokens | < 5 min |

## 8. Health checks
| Probe | URL | Meaning | Used by |
|---|---|---|---|
| Liveness | `/actuator/health/liveness` | JVM and application context alive | `deploy/scripts/host-check.sh`: restarts the `app` container after 3 consecutive failed checks (Docker itself only marks a container unhealthy, it does not restart it) |
| Readiness | `/actuator/health/readiness` | application ready **and** database reachable | Docker `HEALTHCHECK`, Caddy `health_uri`, `deploy.sh` |
| Public health | `https://$APP_DOMAIN/actuator/health` | aggregate `{"status":"UP"}` (no details in prod) | external uptime monitor (section 11) |
Targets: readiness within 60 s of container start; `/actuator/health` p95 under 200 ms.

## 9. Backups and restore

Objectives (decision): **RPO 24 hours** (nightly dump; at most one business day of entries would need re-entry from the notebook, which continues during the
parallel run), **RTO 2 hours**. Retention: 30 days of nightly dumps plus the `pre-deploy-*` dumps of the last 10 releases.

### 9.1 `deploy/scripts/backup.sh [--label name]` (cron on the VPS: `30 21 * * *` UTC = 03:00 IST)
```bash
#!/bin/bash
set -euo pipefail
source /opt/nexora/.env.prod
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
LABEL=${2:-nightly}
FILE=/opt/nexora/backups/nexora-${LABEL}-${STAMP}.dump.age
mkdir -p /opt/nexora/backups

# custom-format dump of the whole database, encrypted with the PUBLIC key before it touches the disk
docker compose --env-file .env.prod -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U postgres -d "$DB_NAME" -Fc --no-owner | age -r "$AGE_PUBLIC_KEY" > "$FILE"

test "$(stat -c %s "$FILE")" -gt 1024                      # a dump smaller than 1 KiB is a failure
sha256sum "$FILE" > "$FILE.sha256"
rclone copyto "$FILE" "$RCLONE_REMOTE/$(basename "$FILE")" # write-only credential; remote lifecycle deletes after 30 days
rclone copyto "$FILE.sha256" "$RCLONE_REMOTE/$(basename "$FILE").sha256"
find /opt/nexora/backups -type f -mtime +7 -delete          # keep 7 days locally
curl -fsS -m 10 --retry 3 "$HEALTHCHECK_PING_URL" > /dev/null  # dead-man's-switch: silence for > 26 h raises an alert
```
Security of backups: `09-SECURITY.md` section 14 (age public-key encryption, offline private key, write-only upload, lifecycle retention).

### 9.2 What is backed up
The whole `nexora_db` (schema, ledgers, `flyway_schema_history`), plus `deploy/.env.prod` stored separately in the owner's password manager (never in the bucket).
Caddy certificates are not backed up (re-issued automatically).

### 9.3 Restore drill (monthly; also the disaster procedure)
Performed on a **separate machine** (the owner's laptop or a temporary VPS) so the production server is never touched.

1. Fetch the newest `nexora-nightly-*.dump.age` and its `.sha256` from the bucket (read credential held by the owner); `sha256sum -c` must print `OK`.
2. Decrypt with the offline private key: `age -d -i nexora-backup.key nexora-nightly-<stamp>.dump.age > restore.dump`.
3. Start an empty PostgreSQL 17: `docker run -d --name restore-pg -e POSTGRES_PASSWORD=drill -p 5440:5432 postgres:17`.
4. `docker exec -i restore-pg createdb -U postgres nexora_db` then `docker exec -i restore-pg pg_restore -U postgres -d nexora_db --no-owner --exit-on-error < restore.dump`.
5. Start the application image of the current release against it (profile `prod`, `DB_URL=jdbc:postgresql://host.docker.internal:5440/nexora_db`; Flyway reports "Schema is up to date").
6. Pass criteria, all required: (a) `/actuator/health` returns `UP`; (b) the reconciliation queries in 9.4 return zero rows; (c) `SELECT count(*) FROM financial_transactions` and `material_movements` match the production counts taken at backup time (recorded by `backup.sh` in the heartbeat note); (d) an OWNER can log in and `GET /api/v1/dashboard/summary` returns 200; (e) total elapsed time <= 2 hours.
7. Record date, backup file, elapsed time and result in `docs/RUNBOOK.md`; destroy the drill database and the decrypted file (`shred -u restore.dump`).
A drill older than 31 days is a release blocker.

### 9.4 Reconciliation queries (run after any restore and during the parallel run; names follow `04-DATA-MODEL.md`)
```sql
-- stock: projection must equal the ledger (expect zero rows)
SELECT product_type, location, ledger_qty, balance_qty FROM (
  SELECT m.product_type, m.location, SUM(m.qty) AS ledger_qty, COALESCE(b.quantity_kg, 0) AS balance_qty
  FROM (SELECT to_product_type AS product_type, to_location AS location,  quantity_kg AS qty FROM material_movements
        UNION ALL
        SELECT product_type, from_location AS location, -quantity_kg AS qty FROM material_movements) m
  LEFT JOIN inventory_balances b ON b.product_type = m.product_type AND b.location = m.location
  WHERE m.location IN ('RAW_STOCK','INTERNAL_WIP','EXTERNAL_WIP','FINISHED_STOCK')
  GROUP BY m.product_type, m.location, b.quantity_kg) x
WHERE ledger_qty <> balance_qty;

-- money: no account may be negative (expect zero rows)
SELECT financial_account_id, SUM(CASE direction WHEN 'IN' THEN amount ELSE -amount END) AS balance
FROM financial_transactions GROUP BY financial_account_id
HAVING SUM(CASE direction WHEN 'IN' THEN amount ELSE -amount END) < 0;

-- allocations never exceed their payment (expect zero rows)
SELECT p.id FROM payments p
JOIN (SELECT payment_id, SUM(allocated_amount) AS allocated FROM (
        SELECT payment_id, allocated_amount FROM customer_payment_allocations
        UNION ALL SELECT payment_id, allocated_amount FROM supplier_payment_allocations
        UNION ALL SELECT payment_id, allocated_amount FROM manufacturer_payment_allocations
        UNION ALL SELECT payment_id, allocated_amount FROM worker_payment_allocations) all_alloc
      GROUP BY payment_id) a ON a.payment_id = p.id
WHERE a.allocated > p.amount;
```
If a column name in `04-DATA-MODEL.md` differs, update this block in the same change.

## 10. Operations

| Task | Procedure |
|---|---|
| First server setup | create deploy user, install Docker Engine + compose plugin + `age` + `rclone`; `ufw allow 22,80,443`; disable password SSH login; unattended security upgrades on; create `/opt/nexora`; copy `deploy/` files; fill `.env.prod`; `docker login ghcr.io` with a read-only token; run `deploy.sh <tag>`; remove the two `BOOTSTRAP_OWNER_*` lines; install the cron entries (backup 21:30 UTC, nightly Docker prune `0 22 * * 0`) |
| Add or rotate a user (post-MVP) | via the user-management endpoints (reserved `USER_MANAGE`) |
| Rotate JWT secret | `09-SECURITY.md` section 9 |
| Disk cleanup | `docker system prune -f` weekly; Caddy and container logs are size-capped (10 MB x 5) |
| OS patching | monthly `apt upgrade` and reboot in a quiet window; compose services restart automatically (`restart: unless-stopped`) |
| Time | VPS clock via `systemd-timesyncd`; application time comes from the injected `Clock` (Asia/Kolkata), database timestamps are UTC |

## 11. Monitoring and alerting
Sources: an external HTTP monitor (UptimeRobot or equivalent), a dead-man's-switch check (Healthchecks.io or equivalent), and a small host script `deploy/scripts/host-check.sh`
run every 5 minutes by cron: it checks liveness, disk and memory, restarts the `app` container after 3 consecutive liveness failures, and posts to the same alert channel (email + phone push to the owner and the developer).

| Alert | Condition | Severity | Action |
|---|---|---|---|
| Site down | `https://$APP_DOMAIN/actuator/health` not `UP` for 2 consecutive checks (2 minutes) | critical | check `docker compose ps`, logs, restart; roll back if the last deploy was < 1 h ago |
| Backup missing | no heartbeat for 26 hours | critical | run `backup.sh` manually; inspect cron and disk |
| Disk | root volume > 80 % used | warning (> 90 % critical) | prune Docker, rotate logs, enlarge disk |
| Memory | container `app` restarted more than 2 times in 1 hour (OOM) | critical | inspect `JAVA_OPTS`, heap, leak |
| Error burst | more than 5 HTTP 5xx responses in 5 minutes (from Caddy JSON log) | critical | read logs by `requestId` |
| Auth anomaly | more than 20 responses `401` from one IP in 5 minutes, or any `refresh_token_reuse` log line | warning | review, consider blocking IP at firewall |
| Certificate | Caddy certificate expires in < 14 days | warning | check DNS and Caddy logs |
| Restore drill | last successful drill older than 31 days | warning | schedule drill |
Metrics endpoint (Phase 7): Prometheus format on the management port, scraped only from within the compose network if a dashboard is added; not required for go-live.

## 12. Go-live data import (task 8.7)

Principle: the system's history starts at a cut-over date (`go_live_date`). Everything before it is entered as **opening records** (never as fake transactions), so the
ledger rules hold from day one (Rule R1/R5, `04-DATA-MODEL.md`).

### 12.1 What is imported
| Data | How it becomes records | Source in the notebook | Checked against |
|---|---|---|---|
| Parties (customers, suppliers, manufacturers, workers, lenders), phones, supplier credit days | normal party creation (roles, `default_credit_days`, worker rates) | party lists | owner review of the printed list |
| Opening stock per product and location | one `OPENING_BALANCE` movement per (product, internal location) through `InventoryService` (movement date = `go_live_date`) | physical stock count done on cut-over day | the count sheet signed by the owner |
| Cash and bank accounts | accounts created; each opening balance = one `OPENING_BALANCE` financial transaction through `FinanceService` | cash count, bank statement balance | counted cash, statement balance |
| Customer receivables (money owed to the business) | **`opening_obligations`** rows: one per customer per original document, `purpose = 'CUSTOMER'`, `original_amount`, `obligation_date` (original date), `reference_note`; allocatable by payments exactly like order items (oldest-first by `obligation_date`) | customer ledger pages | per-customer total = notebook total |
| Supplier payables | `opening_obligations` with `purpose = 'SUPPLIER'` and `due_date` = `obligation_date` + the supplier's credit days | supplier ledger pages | per-supplier total |
| Manufacturer and worker payables | `opening_obligations` with `purpose = 'MANUFACTURER'` / `'WORKER'` | their ledger pages | per-party totals |
| Active loans | `loans` row via the same rules as `POST /loans/existing` (`party_id` of a `LENDER` party, original `start_date`, original `principal_amount`, `repaid_before_go_live` = original − outstanding); no `LOAN_RECEIVED` transaction (the money is already in the account opening balances); interest history is not imported | loan documents | principal outstanding |
| Active chits | `chits` row via the same rules as `POST /chits` with `prior_contributions_count` and `prior_contributions_amount` = contributions paid before go-live; no cash transaction | chit book | amount paid so far |
Open stock in progress (silk with manufacturers, batches in the middle) is entered as `EXTERNAL_WIP` / `INTERNAL_WIP` opening movements with a note naming the job.
Opening obligations use the table `opening_obligations` (`04-DATA-MODEL.md` section 8.1; insert-only): they appear in outstanding/payable queries and are reduced
only by payment allocations.

### 12.2 Import mechanism
A one-off command-line runner, active only with profile `import` (never enabled in normal operation): `java -jar app.jar --spring.profiles.active=prod,import --import.dir=/data/golive --import.dry-run=true`.
Reads UTF-8 CSV files (`parties.csv`, `opening-stock.csv`, `accounts.csv`, `receivables.csv`, `payables.csv`, `loans.csv`, `chits.csv`), validates each row with the same
validators as the API, runs each file in **one transaction** (all rows or none), and refuses to run when the database already contains any `OPENING_BALANCE` movement, `OPENING_BALANCE` financial transaction or `opening_obligations` row (one complete import per database; no marker table). Each imported item writes an `audit_log` row `OPENING_BALANCE_IMPORTED`.
`dry-run=true` (the default) runs everything and rolls back, printing the reconciliation totals below.

### 12.3 Reconciliation and sign-off
1. Freeze period: no new notebook entries for the imported categories while the import runs (target: one evening).
2. Dry run, then real import on a copy of production (a restored backup) first, then on production.
3. The import prints a reconciliation report: counts per file; total stock kg per product/location; balance per account; total receivables and payables per party and overall; loans; chits.
4. The owner compares every figure with the notebook/physical count and signs a **sign-off sheet** (date, figures, signature). Differences > Rs 1.00 or > 0.001 kg block go-live until explained.
5. After sign-off the `import` profile is never enabled again; the runner would refuse anyway because opening records exist, and `opening_obligations` is insert-only (immutability trigger, `04-DATA-MODEL.md` section 14).
6. A full backup is taken and kept as the `golive-baseline` dump (never deleted).

### 12.4 Parallel run (2-4 weeks)
Both the notebook and Nexora are updated daily. Each working day the owner (or the developer with the owner) compares: stock per product/location, cash and bank balances, customer
and supplier outstanding for the day's active parties. **Exit criteria:** 10 consecutive working days with no unexplained difference (limit Rs 1.00 and 0.001 kg), at least one
full month-end payables review done, and a successful restore drill. Until then the notebook remains the reference. After the exit the notebook is archived (kept, not edited).

## 13. Release checklist (each production release)
- [ ] `./gradlew build` green locally and in CI (tests, coverage >= 80 %, `bootJar`).
- [ ] `./gradlew dependencyCheckAnalyze` passes (no CVSS >= 7); Trivy image scan clean.
- [ ] All new migrations applied on an empty database **and** on a copy of production (restored backup); `ddl-auto=validate` passes.
- [ ] Migration follows expand/contract (previous app version still works with the new schema).
- [ ] OpenAPI export updated (`openapi.json`) and the desktop client's contract unchanged or versioned.
- [ ] `docker compose config` valid; image runs as UID 10001 and is healthy in < 60 s.
- [ ] Pre-deploy backup succeeded; last restore drill is < 31 days old.
- [ ] `CHANGELOG.md` updated; version tag `vX.Y.Z` created; release notes list migrations and config changes.
- [ ] Deployment approved in the GitHub Environment; smoke test passed (`UP`, `401` on a protected route).
- [ ] After deploy: login, create-and-reverse of a test payment on a test account, dashboard loads (executed by the owner or developer, then reversed and documented).
- [ ] Monitoring green for 30 minutes (no 5xx burst, disk and memory normal).
- [ ] For v1.0.0 only: go-live sign-off sheet signed (section 12.3), parallel run plan started, owner trained (30-minute session + one-page quick guide).

## 14. Decisions made while writing
| Decision | Reason |
|---|---|
| Database roles `postgres` (init/admin), `nexora_owner` (Flyway), `nexora_app` (runtime) created by an init script | enforces least privilege and the append-only ledgers (ADR-032, `09-SECURITY.md` section 10.3); the `postgres` account is never used by the app |
| `app` container filesystem read-only, all Linux capabilities dropped, `no-new-privileges`, memory limits | defence in depth for a single-host deployment |
| Health probes via `/actuator/health/liveness|readiness`, details hidden in prod | readiness includes the database; public endpoint leaks nothing |
| RPO 24 h / RTO 2 h with nightly `pg_dump` (no WAL archiving) | matches business size; WAL archiving can be added if the owner needs a lower RPO |
| Backups encrypted with `age` before upload; write-only bucket credential; 30-day lifecycle on the bucket | protects data and prevents an attacker from deleting backups |
| Migrations must be expand/contract; automatic rollback re-deploys the previous image only | avoids schema rollbacks; the pre-deploy dump covers data-damaging migrations |
| Go-live import is a profile-gated runner with dry-run and per-file transactions, not an HTTP endpoint | no public attack surface and no client-writable ledger endpoint (Rule R1) |
| Opening receivables/payables modelled as the insert-only table `opening_obligations`, allocatable by payments | resolves the roadmap gap; structure in `04-DATA-MODEL.md` section 8.1 |
| `bootJar` renamed to `app.jar` and `buildInfo()` enabled | stable Docker `COPY`/`ENTRYPOINT`, version visible at `/actuator/info` |
| Alert channel is vendor-neutral (email + phone push); concrete vendors named only as examples | the owner picks the service; thresholds are the requirement |
| A GitHub Environment named `production` with required reviewers provides the manual approval | built-in feature; no extra tooling |

Inconsistencies noted between existing files and this plan: `Nexora-backend/.env.example` currently has `DB_PASSWORD=change-me` while the app's dev default and the compose-based
workflow use `nexora_dev` (section 5.1 sets it to `nexora_dev`); `docker-compose.yml` (dev) requires `DB_PASSWORD` to be exported or present in `.env`, otherwise the container starts with an empty password;
`.github/workflows/ci.yml` currently only runs `./gradlew build` (no audit, secret scan, image, or nightly job).
