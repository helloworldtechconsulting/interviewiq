# InterviewIQ — AI Provider Selection: Cost-Benefit Analysis

**Date:** 1 August 2026 · **Revised 16 August 2026** (reprice + provider-agnostic implementation)

---

## 0. Revision note — 16 August 2026

Two weeks moved three things. None of it reverses the 1 August recommendation; it sharpens it.

**The pinned models went stale again.** OpenAI's published lineup is now `gpt-5.6-luna` / `terra` / `sol`. `gpt-5.4-nano` and `gpt-5.4-mini` are no longer on the price list, though neither appears on the deprecation schedule either. `luna` is the same nano tier at **half the price** of 5.4-nano — $0.10/$0.60 against $0.20/$1.25. Config now pins `luna` for generation and follow-ups and `terra` as the shadow comparator. This is the second time a model string in this repo went stale; treat every model label as a value with a shelf life, which is exactly why they live in `application.yml` and not in code.

**The price argument for a vendor is gone entirely.** Claude Haiku 4.5 ($1/$5) and gpt-5.6-terra ($1/$6) are now within ₹0.25 per interview. The whole cheapest-to-flagship spread is about **₹5 on a ₹100 product**. §3 said "don't choose, instrument" partly because the gap was noise; the gap is smaller now. There is no price tiebreak left to hide behind — the shadow run is the decision, or there is no decision.

**"Vendor is configuration" is now true rather than aspirational.** §3.2 called for per-workflow vendor config and it shipped — but as a two-vendor `switch` over `"openai"` and `"anthropic"`, with provider-specific options classes behind a ternary. That is two-vendor code wearing the vocabulary of vendor-agnostic code: adding a third provider meant editing Java in three places. `AiConfig` now resolves `app.ai.*.vendor` against whatever `ChatModel` beans exist at runtime, keyed off the model class (`OpenAiChatModel` → `openai`, `VertexAiGeminiChatModel` → `vertexaigemini`), and configures them through Spring AI's **portable** `ChatOptions`. Adding a provider is a Maven starter, a credential, and a string in YAML.

The cost of that portability, stated plainly: provider-exclusive knobs (OpenAI reasoning effort, Anthropic extended thinking, Gemini safety settings) are not reachable through `ChatOptions`. No current workflow needs one. The day one does, the honest fix is a per-vendor options customiser — not an `instanceof` smuggled back into `AiConfig`.

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

**Repriced 16 August 2026** (₹85/$):

| Vendor | Model | Input /1M | Output /1M | **Per interview** | **500/mo** | % of ₹100 price |
|---|---|---|---|---|---|---|
| Google | Gemini 2.5 Flash-Lite | $0.10 | $0.40 | ₹0.15 | $0.90 | 0.15% |
| OpenAI | **GPT-5.6-luna** (nano) | $0.10 | $0.60 | **₹0.20** | **$1.20** | 0.2% |
| Google | Gemini 3.1 Flash-Lite | $0.25 | $1.50 | ₹0.51 | $3 | 0.5% |
| Anthropic | **Claude Haiku 4.5** | $1.00 | $5.00 | **₹1.79** | **$10.50** | 1.8% |
| OpenAI | **GPT-5.6-terra** (mid) | $1.00 | $6.00 | **₹2.04** | **$12** | 2% |
| Anthropic | Claude Sonnet 5 | $2.00 | $10.00 | ₹4.08 | $24 | 4% |
| OpenAI | GPT-5.6-sol | $2.50 | $15.00 | ₹5.10 | $30 | 5% |

Four conclusions:

1. **The entire decision is worth ₹5 per interview.** Cheapest to flagship, on a ₹100 product, against ₹8,300/month of fixed infrastructure. Choosing a provider on token price is optimising the wrong 5% of COGS. What decides this is whether the evaluation score correlates with real hiring outcomes — the r > 0.65 launch KPI. A cheap score nobody trusts is worth ₹0.
2. **The cheap tier is now a three-way tie.** luna and Gemini Flash-Lite are within ₹0.05 of each other. Anthropic still sells nothing at this tier, so generation and follow-ups stay on OpenAI — but on availability, not on price.
3. **The evaluation candidates converged.** Haiku 4.5 at $1/$5 and terra at $1/$6 differ by ₹0.25. Where 1 August could say "the gap is noise", 16 August can barely find a gap.
4. **Sonnet 5 repriced down** to $2/$10 (Sonnet 4.6 was $3/$15). If Haiku under-calibrates on the shadow run, the escalation costs ₹4/interview, not ₹5.

