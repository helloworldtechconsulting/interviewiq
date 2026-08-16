# Load test — measuring `interviews-per-task` (INTIQ-99)

Architecture v4.0 §0 sets `interviews-per-task = 15` and states outright that
the number is a conservative guess "to be measured in the Phase 9 load test".
Two things currently rest on that guess: the HPA thresholds that decide when to
add a web pod, and the claim that 25 concurrent interviews fit comfortably on
the 2-pod floor.

`InterviewRoomSimulation` is what replaces the guess with a measurement.

## Before running

**Point it at staging. Never production.** The simulation completes real
interviews end to end. Against production it would consume real wallet balance,
occupy real capacity buckets, and generate evaluation reports for candidates
who do not exist.

### 1. Seed sessions

Each virtual user needs its own invite token, because a session is single-use.
For 25 concurrent users over a 20-minute hold, seed generously — a virtual user
that runs out of interview starts a new one.

Create a job and a batch of candidates in staging, create a session per
candidate, and write the invite tokens one per line:

```bash
# session-tokens.csv — header row required, one token per line after it
printf 'inviteToken\n' > src/test/resources/load/session-tokens.csv
# ... append one invite token per row
```

The file is gitignored. Invite tokens authenticate as a candidate, and a
committed one is a credential in the repository.

### 2. Run

```bash
./mvnw gatling:test -Pload-test -Dload.baseUrl=https://api-staging.interviewengine.ai -Dload.concurrentInterviews=25
```

Tunables, all optional:

| Property | Default | Meaning |
|---|---|---|
| `load.baseUrl` | `http://localhost:8080` | Target environment |
| `load.concurrentInterviews` | 25 | Held concurrency |
| `load.rampMinutes` | 5 | Ramp to full concurrency |
| `load.holdMinutes` | 20 | Steady-state duration |
| `load.questions` | 15 | Questions per interview (STANDARD tier) |

## Reading the result

Gatling's own report answers "was it fast?". That is the less interesting half.
The number being measured is on the pods, so watch Grafana during the run:

1. **Heap per web pod.** §0 predicts 50–100 KB of session state per live
   interview on top of a ~300 MB Spring baseline. Divide the increase by the
   concurrency held. If it lands near the prediction, `interviews-per-task` can
   rise well above 15 and the HPA can be made much less eager.

2. **`hikaricp_connections_pending`.** Must stay at zero. Anything above it
   means the pool sizing in Arch §5.4 is wrong under real concurrency, and the
   connection budget in `deployment_web.tf` needs revisiting before the number
   above is trusted.

3. **WebSocket disconnects.** Any at all are worth chasing. §7.5.2 calls a
   deploy or scale-in killing a live interview "the single worst bug this
   product could ship" — a disconnect under steady load is a smaller version of
   the same failure.

4. **`interviewengine_evaluation_queue_oldest_age_seconds`.** Twenty-five
   interviews finishing within a few minutes of each other is the worst case
   for the evaluation pipeline. If KEDA does not scale workers fast enough to
   keep this under the 30-minute SLA, the worker maximum is too low.

## A caveat worth stating

This measures the server's cost per interview. It does not measure the
browser's, and the browser is where all the media work happens —
`getUserMedia`, `MediaRecorder`, `SpeechRecognition` and the 480p upload all run
client-side. A green run here says the backend scales; it says nothing about
whether the interview room performs acceptably on a mid-range Android phone on
a weak connection, which is a separate and equally real question.
