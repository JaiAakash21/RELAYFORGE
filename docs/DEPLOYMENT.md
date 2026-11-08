# Deployment

## Local

```bash
./infra/scripts/bootstrap.sh    # generates real secrets into a git-ignored .env
docker compose up --build
```

| Service | URL |
|---|---|
| Dashboard | <http://localhost:3000> |
| API + OpenAPI | <http://localhost:8080/swagger-ui.html> |
| Grafana | <http://localhost:3001> |
| Prometheus | <http://localhost:9090> |

The compose file refuses to start without `POSTGRES_PASSWORD`,
`RELAYFORGE_JWT_SECRET`, `RELAYFORGE_ENCRYPTION_KEY` and
`GRAFANA_ADMIN_PASSWORD`, with a message naming the variable and how to
generate it.

## Running without Docker

Useful for development, and what was used to verify this project.

```bash
# Dependencies
docker run -d --name rf-pg -p 5432:5432 \
  -e POSTGRES_DB=relayforge -e POSTGRES_USER=relayforge -e POSTGRES_PASSWORD=devpassword \
  postgres:16-alpine
docker run -d --name rf-redis -p 6379:6379 redis:7.4-alpine
docker run -d --name rf-kafka -p 9092:9092 \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  apache/kafka:3.9.1
```

```bash
mvn -DskipTests package

export POSTGRES_HOST=localhost POSTGRES_PASSWORD=devpassword
export REDIS_HOST=localhost KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export RELAYFORGE_JWT_SECRET="$(openssl rand -base64 64 | tr -d '\n')"
export RELAYFORGE_ENCRYPTION_KEY="$(openssl rand -base64 32 | tr -d '\n')"
export RELAYFORGE_EGRESS_DEV_ALLOWLIST=localhost
export SIMULATOR_BASE_URL=http://localhost:8081

java -jar webhook-simulator/target/relayforge-webhook-simulator.jar &
java -jar backend/target/relayforge-backend.jar &
(cd frontend && npm ci && npm run build && RELAYFORGE_API_INTERNAL_URL=http://localhost:8080 npm start)
```

## Scaling

```bash
docker compose up --scale worker=4
```

Workers coordinate through the database (leases) and Kafka (a consumer group),
so adding one needs no configuration change. Delivery parallelism is bounded
by **partitions × instances**: the topic has 6 partitions, so beyond 6 worker
instances the extras idle. Raise `partitions` in `KafkaConfig` to go further.

**Scale workers up to clear a backlog — never restart them.** A restart expires
leases, which forces recovery work on top of the backlog and makes it worse.

The API scales freely; it is stateless. Only one process needs to be a worker,
but more than one is safe at every point: the outbox claim, the dispatcher and
the delivery claim are all contention-safe by construction.

## Configuration

Everything is `relayforge.*` in `application.yml`, overridable by environment
variable. Secrets have no defaults and are validated at startup.

| Variable | Default | Notes |
|---|---|---|
| `RELAYFORGE_ROLE` | `all` | `api`, `worker`, or `all` |
| `RELAYFORGE_JWT_SECRET` | — | ≥64 bytes. Changing it invalidates every token |
| `RELAYFORGE_ENCRYPTION_KEY` | — | base64 32 bytes. **Changing it makes every stored webhook secret unreadable** |
| `RELAYFORGE_EGRESS_PROFILE` | `dev` | `prod` enforces https and disables the allowlist |
| `RELAYFORGE_EGRESS_DEV_ALLOWLIST` | `webhook-simulator,localhost,…` | Only in `dev` |
| `RELAYFORGE_DELIVERY_CONCURRENCY` | `4` | Consumer threads per worker |
| `RELAYFORGE_RETRY_MAX_ATTEMPTS` | `6` | Overridable per endpoint |
| `RELAYFORGE_CORS_ORIGINS` | `http://localhost:3000` | Never wildcarded |
| `RELAYFORGE_DEMO_SEED_ENABLED` | `false` | An account with a known password is a backdoor |

Back up the encryption key. Losing it is unrecoverable.

## Health checks

| Probe | Checks | Use |
|---|---|---|
| `/actuator/health/liveness` | The process is alive | Restart policy |
| `/actuator/health/readiness` | Database and Redis reachable | Load-balancer membership |
| `/actuator/health` | The above, plus pipeline depth | Dashboards and alerts |

A delivery backlog reports **`DEGRADED`, not `DOWN`**, and liveness
deliberately excludes it. Reporting DOWN would make an orchestrator restart or
drain the instance, which is the wrong response to a backlog: fewer workers
makes it worse.

## Production checklist

Before real traffic:

- [ ] `RELAYFORGE_EGRESS_PROFILE=prod` and an **empty dev allowlist**
- [ ] **Network-level egress filtering** — the most important item here.
      Application SSRF validation is defence in depth, not a boundary
- [ ] Secrets from a secret manager, not environment variables
- [ ] TLS everywhere, including between services
- [ ] An edge proxy that overwrites `X-Forwarded-For`
- [ ] Managed PostgreSQL with PITR; **back up the encryption key separately**
- [ ] Kafka with ≥3 brokers and replication factor 3 (compose uses 1)
- [ ] `RELAYFORGE_DEMO_SEED_ENABLED=false`
- [ ] Alert routing wired to `infra/prometheus/alerts.yml`
- [ ] Tracing sample rate reduced from 1.0

## Images

Multi-stage, non-root, with dependency layers cached separately from
application code. The backend uses Spring Boot layertools so a redeploy ships a
few hundred KB rather than ~60 MB; the frontend uses Next.js standalone output
(~120 MB rather than ~600 MB).

```bash
docker compose build
```

## CI/CD

| Workflow | Runs |
|---|---|
| `backend.yml` | Unit tests, then Testcontainers integration separately, then packaging |
| `frontend.yml` | Lint, type check, production build |
| `e2e.yml` | Builds the stack, waits for readiness, runs Playwright |
| `security.yml` | Secret scan over full history, dependency review, npm audit |

Unit and integration are split so a logic error surfaces in two minutes rather
than fifteen. Nothing deploys automatically — publishing to a production
environment requires explicit authorisation.