> **Google was evaluated and not adopted — on price.** Flash-Lite beats luna by ₹0.05 per interview, which does not justify a third API key, a third outage surface, and a third set of procurement terms. There is exactly one reason to open Vertex AI, and it is in §4.
>
> **Note on model names:** this table has now gone stale twice (`gpt-4o`, then `gpt-5.4-*`). The price *tiers* are stable; the labels are not. Confirm against the provider price list at every deploy.

### 2.1 Two levers worth more than the vendor choice

**Prompt caching — use it, but don't double-count it.** Cache reads cost ~10% of base input at all three providers. **Correction (16 Aug):** the "~90% off question generation" figure above was sized before two-stage generation, which already collapsed that cost ~77% by calling the model once per *job* rather than once per candidate. That saving is banked; caching cannot bank it twice. The lever still genuinely on the table is caching the **rubric + JD prefix on the evaluation call**, which fires once per answer — roughly 15× per interview.

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
| Question generation | **GPT-5.6-luna** | Easy, templated, structured. Cheapest tier Anthropic doesn't sell into. |
| Follow-up decision | **GPT-5.6-luna** | Trivial classification, latency-sensitive, cheapest. |
| **Evaluation** | **Shadow-mode GPT-5.6-terra vs Claude Haiku 4.5** for the first ~50 interviews | This is where score quality lives. Decide with your own data. |

**Shadow mode**: score every interview with both models, serve one to the recruiter, log both. When you collect the "did you hire this candidate?" feedback the PRD already calls for, you get a real Pearson r per vendor. Cost of running both: **~₹3.83/interview instead of ₹1.79 — under ₹200 for the whole 50-interview experiment.** That is a rounding error against making this decision blind.

If you must pick one today without data: **Haiku 4.5 for evaluation.** After the reprice the premium over terra is ₹0.25 *in Haiku's favour* on output-heavy work, and scoring calibration is the single quality attribute the product is sold on.

### 3.2 Build it so the choice is reversible

You already agreed to per-workflow model config. Extend it one step — make it **per-workflow *vendor***:

```yaml
app:
  ai:
    question:   { vendor: openai,    model: gpt-5.6-luna }
    followup:   { vendor: openai,    model: gpt-5.6-luna }
    evaluation: { vendor: anthropic, model: claude-haiku-4-5 }
```

This buys three things:

1. **Empirical vendor selection** rather than a guess.
2. **Failover.** Provider outage → flip config, redeploy, keep selling. The architecture doc already lists "Bedrock Claude as secondary" for Phase 2 — this delivers it for free.
3. **Negotiating position.** Being able to move workloads in an afternoon is worth real money once spend is meaningful.

#### Implemented, and then corrected — 16 August 2026

Shipped as written, and the first version was only half-agnostic. `AiConfig` had a `switch (vendor)` naming `"openai"` and `"anthropic"`, one `ObjectProvider` per vendor in the constructor, a ternary between `OpenAiChatOptions` and `AnthropicChatOptions`, and an error message reading *"Supported: openai, anthropic."* Config said vendor was a runtime value; the code said it was a build-time value. Only the code was true.

**What it does now:**

- **Discovery, not enumeration.** The constructor takes `ObjectProvider<ChatModel>` — *all* of them — and keys each by its implementation class: `OpenAiChatModel` → `openai`, `AnthropicChatModel` → `anthropic`, `VertexAiGeminiChatModel` → `vertexaigemini`. Verified against the real jars, and the derived keys match the strings already in `application.yml`, so nothing had to migrate. Keyed off the class rather than the bean name because Spring AI has already renamed its bean/starter conventions once; the model class name is public API.
- **Portable options.** `ChatOptions.builder().model(...).temperature(...)` instead of a per-vendor options class. Spring AI merges portable options into each provider's native shape at request time.
- **Honest failures.** The error lists the providers actually present and the aliases actually configured, rather than a hardcoded list that would be a lie the moment a starter was added — or a misdirection when a starter is present but its API key isn't.
- **Aliases are config too.** `app.ai.vendor-aliases` maps friendly names (`google`, `gemini`, `claude`, `bedrock`) onto derived keys, so an unanticipated provider can be taught to the app without a build.

Net effect: **adding a provider is a Maven starter + a credential + a YAML string.** Zero Java. `pom.xml` carries the commented-out Vertex AI Gemini starter as the worked example.

Worth being clear about what was *not* built: no in-house `AiProvider` interface, no adapter layer, no factory hierarchy. Spring AI's `ChatModel`/`ChatClient` is already the portability abstraction. Wrapping it in a second one of our own would add a layer to maintain and a vocabulary to learn, in exchange for nothing — the failure mode where "make it vendor-agnostic" produces more coupling than it removes.

