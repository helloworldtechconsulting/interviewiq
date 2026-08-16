package com.interviewiq.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.ClassUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds one {@link ChatClient} per AI workflow (PRD v2.1 §9.1).
 *
 * <p>"Each workflow gets its own {@code ChatClient} bean built from configuration
 * and injected by qualifier." Vendor and model are both configuration values, so
 * switching a workflow between providers — or failing over during a provider
 * outage — is a config change and a redeploy, not a rewrite.
 *
 * <h2>Why this class no longer names any vendor</h2>
 *
 * <p>It used to. A {@code switch} listed {@code "openai"} and {@code "anthropic"},
 * the constructor took one {@code ObjectProvider} per vendor, and the options
 * builder was a ternary between {@code OpenAiChatOptions} and
 * {@code AnthropicChatOptions}. That is two-vendor code wearing the vocabulary of
 * vendor-agnostic code: the config *said* vendor was a runtime value, but adding
 * a third provider meant editing Java in three places and shipping a new build.
 *
 * <p>Now the class discovers whatever {@link ChatModel} beans Spring AI's
 * auto-configuration put on the classpath and keys them by a name derived from
 * the implementation class ({@code OpenAiChatModel} → {@code openai},
 * {@code VertexAiGeminiChatModel} → {@code vertexaigemini}). **Adding a provider
 * is a Maven starter, an API key, and a string in {@code application.yml} — no
 * Java change.** Removing one is deleting the starter.
 *
 * <h2>The deliberate constraint: portable options only</h2>
 *
 * <p>Options are built with the portable {@link ChatOptions#builder()}, not a
 * provider's own options class. Spring AI merges portable options into each
 * provider's native shape at request time, so model and temperature work
 * everywhere. The trade-off is real and worth stating: provider-exclusive knobs
 * (OpenAI reasoning effort, Anthropic extended thinking, Gemini safety settings)
 * are <em>not</em> reachable through this path. That is the price of genuine
 * portability, and it is the right price here — none of the three workflows needs
 * one. The day a workflow does, the honest fix is a per-vendor options
 * customiser, not a quiet {@code instanceof} in this method.
 *
 * @see AiWorkflowProperties for why the evaluation vendor is undecided by design
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    public static final String QUESTION_CLIENT   = "questionChatClient";
    public static final String FOLLOWUP_CLIENT   = "followupChatClient";
    public static final String EVALUATION_CLIENT = "evaluationChatClient";
    public static final String SHADOW_CLIENT     = "shadowEvaluationChatClient";

    private static final String CHAT_MODEL_SUFFIX = "ChatModel";

    private final AiWorkflowProperties properties;

    /** Every provider on the classpath, keyed by its derived vendor name. */
    private final Map<String, ChatModel> modelsByVendor;

    public AiConfig(AiWorkflowProperties properties, ObjectProvider<ChatModel> chatModels) {
        this.properties = properties;
        this.modelsByVendor = chatModels.orderedStream()
                .collect(Collectors.toMap(
                        AiConfig::vendorKeyOf,
                        Function.identity(),
                        (first, duplicate) -> first,
                        LinkedHashMap::new));

        if (modelsByVendor.isEmpty()) {
            log.warn("No ChatModel beans found. Every AI workflow will fail to start. "
                    + "Check that a Spring AI model starter is on the classpath and its API key is set.");
        } else {
            log.info("AI providers available: {}", modelsByVendor.keySet());
        }
    }

    @Bean(QUESTION_CLIENT)
    public ChatClient questionChatClient() {
        return clientFor("question", properties.getQuestion());
    }

    @Bean(FOLLOWUP_CLIENT)
    public ChatClient followupChatClient() {
        return clientFor("followup", properties.getFollowup());
    }

    /**
     * Marked primary so an unqualified {@code ChatClient} injection resolves to
     * evaluation rather than failing ambiguously. Evaluation is the workflow
     * whose output <em>is</em> the product, so it is the least surprising default.
     */
    @Bean(EVALUATION_CLIENT)
    @Primary
    public ChatClient evaluationChatClient() {
        return clientFor("evaluation", properties.getEvaluation());
    }

    /**
     * The second opinion for shadow mode (§13.1).
     *
     * <p>Returns null when shadow mode is off, which means <strong>no bean is
     * registered at all</strong> — Spring treats a null-returning {@code @Bean}
     * method as "no such bean", not as "a bean whose value is null".
     *
     * <p>This comment previously claimed the opposite: that "the bean exists for
     * injection but the shadow path is skipped". It does not, and the
     * consequence was total — with {@code shadow-evaluation: false}, which is
     * the default, {@code EvaluationService}'s constructor could not be
     * satisfied and <em>the application did not start</em>. It went unnoticed
     * because no test loads the full Spring context: the integration tests are
     * {@code @DataJpaTest} slices that never see this configuration.
     *
     * <p>The injection point in {@code EvaluationService} is therefore an
     * {@code ObjectProvider}, which tolerates the absent bean. Keeping the null
     * return is still the right shape — a conditional bean would put an
     * {@code @ConditionalOnProperty} in front of a flag that flips once — but
     * only now that the consumer actually handles it.
     */
    @Bean(SHADOW_CLIENT)
    public ChatClient shadowEvaluationChatClient() {
        if (!properties.isShadowEvaluation()) {
            return null;
        }
        AiWorkflowProperties.Workflow shadow = new AiWorkflowProperties.Workflow();
        shadow.setVendor(properties.getShadowVendor());
        shadow.setModel(properties.getShadowModel());
        shadow.setTemperature(properties.getEvaluation().getTemperature());
        return clientFor("evaluation-shadow", shadow);
    }

    // =========================================================================
    // Wiring
    // =========================================================================

    private ChatClient clientFor(String workflowName, AiWorkflowProperties.Workflow workflow) {
        ChatModel model = resolveVendor(workflowName, workflow.getVendor());

        if (workflow.getModel() == null || workflow.getModel().isBlank()) {
            throw new IllegalStateException(
                    "Workflow '" + workflowName + "' has no model configured. "
                            + "Set app.ai." + workflowName + ".model to a model ID the provider currently sells.");
        }

        log.info("AI workflow '{}' bound to {}", workflowName, workflow);

        return ChatClient.builder(model)
                .defaultOptions(ChatOptions.builder()
                        .model(workflow.getModel())
                        .temperature(workflow.getTemperature())
                        .build())
                .build();
    }

    /**
     * Maps a configured vendor string onto one of the {@link ChatModel} beans
     * that actually exist in this build.
     *
     * <p>The failure message lists what <em>is</em> available rather than a
     * hardcoded list of what used to be, because the previous message ("Supported:
     * openai, anthropic") would have been a lie the moment a third starter was
     * added and a misdirection if a starter was present but its API key was not.
     */
    private ChatModel resolveVendor(String workflowName, String configuredVendor) {
        String requested = normalize(configuredVendor);

        if (requested.isEmpty()) {
            throw new IllegalStateException(
                    "Workflow '" + workflowName + "' has no vendor configured. "
                            + "Set app.ai." + workflowName + ".vendor to one of: " + modelsByVendor.keySet());
        }

        String key = properties.getVendorAliases().getOrDefault(requested, requested);
        ChatModel model = modelsByVendor.get(key);

        if (model == null) {
            throw new IllegalStateException(
                    "Workflow '" + workflowName + "' is configured for vendor '" + configuredVendor
                            + "' (resolved to '" + key + "') but no such ChatModel is available. "
                            + "Available: " + modelsByVendor.keySet() + ". "
                            + "Known aliases: " + properties.getVendorAliases() + ". "
                            + "A provider appears here only when both its Spring AI starter is on the "
                            + "classpath and its API key is set.");
        }
        return model;
    }

    /**
     * Derives a stable vendor key from the model implementation class:
     * {@code OpenAiChatModel} → {@code openai}, {@code AnthropicChatModel} →
     * {@code anthropic}, {@code VertexAiGeminiChatModel} → {@code vertexaigemini}.
     *
     * <p>Keyed off the class rather than the bean name because bean names are an
     * auto-configuration detail that Spring AI has already renamed once
     * ({@code spring-ai-openai-spring-boot-starter} →
     * {@code spring-ai-starter-model-openai}), whereas the model class name is
     * public API. {@code ClassUtils.getUserClass} unwraps any CGLIB proxy so a
     * decorated bean still resolves to the same key.
     */
    static String vendorKeyOf(ChatModel model) {
        String name = ClassUtils.getUserClass(model).getSimpleName();
        int suffix = name.indexOf(CHAT_MODEL_SUFFIX);
        if (suffix > 0) {
            name = name.substring(0, suffix);
        }
        return normalize(name);
    }

    /** Lowercases and drops separators, so {@code vertex-ai-gemini} and {@code VertexAiGemini} agree. */
    static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
