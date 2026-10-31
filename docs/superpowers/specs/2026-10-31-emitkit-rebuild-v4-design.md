# EmitKit Rebuild — v4: Observability & Kubernetes

**Date:** 2026-10-31
**Status:** Approved design, pending spec review
**Depends on:** [Overview](2026-10-31-emitkit-rebuild-overview-design.md), [v1](2026-10-31-emitkit-rebuild-v1-design.md) (shipped). Does **not** depend on v2 or v3.

---

## 1. Goal & exit criteria

v1–v3 run on a single Docker Compose host with health checks and plain logs only. v4 adds full observability and moves production to Kubernetes with highly available data stores. It is a separate phase so these tools can be learned first. It can start any time after v1.

Observability comes first (§3), and it runs on the **existing Compose stack**. This gives visibility before the Kubernetes move and makes the move itself easier to check.

**Exit criteria**
1. Metrics, traces and logs for api, worker, sandbox, RabbitMQ, Postgres, Redis and Vault are visible in Grafana, with the dashboards in §3.4.
2. The alerts in §3.5 fire in a staging drill and reach the on-call channel.
3. Production runs on Kubernetes through the Helm chart. Losing any one node causes no downtime (tested in staging by draining a node during a run).
4. The availability targets in §6 are met for 30 days in a row.
5. The Compose production stack is shut down, and its volumes are kept read-only for 30 days, then deleted.

---

## 2. Scope

| Group | Items |
|---|---|
| Observability (feature 76) | Metrics (Micrometer → Prometheus), tracing (OpenTelemetry → Tempo), structured logs (→ Loki), Grafana dashboards, Alertmanager alerts |
| Kubernetes | Helm chart, ingress + TLS, Kubernetes Job sandbox, NetworkPolicies, autoscaling, rolling deploys |
| HA data stores | Postgres (CloudNativePG), RabbitMQ (cluster operator), Redis (Sentinel), RustFS (distributed mode), Vault (3-node Raft + auto-unseal) |
| Migration | Compose → Kubernetes cutover with a short planned downtime |

---

## 3. Observability (on Compose first, then carried into Kubernetes)

### 3.1 Metrics
- Spring Boot Actuator + Micrometer with the Prometheus registry. Endpoint `/actuator/prometheus` on the management port (never exposed publicly).
- App metrics:

| Metric | Type | Labels |
|---|---|---|
| `emitkit.runs.started` / `emitkit.runs.finished` | counter | `status`, `trigger` |
| `emitkit.runs.duration` | timer (histogram) | `status` |
| `emitkit.pipeline.step.duration` | timer | `step`, `status` |
| `emitkit.generator.duration` | timer | `generator` |
| `emitkit.sandbox.active` / `emitkit.sandbox.wait` | gauge / timer | — |
| `emitkit.github.ratelimit.remaining` | gauge | — (no per-user labels, to keep cardinality low; the lowest value wins) |
| `emitkit.vault.requests` | timer | `op`, `status` |
| `emitkit.ai.requests` | timer | `provider`, `feature`, `status` |
| `emitkit.webhooks.received` | counter | `event`, `result` |

- Labels never include org, project, user or run IDs (cardinality). Per-run details belong in traces and logs.
- Infrastructure exporters: postgres-exporter, the RabbitMQ Prometheus plugin, redis-exporter, Vault telemetry (`/v1/sys/metrics`), RustFS metrics, node-exporter, cAdvisor (Compose) / kube-state-metrics (Kubernetes).

### 3.2 Tracing
- OpenTelemetry through Spring Boot's OpenTelemetry support (Micrometer Tracing bridge), exported over OTLP to an **OpenTelemetry Collector**, then to **Grafana Tempo**.
- Trace context is passed across RabbitMQ in message headers, so one trace covers: HTTP request → enqueue → worker → each pipeline step → GitHub calls → sandbox.
- Spans per pipeline step and per generator, with attributes `run.id`, `project.id`, `step`.
- Sampling: 100% of runs (low volume), 10% of plain API requests, 100% of errors (tail sampling in the Collector).

### 3.3 Logs
- Spring Boot structured logging in JSON (`logging.structured.format.console`), with `traceId`, `spanId`, `runId`, `orgId` from the MDC. This replaces the v1 plain-text format.
- Shipped by **Grafana Alloy** to **Loki**. Retention 30 days.
- Grafana links each log line to its trace by `traceId`, and each trace to its logs.
- Secrets and decrypted values are never logged. A Logback filter masks anything shaped like a token (`ghp_…`, `ek_…`, `vault:v…`) as a last line of defense, with a unit test.

