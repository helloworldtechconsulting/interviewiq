# InterviewIQ — OpenAI vs Anthropic: Cost-Benefit Analysis

**Date:** 1 August 2026 · Pricing checked same day

---

## 1. The workloads we're buying tokens for

| Workload | Nature | Latency need | Est. tokens |
|---|---|---|---|
| **Question generation** | JD + resume → 12–20 structured questions. Templated, structured output, low judgement. | Async (pre-computed at invite time) | ~2k in / ~1.5k out |
| **Evaluation** | Transcript → per-dimension scores + summary + recommendation. **High judgement.** | < 3 min after session end | ~4k in / ~1.5k out |
| **Follow-up decision** (Phase 3) | "Does this answer warrant a follow-up?" | **Real-time, mid-interview** | ~1k in / ~200 out |

Roughly **6k input / 3k output per interview**.

---

## 2. Cost

| Vendor | Model | Input /1M | Output /1M | **Per interview** | **500/mo** |
|---|---|---|---|---|---|
| OpenAI | GPT-5.4-nano | $0.20 | $1.25 | **₹0.42** | **$2.50** |
| OpenAI | GPT-5.4-mini | $0.75 | $4.50 | **₹1.55** | **$9** |
| Anthropic | **Claude Haiku 4.5** | $1.00 | $5.00 | **₹1.80** | **$10.50** |
| OpenAI | GPT-5.4 | $2.50 | $15.00 | ₹5.20 | $30 |
| Anthropic | Claude Sonnet | ~$2–3 | ~$10–15 | ₹4.70–5.45 | $27–32 |

Three conclusions:

1. **OpenAI's nano tier has no Anthropic equivalent.** At $0.20/$1.25 it is ~4× cheaper than Anthropic's cheapest model. If a workload is easy enough for nano, OpenAI wins on price outright.
2. **At the mini/Haiku tier the two are within 16%** — ₹1.55 vs ₹1.80 per interview. On 500 interviews that's a ₹125/month difference. **That is noise. Do not pick a vendor on this.**
3. At the flagship tier they're within ~10% of each other. Also noise.

> Note on model names: sources published weeks apart disagree on current version labels (Opus 5 / Sonnet 5 vs Opus 4.7 / Sonnet 4.6). All agree **Haiku 4.5 at $1/$5** is Anthropic's cheapest current tier. Treat exact version strings as needing confirmation at implementation time; the price tiers are stable.

### 2.1 Two levers worth more than the vendor choice

**Prompt caching — use it.** For one job opening you generate questions for up to 200 candidates against the **same JD**. Cache the JD + system prompt: cache reads cost ~10% of base input on both vendors. On a 200-candidate opening that cuts question-generation input cost by roughly 90%. Both vendors support it. This saves more than switching vendors does.

**Batch API — reject it.** Anthropic and OpenAI both offer ~50% off for asynchronous batch. Tempting for evaluation, but batch turnaround is "up to 24 hours" and your PRD commits to **report-ready in under 3 minutes**. Not compatible. *(Possible future product idea: a discounted "next-day results" tier that uses batch. Out of scope now.)*

---

## 3. Accuracy — and why I won't give you a verdict here

You asked which is more accurate. The honest answer is that **nobody can tell you from public data**, because the benchmark that matters to you doesn't exist publicly: *correlation between model score and actual hiring outcome, on Indian SMB screening interviews, in Indian English, across your job categories.* Public leaderboards measure none of that.

What I can offer is directional, and I'd hold it loosely:

- **Anthropic models have a reputation for calibrated, rubric-following judgement** and less grade inflation. LLM-as-judge systems commonly fail by clustering every candidate in the 70–85 band, which makes your score useless for ranking — the exact failure that would kill the product. This is a point in Haiku's favour for **evaluation**.
- **OpenAI's nano/mini tiers are cheaper and have mature JSON-schema-enforced structured output.** For **question generation** — a templated task — this is a straightforward win.
- Both support strict structured output, both are fast enough, both have solid Indian-English handling.

**So: don't choose. Instrument.**

### 3.1 The recommendation

| Workload | Model | Why |
|---|---|---|
| Question generation | **GPT-5.4-nano** | Easy, templated, structured. 4× cheaper than anything else. Combine with prompt caching. |
| Follow-up decision | **GPT-5.4-nano** | Trivial classification, latency-sensitive, cheapest. |
| **Evaluation** | **Shadow-mode GPT-5.4-mini vs Claude Haiku 4.5** for the first ~50 interviews | This is where score quality lives. Decide with your own data. |

**Shadow mode**: score every interview with both models, serve one to the recruiter, log both. When you collect the "did you hire this candidate?" feedback the PRD already calls for, you get a real Pearson r per vendor. Cost of running both: **~₹3.35/interview instead of ₹1.55 — about ₹900 for the whole 50-interview experiment.** That is a rounding error against making this decision blind.

If you must pick one today without data: **Haiku 4.5 for evaluation.** The ₹0.25/interview premium over mini is trivial, and scoring calibration is the single quality attribute your product is sold on.

### 3.2 Build it so the choice is reversible

You already agreed to per-workflow model config. Extend it one step — make it **per-workflow *vendor***:

```yaml
app:
  ai:
    question:   { vendor: openai,    model: gpt-5.4-nano }
    followup:   { vendor: openai,    model: gpt-5.4-nano }
    evaluation: { vendor: anthropic, model: claude-haiku-4-5 }
```