---

## 4. Correction: Bedrock does *not* solve data residency

I expected to recommend Claude via **AWS Bedrock in ap-south-1** on the grounds that it keeps candidate data inside India. **I checked, and that's wrong.**

AWS's own documentation states that Claude models in India on Bedrock are served through **global cross-Region inference**, routing to "AWS commercial Regions globally." The published routing is explicitly `BOM (ap-south-1) → AWS commercial Regions`. Only CloudTrail and CloudWatch logs stay in India — the **inference itself does not**.

**So neither vendor keeps inference inside India.** Which means:

> **Lead worth chasing — 16 August 2026, UNVERIFIED.** Google Cloud publishes *per-region ML-processing* commitments for Vertex AI, and `asia-south1` (Mumbai) is a Gemini region. If a Vertex regional endpoint genuinely keeps Gemini inference in India, it is the only route found so far to making the residency claim true without self-hosting — the thing Bedrock explicitly cannot do. **Do not act on this yet.** I could not confirm it from Google's docs, and there are open developer reports of the global endpoint silently overriding a configured location, which is precisely the failure that would make the claim false while appearing true. Get it in writing from Google before it goes anywhere near the privacy policy. If it holds, *that* is the argument for adding Gemini — a compliance argument, not the ₹0.05 price one.

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

*Updated 16 August 2026.*

| Question | Answer |
|---|---|
| Cheaper? | **Wrong question.** Cheapest to flagship is ~₹5 on a ₹100 product. OpenAI's nano tier still wins where a workload is easy enough for it, because Anthropic sells nothing there. |
| More accurate? | **Unknown, and unknowable from public data.** Measure it with shadow mode on the first 50 interviews. The reprice removed the last price tiebreak, so this is now the *only* input. |
| What to build? | Per-workflow **vendor + model** config — **built, then corrected** to resolve vendors at runtime instead of enumerating two. Adding a provider is a starter + credential + YAML string. |
| Pinned models | `gpt-5.6-luna` (generation, follow-ups) · `claude-haiku-4-5` (evaluation) · `gpt-5.6-terra` (shadow). Was `gpt-5.4-*`, which is off the price list; luna is half the price. |
| Default if forced to pick blind | **Haiku 4.5 for evaluation** (calibration over price — and it is now the cheaper of the two anyway), luna for everything else. |
| Should we add Google? | **Not for price** — ₹0.05/interview does not buy a third API key. Possibly for **data residency** (§4), if Vertex `asia-south1` in-region inference is confirmed in writing. |
| Bigger cost lever than vendor choice | **Caching the rubric + JD prefix on the evaluation call.** The question-generation caching win was already banked by two-stage generation — don't count it twice. Batch API remains unusable: 24h vs the 3-min SLA. |
| Data residency | **No external LLM keeps inference in India, Bedrock included.** Amend the PRD claim, redact PII before the call, get zero-retention terms in writing. Vertex Mumbai is an unverified lead, not a fix. |
| Monthly AI spend at 500 interviews | **~$6.4 (₹545)** on the recommended mix — versus $30 on the flagship tier the original docs assumed. Note the shape: **evaluation is ~90% of it** (~$5.75 of $6.4). Generation and follow-ups together cost under $1/month, which is why tuning them further is wasted effort. |

---

*Sources (1 Aug 2026): [Morph — OpenAI API pricing 2026](https://www.morphllm.com/openai-api-pricing) · [CloudZero — OpenAI pricing](https://www.cloudzero.com/blog/openai-pricing/) · [BenchLM — Claude API pricing July 2026](https://benchlm.ai/anthropic/api-pricing) · [CloudZero — Claude API pricing](https://www.cloudzero.com/blog/claude-api-pricing/) · [AWS — Claude models in India on Bedrock with global cross-region inference](https://aws.amazon.com/blogs/machine-learning/access-anthropic-claude-models-in-india-on-amazon-bedrock-with-global-cross-region-inference)*

*Sources (16 Aug 2026 revision — vendor price lists read directly, not via trackers): [OpenAI API pricing](https://developers.openai.com/api/docs/pricing) · [OpenAI model deprecations](https://developers.openai.com/api/docs/deprecations) · [Anthropic pricing](https://platform.claude.com/docs/en/about-claude/pricing) · [Gemini API pricing](https://ai.google.dev/gemini-api/docs/pricing) · [Vertex AI data residency](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/learn/data-residency) · [Spring AI `ChatOptions`](https://docs.spring.io/spring-ai/docs/1.0.x/api/org/springframework/ai/chat/prompt/ChatOptions.html)*