### 3.4 Dashboards (provisioned as code from `/deploy/observability/dashboards/*.json`)
1. **Overview:** request rate, error rate, p95 latency, runs per minute by status, queue depth.
2. **Pipeline:** duration per step (p50/p95), failures by `failure_code`, sandbox wait time and slots in use.
3. **Dependencies:** GitHub rate limit, Vault latency and errors, AI latency and errors, RustFS errors.
4. **Infrastructure:** CPU/memory per service, Postgres connections and slow queries, RabbitMQ queues/DLQ, Redis memory.

### 3.5 Alerts (Alertmanager → Slack/email)
| Alert | Condition |
|---|---|
| API error rate high | 5xx > 2% for 5 min |
| Runs failing | failed / finished > 30% over 15 min (with at least 5 runs) |
| DLQ not empty | `generation.dlq` > 0 for 10 min |
| Queue backlog | `generation.jobs` > 50 for 10 min |
| Vault sealed or unreachable | health check failing for 2 min |
| GitHub rate limit low | remaining < 200 |
| Disk / volume | > 80% used |
| Backup missing | no successful Postgres or Vault backup in 26 h |
| Certificate expiring | < 14 days |

Each alert links to a short runbook in `/docs/runbooks/{alert}.md`.

### 3.6 Compose stack for observability
`docker-compose.observability.yml` adds Prometheus, Alertmanager, Grafana, Loki, Tempo, Alloy and the OTel Collector. Grafana sits behind Caddy with its own login. The same config files are reused by the Kubernetes setup (§4.6).

---

## 4. Kubernetes

