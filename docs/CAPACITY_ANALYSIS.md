# InterviewIQ — Concurrency & Capacity Analysis

**Date:** 1 August 2026
**Question:** does capping HikariCP at 8 connections per task limit us to 8 concurrent interviews? Target is 25+ concurrent at MVP launch.

---

## 1. Short answer: no. Connections are not sessions.

A database connection is held **only while a query is executing** — single-digit milliseconds — not for the duration of an interview. A candidate spends 60–90 seconds answering a question and the database is completely idle for all of it.

The arithmetic, for a 30-minute interview with 15 questions:

| | |
|---|---|
| Queries per interview (start + 3/question + completion) | ~53 |
| Time per query | ~3–5 ms |
| **Total connection time per interview** | **~200 ms** |
| Spread over | 1,800 seconds |
| **Fraction of one connection consumed** | **~0.011%** |

**25 concurrent interviews consume less than half of one database connection.** Even at a pessimistic 50 ms per query — 10× worse than realistic — 25 concurrent interviews use about 4% of a single connection.

A pool of 8 isn't a limit of 8 users. It's a limit of **8 queries executing at the same instant**, which at your load profile would support several thousand concurrent interviews.

**I'm also correcting a number from my last message.** I said `db.t4g.micro` allows ~85 connections. The RDS formula (`DBInstanceClassMemory / 9531392`) gives **~112** for a 1 GiB instance. Verify with `SHOW max_connections;` once it's provisioned, but ~112 is the right planning figure.

**Revised recommendation: 10 connections per task.** At the 6-task ceiling that's 60 against ~112 available — comfortable headroom, and far more than the workload needs.

---

## 2. But there IS a real 25-concurrent problem, and it's somewhere else

Two of them, actually. Both are caused by the move to autoscaling, and both are invisible on a single box.

### 2.1 🔴 CRITICAL — every scheduled worker runs on every task

The codebase has **six `@Scheduled` workers** and **zero locking anywhere** — no `@Lock`, no `LockModeType`, no `SKIP LOCKED`, no ShedLock. I checked; there is not a single locking construct in `src/main`.

| Worker | Interval |
|---|---|
| `QuestionGenerationWorker` | every 20s |
| `EvaluationWorker` | every 30s |
| `JdExtractionWorker` | every 30s |
| `ResumeExtractionWorker` | every 30s |
| `SessionExpiryJob` | daily 03:00 |
| `AuthCleanupScheduler` | daily 02:00 / 02:30 |

Each polls with a plain derived query — e.g. `findAllByGenerationStatusIn(PENDING, IN_PROGRESS)` — and processes what it finds.

On one box this is correct, and the code says so: *"`fixedDelay` prevents concurrent scheduler runs within a single JVM."* That comment is true and its assumption is about to become false.

**On 6 Fargate tasks, all 6 tasks poll the same rows and process them simultaneously.** Consequences:

- **6× the LLM bill.** Every evaluation and every question-generation runs six times. This alone undoes the entire model-tiering saving.
- **Racing writes** on the same `EvaluationReport` row, with lost updates.
- **`generationAttempts` increments race** — six concurrent increments can blow past `maxAttempts` and mark a perfectly good session `FAILED`.
- After Phase 3 wires wallet settlement to completion, **duplicate settlement attempts on the same session.**

This is a hard blocker on autoscaling. It must ship in the same release as the Fargate migration.

**Fix — `SELECT … FOR UPDATE SKIP LOCKED`**, the standard Postgres work-queue pattern:

```java
@Query(value = """
    SELECT * FROM evaluation_reports
    WHERE generation_status IN ('PENDING','IN_PROGRESS')
    ORDER BY created_at
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
List<EvaluationReport> claimBatch(@Param("batchSize") int batchSize);
```

Each task claims a **distinct** set of rows and skips anything another task already holds. No coordination service, no Redis, no ShedLock, no extra cost — and unlike singleton scheduling it keeps all six tasks working in parallel, which is exactly what you want.

*(ShedLock would also prevent duplication, but by electing one instance to run the job — that throws away your parallelism. `SKIP LOCKED` is strictly better here.)*

### 2.2 🔴 The evaluation burst — this is what actually breaks at 25 concurrent

`EvaluationWorker.evaluatePendingReports()` fetches all pending reports and processes them in a **serial `for` loop**, with **no batch limit**, polled every 30 seconds.

Your PRD commits to a report being ready **within 3 minutes** of session end.

Now picture your launch scenario: a client runs a hiring drive and 25 interviews finish within the same few minutes.