Spring AI makes this nearly free: `spring-ai-openai` and `spring-ai-anthropic` both implement `ChatClient`. Build a `ChatClient` bean per workflow from config, inject by qualifier. Roughly a day of work, and it buys you three things:

1. **Empirical vendor selection** rather than a guess.
2. **Failover.** OpenAI outage → flip config, redeploy, keep selling. Your own architecture doc already lists "Bedrock Claude as secondary" for Phase 2 — this delivers it in Phase 2 for free.
3. **Negotiating position.** Being able to move workloads in an afternoon is worth real money once you're spending meaningfully.

---

## 4. Correction: Bedrock does *not* solve data residency

I expected to recommend Claude via **AWS Bedrock in ap-south-1** on the grounds that it keeps candidate data inside India. **I checked, and that's wrong.**

AWS's own documentation states that Claude models in India on Bedrock are served through **global cross-Region inference**, routing to "AWS commercial Regions globally." The published routing is explicitly `BOM (ap-south-1) → AWS commercial Regions`. Only CloudTrail and CloudWatch logs stay in India — the **inference itself does not**.

**So neither vendor keeps inference inside India.** Which means:

### 4.1 Your PRD contains a claim that is already false

> *"Compliance — Data residency: All data stored and processed in AWS ap-south-1 (Mumbai) region."*

The moment you call **any** external LLM, this is untrue. It was untrue under the original GPT-4o design too. Self-hosting a model is the only way to make it true, and that costs far more than your entire infrastructure budget.

**Three things to do about it, all cheap:**

1. **Amend the claim.** Change it to something you can defend: *"All candidate data is stored at rest in ap-south-1. Interview transcripts are processed by our AI provider under a zero-retention agreement; no candidate data is used to train third-party models."* Get this into the privacy policy and the customer contract before your first paying client, not after.

2. **Redact PII before the LLM call — this is the important one.** The evaluation model does not need to know the candidate's name, email, or phone number to score an answer about Spring Boot. Strip identity fields, pass an opaque `candidate_ref`, re-attach identity locally when you persist the report. Costs nothing, removes most of the residency exposure, and is a genuinely strong answer when a customer's security team asks. **Do this regardless of vendor.**

3. **Get the zero-retention commitment in writing** from whichever vendor you pick. Both offer no-training-on-API-data terms on business tiers. Keep the document — a prospect's procurement team will ask.

If a specific enterprise customer later *demands* in-country inference, that's a Phase 3 conversation about a self-hosted open-weights model on your own infra, priced as a premium tier. Don't build for it now.

---

## 5. On "we just give a score, the recruiter decides"

You're right, and it's a materially safer position than auto-rejection. Two things follow.

**It reduces legal exposure — make that explicit rather than implicit.** Put it in the product and the contract: the score is advisory, InterviewIQ performs no automated rejection, a human makes every decision. India has no direct analogue to NYC Local Law 144 yet, but the **EU AI Act classifies employment-screening AI as high-risk** — relevant the day you sell to an Indian company with EU operations. A one-line disclaimer on the report page and a clause in the MSA cost nothing now and are expensive to retrofit.

**It does not reduce the accuracy requirement.** The score *is* the product. If recruiters can't act on it, they stop paying — and your own PRD makes AI-score-to-hire correlation (Pearson r > 0.65) a launch KPI. "The human decides" protects you legally; it doesn't protect the business case. That's precisely why §3.1 spends ₹900 to answer the vendor question with data rather than a guess.

One design consequence worth acting on: **make the report show its reasoning, not just a number.** Per-dimension scores with the specific quoted answer that drove each one. A recruiter who can see *why* the score is 72 will trust and use it; a bare "72" gets ignored. This also happens to be your best defence if a candidate ever challenges a decision.

---

## 6. Summary

| Question | Answer |
|---|---|
| Cheaper? | **OpenAI**, decisively — but only because of the nano tier. At mini/Haiku the gap is ₹0.25/interview. |
| More accurate? | **Unknown, and unknowable from public data.** Measure it with shadow mode on your first 50 interviews. |
| What to build? | Per-workflow **vendor + model** config. nano for generation and follow-ups; shadow mini vs Haiku for evaluation. |
| Default if forced to pick blind | **Haiku 4.5 for evaluation** (calibration matters more than ₹0.25), nano for everything else. |
| Bigger cost lever than vendor choice | **Prompt caching on the JD** (~90% off question-gen input). Batch API is unusable — 24h vs your 3-min SLA. |
| Data residency | **Neither vendor keeps inference in India, including Bedrock.** Amend the PRD claim, redact PII before the call, get zero-retention terms in writing. |
| Monthly AI spend at 500 interviews | **~$5 (₹430)** on the recommended mix — versus $30 on the flagship tier the docs assumed. |

---

*Sources: [Morph — OpenAI API pricing 2026](https://www.morphllm.com/openai-api-pricing) · [CloudZero — OpenAI pricing](https://www.cloudzero.com/blog/openai-pricing/) · [BenchLM — Claude API pricing July 2026](https://benchlm.ai/anthropic/api-pricing) · [CloudZero — Claude API pricing](https://www.cloudzero.com/blog/claude-api-pricing/) · [AWS — Claude models in India on Bedrock with global cross-region inference](https://aws.amazon.com/blogs/machine-learning/access-anthropic-claude-models-in-india-on-amazon-bedrock-with-global-cross-region-inference)*