### 4.1 Cluster & chart
- Any CNCF-conformant cluster (managed, e.g. EKS/GKE/AKS, or self-hosted k3s). At least 3 worker nodes across zones where possible.
- Helm chart `deploy/helm/emitkit` with sub-charts or dependencies for the operators below. One `values-{env}.yaml` per environment.
- Deployments: `api` (min 2, HPA on CPU and request rate up to 6), `worker` (min 2, HPA on `generation.jobs` queue depth through KEDA's RabbitMQ scaler, up to 10), `web` (static frontend through nginx, or served by the ingress).
- PodDisruptionBudgets (`minAvailable: 1`) for api and worker; topology spread across nodes.
- Rolling updates (`maxUnavailable: 0`). Workers handle `SIGTERM` by finishing the current step, then nack-requeueing the message (grace period 300 s).
- Flyway migrations run as a Helm pre-upgrade Job, not at app start-up.

### 4.2 Ingress & TLS
- ingress-nginx (or Gateway API with Envoy Gateway) replaces Caddy. TLS from **cert-manager** with Let's Encrypt.
- SSE: proxy buffering off and a read timeout of 1 h on the log stream and notification stream paths.

### 4.3 Sandbox as Kubernetes Jobs
A new `KubernetesSandboxRunner` (fabric8 `kubernetes-client`) next to v1's `DockerSandboxRunner`, chosen with `emitkit.sandbox.mode=kubernetes`. It keeps the same security properties:
- a dedicated `emitkit-sandbox` namespace, NetworkPolicy deny-all (publish Jobs from v2: egress to the egress proxy only);
- read-only root filesystem, `emptyDir` (memory-backed) workspace;
- 1 CPU / 1 Gi limits, `activeDeadlineSeconds=180`, `ttlSecondsAfterFinished=60`;
- non-root, `allowPrivilegeEscalation: false`, all capabilities dropped, seccomp `RuntimeDefault`, Pod Security Admission `restricted`;
- optional: gVisor `RuntimeClass` for an extra isolation layer, if the cluster supports it;
- input and output through presigned RustFS URLs (an init container downloads; the main container uploads results), because `copyArchiveToContainer` isn't available for Jobs;
- the worker's ServiceAccount may only create, watch and delete Jobs and read Pod logs in `emitkit-sandbox`.

### 4.4 HA data stores
| Store | v1–v3 (Compose) | v4 (Kubernetes) |
|---|---|---|
| Postgres | single container + nightly dump | **CloudNativePG**: 3 instances (1 primary, 2 replicas), automatic failover, continuous backup (WAL + base backups) to object storage, point-in-time restore |
| RabbitMQ | single container | **RabbitMQ Cluster Operator**: 3 nodes, **quorum queues** for `generation.jobs`, retry and DLQ queues |
| Redis | single container | Redis with **Sentinel** (1 primary, 2 replicas). Spring Session and pub/sub configured for Sentinel |
| RustFS | single container | RustFS **distributed mode** (4 nodes, erasure coding), or the cloud provider's S3 service, since the app only needs the S3 API |
| Vault | single server, manual unseal | 3-node **Raft** cluster through the official Vault Helm chart, **auto-unseal** with a cloud KMS, automated Raft snapshots to object storage |

### 4.5 Vault on Kubernetes
- The auth method changes from AppRole to **Kubernetes auth**: `api` and `worker` ServiceAccounts log in with their projected token. The policies stay the same (overview §3.1).
- `spring-vault-core` switches to `KubernetesAuthentication`. That's a config change only; `SecretCipher` is unchanged.
- The Vault health indicator joins the **readiness** group (not liveness), so a Vault outage takes pods out of rotation without restarting them.

### 4.6 Observability on Kubernetes
- **kube-prometheus-stack** (Prometheus Operator, Alertmanager, Grafana, kube-state-metrics, node-exporter). Apps are scraped through `ServiceMonitor` resources from the Helm chart.
- Loki, Tempo and the OTel Collector through their Helm charts. Alloy runs as a DaemonSet to collect pod logs.
- Dashboards and alert rules are the same files as in §3.4/§3.5, loaded as ConfigMaps and `PrometheusRule` resources.

### 4.7 Config secrets
App config secrets (DB password, RabbitMQ/Redis passwords, GitHub OAuth client secret, SMTP) are Kubernetes Secrets managed with **Sealed Secrets**, so the encrypted manifests can be committed to git. Vault stays Transit-only.

---

## 5. Migration: Compose → Kubernetes

1. Build and test the cluster in staging with a copy of production data.
2. Announce a maintenance window (target < 30 min).
3. Put the Compose stack in maintenance mode: stop accepting new runs; webhooks return 503 so GitHub redelivers later; wait for running runs to finish.
4. Copy the data:
   - Postgres: `pg_dump` → CloudNativePG import;
   - Vault: Raft snapshot → restore into the new cluster, then unseal through auto-unseal;
   - RustFS: mirror the artifacts bucket (`mc mirror` or `rclone`).
5. Run the smoke test suite against the cluster (login, open project, dry run, real run on a test repo).
6. Switch DNS to the ingress. Lower the TTL to 60 s the day before.
7. Leave Compose running read-only for 24 h as a fallback. Rollback = switch DNS back (any runs done on Kubernetes in the meantime are replayed from GitHub webhooks).

Webhook URLs don't change (same domain), so no GitHub webhooks need re-registering.

---

## 6. Non-functional targets (replacing v1 §11's single-host values)

| Area | Target |
|---|---|
| API availability | 99.5% monthly |
| Vault availability | 99.9% |
| Recovery point (RPO) | ≤ 5 min (WAL archiving) |
| Recovery time (RTO) | ≤ 30 min for a full-region restore from backups |
| Throughput | Scales with worker replicas; 4 sandbox Jobs per worker by default |

---

## 7. Testing

- Helm: `helm lint`, `chart-testing` (`ct lint` and `ct install`) on a **kind** cluster in CI, `kubeconform` schema checks.
- Sandbox: integration test of `KubernetesSandboxRunner` on kind. Includes a test that a Job **can't** reach the network (curl must fail) and can't run as root.
- Resilience in staging: kill the Postgres primary during a run (failover, run continues after retry); drain a node (no failed requests); restart a RabbitMQ node (no lost jobs).
- Observability: a test that `/actuator/prometheus` exposes every metric in §3.1; a trace-propagation test across RabbitMQ (Testcontainers + an in-memory span exporter).
- Restore drill: restore Postgres and Vault from backups into a scratch namespace every quarter.

---

## 8. Milestones

1. **Metrics:** Micrometer metrics, Prometheus + Grafana on Compose, the first two dashboards.
2. **Logs & traces:** structured logs, Loki, OpenTelemetry + Tempo, log ↔ trace links.
3. **Alerts & runbooks:** Alertmanager rules, runbooks, staging drill.
4. **Kubernetes basics:** Helm chart for api, worker and web; ingress + cert-manager; Flyway Job; staging cluster.
5. **Kubernetes sandbox:** `KubernetesSandboxRunner`, sandbox namespace, NetworkPolicies, tests on kind.
6. **HA data stores:** CloudNativePG, RabbitMQ operator with quorum queues, Redis Sentinel, RustFS distributed, Vault HA + Kubernetes auth.
7. **Observability on Kubernetes:** kube-prometheus-stack, Loki, Tempo, Alloy, ServiceMonitors.
8. **Migration & cutover:** rehearsal in staging, production cutover, Compose shutdown.

Each milestone gets its own implementation plan. Milestones 1–3 also work as a learning track for the observability tools, and 4–7 for Kubernetes.