| | Today | After fix |
|---|---|---|
| Poll delay before work starts | up to 30 s | ~0 s (event-triggered) |
| Evaluations in flight | **1** (serial loop) | 24 (6 tasks × 4) |
| Time for 25 evaluations @ ~20 s each | **~500 s** | ~40 s |
| **Last candidate's report ready** | **~8.5 minutes** ❌ | **~40 seconds** ✅ |

**Three changes:**

1. **Trigger evaluation on the completion event** rather than waiting for the next poll. Keep the poller purely as a crash-recovery safety net.
2. **Process the claimed batch in parallel** using the virtual threads you already have enabled (`spring.threads.virtual.enabled: true`), bounded by a semaphore of ~4 concurrent LLM calls per task to stay inside provider rate limits.
3. **Add a batch limit** (`LIMIT :batchSize`) so one task can't claim the entire queue.

---

## 3. What actually limits concurrent interviews

With the two fixes above, here's what binds — in order:

| Resource | At 25 concurrent | Verdict |
|---|---|---|
| **DB connections** | <1 connection of real utilisation | ✅ Enormous headroom |
| **WebSocket connections** | 25 sockets across 2 tasks | ✅ A single JVM handles thousands |
| **JVM heap** | ~50 KB session state × 25 ≈ 1.25 MB, on top of a ~300 MB Spring baseline | ✅ Fine |
| **App CPU** | ~0.3 answer-submits/sec; media never touches the server | ✅ Trivial |
| **Recording upload** | Browser → S3 direct via pre-signed URL | ✅ Bypasses the app entirely |
| **RDS CPU** | ~0.7 queries/sec | ✅ Fine — but see below |
| **Evaluation burst** | 25 simultaneous LLM calls | ⚠️ **The real constraint** — fixed by §2.2 |
| **LLM provider rate limits** | 25 concurrent requests | ⚠️ Check your tier's RPM/TPM before launch |

**This architecture supports well over 25 concurrent interviews** — the media pipeline is entirely client-side, which is precisely what makes the April design cheap to run. The server is a text relay plus a database writer.

### One watch item: burstable instances

`db.t4g.micro` and Fargate are both fine on raw capacity, but `t4g` is a **burstable** class earning CPU credits. Sustained load can exhaust the balance and throttle you to baseline — which would look exactly like the "breaks under load" failure you want to avoid.

- **Alarm on RDS `CPUCreditBalance`.** Non-negotiable, and free.
- `db.t4g.small` (2 GiB, ~225 connections, 2× baseline credits) is **+$14/mo** if you want margin. My advice: launch on `micro`, watch the credit balance for two weeks, resize if it trends down. It's a one-line Terraform change.

### Task sizing

I'd keep **0.5 vCPU / 1 GB × 2 tasks** and let the load test decide. If it shows memory or CPU pressure, **1 vCPU / 2 GB** doubles Fargate to ~$58/mo. Resizing is a task-definition change and a redeploy — not worth pre-buying.

---

## 4. Load-test target: 50, not 25

Your PRD already specifies **50 simultaneous sessions** as the NFR. Test to 50 so that 25 sits at half your proven ceiling. A capacity number you can only just hit is not a number you can sell against.

The load test must exercise the parts that actually break, not just HTTP endpoints:

- 50 concurrent **WebSocket** sessions held open for a realistic duration
- Answer submissions at realistic pacing (one per 60–90 s per session)
- **All 50 sessions completing within a 2-minute window** — this is the evaluation-burst test, and the one that would have failed today
- A **scale-in event fired during active interviews**, to prove task scale-in protection works
- Sustained run long enough to watch `CPUCreditBalance` on both RDS and Fargate

---

## 5. Summary

| | |
|---|---|
| Does an 8-connection pool cap us at 8 interviews? | **No.** 25 concurrent interviews use under half of one connection. |
| Corrected `max_connections` for `db.t4g.micro` | **~112**, not the ~85 I quoted |
| Recommended pool size | **10 per task** (6 tasks × 10 = 60, well under 112) |
| Can we do 25 concurrent? | **Yes, comfortably** — once §2.1 and §2.2 are fixed |
| What actually breaks at 25 today | Duplicate work across tasks (6× LLM cost), and serial evaluation blowing the 3-minute SLA at ~8.5 minutes |
| New blockers added to the backlog | `SKIP LOCKED` claim pattern (blocks autoscaling), parallel + event-driven evaluation |
| Extra cost to support 25+ | **₹0.** Both fixes are code, not infrastructure. |

The good news: your architecture is genuinely well-suited to this. Because all audio and video stays in the candidate's browser, the server does almost nothing per interview. The concurrency ceiling is set by two fixable code patterns, not by anything you'd need to buy.
