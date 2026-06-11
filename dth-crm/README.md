# DTH CRM

B2B CRM portal built on **Jmix 2.8** with an embedded AI agent (`com.vn:ai-agent-starter`).
Vietnamese is the default locale; English is supported as a fallback.

## Quick start (local dev)

```powershell
# 1. Postgres + pgvector
docker compose up -d

# 2. Copy template and fill OpenRouter key
Copy-Item .env.example .env
# edit .env → OPENROUTER_API_KEY=sk-or-v1-...

# 3. Boot the app
.\gradlew.bat bootRun
# → http://localhost:8080  (login: admin / admin)
```

The `local` Spring profile is active by default → on first boot the demo data generator seeds
30 clients, 187 orders, 142 invoices and 189 payments from `src/main/resources/demo-data/*.csv`.

## Stack

| Layer | Tech |
|---|---|
| Runtime | Java 21 (Eclipse Temurin) |
| Framework | Jmix 2.8.1 (Spring Boot 3.5, Vaadin 24) |
| Main store | PostgreSQL 16 |
| Agent store | PostgreSQL 16 + **pgvector** (required by the AI add-on for RAG) |
| AI provider | OpenRouter (default model `qwen/qwen3.6-35b-a3b`) |
| AI add-on | `com.vn:ai-agent-starter:1.1.1-SNAPSHOT` (internal Nexus) |

## Deployment

### Build & push image

CI is wired in [`.github/workflows/build-image.yml`](.github/workflows/build-image.yml).
Pushes to `main` and tags `v*.*.*` publish to **GHCR** as `ghcr.io/<owner>/dth-crm:<tag>`.

Local build:

```powershell
docker build -t dth-crm:local .
```

### Run the production stack

```powershell
# Create .env next to docker-compose.prod.yml
# Required keys:
#   IMAGE_TAG=ghcr.io/<owner>/dth-crm:latest
#   POSTGRES_PASSWORD=<strong>
#   OPENROUTER_API_KEY=sk-or-v1-...

docker compose -f docker-compose.prod.yml --env-file .env up -d
docker compose -f docker-compose.prod.yml --env-file .env logs -f app
```

The stack provisions:
- `dthcrm-db-main` — main store (`postgres:16`)
- `dthcrm-db-agentstore` — `pgvector/pgvector:pg16` (required by ai-agent for RAG)
- `dthcrm-app` — the Jmix app behind a HEALTHCHECK probe; runs as non-root user

### Deploy from CI (Ubuntu host, SSH)

