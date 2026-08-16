package com.interviewengine.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-workflow AI vendor and model selection (PRD v2.1 §9.1).
 *
 * <p>Bound to {@code app.ai}. Vendor <em>and</em> model are configurable per
 * workflow, and each workflow gets its own {@code ChatClient} bean built from
 * this and injected by qualifier:
 *
 * <pre>
 * app:
 *   ai:
 *     question:   { vendor: openai,    model: gpt-5.6-luna }
 *     followup:   { vendor: openai,    model: gpt-5.6-luna }
 *     evaluation: { vendor: anthropic, model: claude-haiku-4-5 }
 * </pre>
 *
 * <p>The PRD gives three reasons this is configuration rather than code:
 * empirical vendor selection rather than a guess; failover, since a provider
 * outage becomes a config flip and a redeploy; and a negotiating position once
 * spend becomes meaningful.
 *
 * <h2>Adding a provider</h2>
 *
 * <p>{@link AiConfig} resolves {@code vendor} against the {@code ChatModel} beans
 * present at runtime, so a third provider needs no Java change at all:
 *
 * <ol>
 *   <li>add its Spring AI starter to {@code pom.xml}
 *       (e.g. {@code spring-ai-starter-model-vertex-ai-gemini});</li>
 *   <li>set its credentials in {@code spring.ai.*};</li>
 *   <li>point a workflow at it: {@code vendor: google}.</li>
 * </ol>
 *
 * <p>Vendor names are derived from the model class — {@code OpenAiChatModel} →
 * {@code openai}, {@code VertexAiGeminiChatModel} → {@code vertexaigemini} — which
 * is precise but not what anyone types. {@link #getVendorAliases()} maps the
 * names people actually use onto those keys, and is itself configuration, so an
 * unanticipated provider can be aliased without a build.
 *
 * <h2>Why evaluation is deliberately undecided</h2>
 *
 * <p>§13.1 is unusually candid: "Accuracy is not knowable from public data." The
 * benchmark that matters — correlation between model score and actual hiring
 * outcome, on Indian SMB screening interviews in Indian English — does not exist.
 * So the plan is to instrument rather than guess: score the first ~50 interviews
 * with both candidates, serve one and log both, and select on real Pearson r for
 * about ₹900 total.
 *
 * <p>The August 2026 reprice strengthened this rather than settling it: the two
 * evaluation candidates are now within a rupee of each other per interview
 * (Haiku 4.5 at $1/$5, gpt-5.6-terra at $1/$6), and the whole cheapest-to-flagship
 * spread is about ₹5 on a ₹100 product. There is no longer even a price argument
 * to hide behind — this is a measurement, or it is a guess.
 *
 * <p>Model version strings must be confirmed against the provider price list at
 * implementation time. The price <em>tiers</em> are stable; the labels are not —
 * {@code gpt-4o}, then {@code gpt-5.4-nano}, have both already gone stale here.
 */
@ConfigurationProperties(prefix = "app.ai")
public class AiWorkflowProperties {

    /**
     * Question generation — templated, structured, low-judgement. Cheapest tier.
     *
     * <p>{@code gpt-5.6-luna} replaced {@code gpt-5.4-nano} on 16 Aug 2026: same
     * nano tier, half the price ($0.10/$0.60 against $0.20/$1.25), and 5.4 is no
     * longer on the published price list.
     */
    private final Workflow question = new Workflow("openai", "gpt-5.6-luna");

    /** Follow-up decision — trivial real-time classification, latency-sensitive. */
    private final Workflow followup = new Workflow("openai", "gpt-5.6-luna");

    /**
     * Evaluation — score quality is the product.
     *
     * <p>Defaults to Haiku because, forced to choose blind, §13.1 picks it: "because
     * calibration matters more than ₹0.25". LLM-as-judge systems typically fail by
     * clustering everyone in the 70–85 band, which destroys the ranking signal the
     * product is sold on.
     */
    private final Workflow evaluation = new Workflow("anthropic", "claude-haiku-4-5");

    /**
     * Friendly vendor name → the key {@link AiConfig} derives from the model class.
     *
     * <p>Seeded with the obvious ones and additive: entries under
     * {@code app.ai.vendor-aliases} merge into these rather than replacing them.
     */
    private final Map<String, String> vendorAliases = new LinkedHashMap<>(Map.of(
            "gpt",         "openai",
            "chatgpt",     "openai",
            "claude",      "anthropic",
            "google",      "vertexaigemini",
            "gemini",      "vertexaigemini",
            "vertexai",    "vertexaigemini",
            "azure",       "azureopenai",
            "bedrock",     "bedrockproxy",
            "mistral",     "mistralai"));

    /**
     * Shadow mode: score with both vendors, serve one, log both (§13.1).
     *
     * <p>Off by default; enabled for the first ~50 interviews to produce a real
     * per-vendor correlation against "did you hire this candidate?" feedback.
     */
    private boolean shadowEvaluation = false;

    private String shadowVendor = "openai";

    /** Mid tier, the like-for-like comparison against Haiku 4.5. Was {@code gpt-5.4-mini}. */
    private String shadowModel = "gpt-5.6-terra";

    /** Maximum retries before a report is flagged ERROR for manual review (§7.5.5). */
    private int evaluationMaxAttempts = 3;

    /**
     * Prompt caching on the JD and system prompt (§7.5.1, §13.1).
     *
     * <p>Cache reads cost about 10% of base input at every provider currently in
     * scope. Note the sizing in §13.1 predates two-stage generation, which already
     * collapsed question-generation cost by ~77% by calling the model once per
     * <em>job</em> rather than once per candidate — do not count that saving
     * twice. The lever still on the table is caching the rubric and JD prefix on
     * the <em>evaluation</em> call, which fires once per answer.
     */
    private boolean promptCaching = true;

    public Workflow getQuestion()   { return question; }
    public Workflow getFollowup()   { return followup; }
    public Workflow getEvaluation() { return evaluation; }

    public Map<String, String> getVendorAliases() { return vendorAliases; }

    public boolean isShadowEvaluation() { return shadowEvaluation; }
    public void setShadowEvaluation(boolean shadowEvaluation) { this.shadowEvaluation = shadowEvaluation; }

    public String getShadowVendor() { return shadowVendor; }
    public void setShadowVendor(String shadowVendor) { this.shadowVendor = shadowVendor; }

    public String getShadowModel() { return shadowModel; }
    public void setShadowModel(String shadowModel) { this.shadowModel = shadowModel; }

    public int getEvaluationMaxAttempts() { return evaluationMaxAttempts; }
    public void setEvaluationMaxAttempts(int v) { this.evaluationMaxAttempts = v; }

    public boolean isPromptCaching() { return promptCaching; }
    public void setPromptCaching(boolean promptCaching) { this.promptCaching = promptCaching; }

    /** One workflow's vendor, model and sampling temperature. */
    public static class Workflow {

        private String vendor;
        private String model;

        /**
         * Low by default. Every workflow here is a structured, rubric-following
         * task rather than a creative one, and evaluation in particular needs to
         * be reproducible enough that the same transcript scores the same twice.
         */
        private double temperature = 0.2;

        public Workflow() {}

        Workflow(String vendor, String model) {
            this.vendor = vendor;
            this.model = model;
        }

        public String getVendor() { return vendor; }
        public void setVendor(String vendor) { this.vendor = vendor; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }

        @Override
        public String toString() {
            return vendor + "/" + model;
        }
    }
}
