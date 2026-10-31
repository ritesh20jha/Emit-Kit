# WRK-00 · worker-app skeleton + RabbitMQ topology

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A bootable worker-app that declares the jobs, retry, DLQ and control exchanges/queues, plus the shared message type.

**Lane:** CORE · **Depends on:** [CORE-01](CORE-01-common-errors-ids.md) · **Size budget:** ~500 changed lines (hard limit 1000) · **Branch:** `ticket/wrk-00`

**Spec:** ov §3 messaging

**Global constraints & review focus:** see [README](README.md). They apply to every task below.

---

## Files

- Create: `backend/common/src/main/java/dev/emitkit/common/messaging/Queues.java`, `GenerationJob.java`, `ControlMessage.java`
- Create: `backend/worker-app/src/main/java/dev/emitkit/worker/WorkerApplication.java`, `.../worker/messaging/RabbitTopologyConfig.java`, `GenerationJobListener.java` (logs and acks; real work in WRK-11)
- Create: `backend/worker-app/src/main/resources/application.yml`
- Test: `backend/worker-app/src/test/java/dev/emitkit/worker/messaging/RabbitTopologyIT.java`

## Interfaces

- **Consumes:** CORE-01 types.
- **Produces:**
```java
public final class Queues {
  public static final String JOBS_EXCHANGE = "emitkit.jobs";        // direct
  public static final String JOBS_QUEUE = "generation.jobs";        // routing key "generation"
  public static final String JOBS_ROUTING_KEY = "generation";
  public static final List<String> RETRY_QUEUES =
      List.of("generation.retry.10s", "generation.retry.60s", "generation.retry.300s");
  public static final String DLQ = "generation.dlq";
  public static final String CONTROL_EXCHANGE = "emitkit.control";  // fanout
  public static final int MAX_ATTEMPTS = 3;
  public static String retryQueueFor(int attempt);   // 1→10s, 2→60s, 3→300s
}
public record GenerationJob(String runId, int attempt) {}
public record ControlMessage(String type, String runId) {}          // type "CANCEL"
```
- Retry queues: `x-message-ttl` 10000/60000/300000, `x-dead-letter-exchange=emitkit.jobs`, `x-dead-letter-routing-key=generation`. TTLs are overridable by `emitkit.worker.retry-ttls` (test uses 200 ms).
- Each worker binds an exclusive, auto-delete anonymous queue to `emitkit.control`.
- Jackson JSON message converter; listener `prefetch=1`, manual ack, `concurrency` from `emitkit.worker.concurrency` (default 4).

## Tasks

### Task 1: Topology

- [ ] **Step 1: Write the failing test**

```java
@SpringBootTest @Testcontainers
class RabbitTopologyIT {
  @Container @ServiceConnection static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3-management");
  @Test void retryQueueDeadLettersBackToJobs() {
    // emitkit.worker.retry-ttls=200ms,200ms,200ms; listener disabled via property for this test
    rabbitTemplate.convertAndSend("", "generation.retry.10s", new GenerationJob("r1", 1));
    var msg = rabbitTemplate.receiveAndConvert("generation.jobs", 5000);
    assertThat(msg).isEqualTo(new GenerationJob("r1", 1));
  }
  @Test void controlFanoutReachesWorkerQueue() { /* publish ControlMessage("CANCEL","r1") → receiver bean got it */ }
  @Test void retryQueueForAttempt() {
    assertThat(Queues.retryQueueFor(1)).isEqualTo("generation.retry.10s");
    assertThat(Queues.retryQueueFor(3)).isEqualTo("generation.retry.300s");
  }
}
```

- [ ] **Step 2: Run it — expect FAIL**

  Run: `./gradlew :worker-app:test --tests '*RabbitTopologyIT'`

- [ ] **Step 3: Implement** — `RabbitTopologyConfig` declares `Declarables`; `GenerationJobListener` gated by `emitkit.worker.listener.enabled` (default true).

- [ ] **Step 4: Run it — expect PASS**

  Run: `./gradlew :worker-app:test --tests '*RabbitTopologyIT'`

- [ ] **Step 5: Commit** — `git commit -m "feat(worker): rabbitmq topology and job message"`

---

## Done when

- [ ] All tasks above are committed on `ticket/wrk-00`.
- [ ] CI is green, including `scripts/pr-size.sh` (≤ 1000 counted lines).
- [ ] PR description lists what this ticket **Produces** so dependent tickets can start.