[`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) is a manual workflow that
SCPs `docker-compose.prod.yml` to the host, writes `.env` from GitHub secrets, then runs
`docker compose pull + up -d` and waits for the container's HEALTHCHECK to flip to
`healthy` (rolls back nothing on failure — dumps `docker logs --tail 200` instead).

**One-time host setup**

```bash
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2
sudo usermod -aG docker $USER && newgrp docker
sudo mkdir -p /opt/dth-crm && sudo chown $USER:$USER /opt/dth-crm
```

**GitHub repo settings** (Settings → Environments → `staging` / `production`):

| Type | Name | Purpose |
|---|---|---|
| Secret | `DEPLOY_HOST` | e.g. `crm.dth.vn` or IP |
| Secret | `DEPLOY_USER` | SSH user with docker group |
| Secret | `DEPLOY_SSH_KEY` | Private key (matches host's `~/.ssh/authorized_keys`) |
| Secret | `DEPLOY_PORT` *(optional)* | SSH port if not 22 |
| Secret | `POSTGRES_PASSWORD` | Strong password for both DBs |
| Secret | `OPENROUTER_API_KEY` | LLM provider key |
| Secret | `GHCR_READ_TOKEN` | GitHub PAT with `read:packages` (only needed if image is private) |
| Secret | `GHCR_READ_USER` *(optional)* | PAT owner, defaults to `github.actor` |
| Var | `OPENROUTER_MODEL` *(optional)* | Override default `qwen/qwen3.6-35b-a3b` |
| Var | `APP_PORT` *(optional)* | Override default `8080` on host |

**Trigger:** Actions → Deploy → Run workflow → pick image tag (e.g. `v1.0.0`) and environment.

## Configuration matrix

All overrides are env vars. Defaults in [`application.properties`](src/main/resources/application.properties);
production overrides in [`application-prod.properties`](src/main/resources/application-prod.properties)
(activated by `DTH_ACTIVE_PROFILE=prod`).

| Env var | Default | Purpose |
|---|---|---|
| `DTH_SERVER_PORT` | `8080` | HTTP port |
| `MAIN_DATASOURCE_URL` | `jdbc:postgresql://localhost:5555/dthcrm` | Main CRM DB |
| `MAIN_DATASOURCE_USERNAME` / `_PASSWORD` | `postgres` / `postgres` | Main DB creds |
| `AGENTSTORE_DATASOURCE_URL` | `jdbc:postgresql://localhost:5556/agentstore` | AI agent DB (requires pgvector) |
| `AGENTSTORE_DATASOURCE_USERNAME` / `_PASSWORD` | `postgres` / `postgres` | Agent store creds |
| `OPENROUTER_API_KEY` | *(placeholder)* | **Required** — chat fails with 401 if unset |
| `OPENROUTER_MODEL` | `qwen/qwen3.6-35b-a3b` | LLM slug (must match add-on's `default-params.yaml`) |
| `OPENROUTER_EMBEDDING_MODEL` | `qwen/qwen3-embedding-4b` | Embedding slug for RAG |
| `DTH_ACTIVE_PROFILE` | `local` | `prod` disables demo data + bumps pool sizes |

## CI/CD

| Workflow | Trigger | Action |
|---|---|---|
| [`tests.yml`](.github/workflows/tests.yml) | push to main, PR | spins up Postgres services, runs `gradlew test`, publishes JUnit report |
| [`build-image.yml`](.github/workflows/build-image.yml) | push to main, tag `v*`, manual | multi-stage Docker build → push to GHCR with semver / sha / latest tags |
| [`deploy.yml`](.github/workflows/deploy.yml) | manual | deploys a chosen image tag to staging/production (target template) |

Dependabot ([`.github/dependabot.yml`](.github/dependabot.yml)) watches Gradle, GitHub
Actions, and Docker base images weekly. Jmix BOM and `com.vn:ai-agent*` are explicitly
**ignored** so they only move on intentional coordinated bumps with the add-on team.

## AI extension points

The add-on ships 6 generic data tools (`list_entities` / `describe_entity` / `find_records`
/ `get_record` / `count_records` / `get_related_records`) plus the SPI surfaces below. This
project adds CRM-specific reports via the `ToolContributor` SPI:

| File | Role |
|---|---|
| [`CrmReportToolContributor`](src/main/java/com/company/crm/ai/tool/CrmReportToolContributor.java) | Exposes `getAvailableReports`, `getReportsByCodes`, `runReport` over the 3 shipped reports |
| [`AiReportExecutionService`](src/main/java/com/company/crm/ai/report/run/AiReportExecutionService.java) | Runs `client-360-report` / `category-cashflow-risk-report` / `invoice-report` and returns text content |

Add new SPIs by implementing `com.vn.agent.spi.*` (`ToolContributor`, `ContextContributor`,
`PromptContextContributor`, `ToolGuard`, `AuditListener`, `CustomIngester`) as
`@Component` beans — auto-discovered.

## Repo layout

```
.
├── .github/workflows/        # tests, build-image, deploy
├── docker-compose.yml        # local dev (2 Postgres)
├── docker-compose.prod.yml   # prod (app + 2 Postgres)
├── Dockerfile                # multi-stage (deps cache → build → JRE runtime)
├── src/main/
│   ├── java/com/company/crm/     # ported from jmix-crm reference
│   │   ├── ai/                       # CRM-specific AI tools (report contributor)
│   │   ├── app/, model/, view/, security/, report/
│   │   └── CrmJmixModuleConfiguration.java   # @JmixModule marker
│   ├── java/com/vn/dth/crm/      # entry point (DthCrmApplication)
│   ├── resources/com/company/crm/messages_{en,vi}.properties
│   └── frontend/themes/crm/      # CRM theme
```

## License

Internal — DTH.
