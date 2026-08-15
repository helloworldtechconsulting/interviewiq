package com.interviewiq.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Locale;

/**
 * Builds one {@link ChatClient} per AI workflow (PRD v2.1 §9.1).
 *
 * <p>"Each workflow gets its own {@code ChatClient} bean built from configuration
 * and injected by qualifier." Vendor and model are both configuration values, so
 * switching a workflow between OpenAI and Anthropic — or failing over during a
 * provider outage — is a config change and a redeploy, not a rewrite. Both
 * vendors' models implement {@code ChatModel}, which is what makes that true.
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

    private final AiWorkflowProperties properties;
    private final ObjectProvider<OpenAiChatModel> openAi;
    private final ObjectProvider<AnthropicChatModel> anthropic;

    public AiConfig(AiWorkflowProperties properties,
                    ObjectProvider<OpenAiChatModel> openAi,
                    ObjectProvider<AnthropicChatModel> anthropic) {
        this.properties = properties;
        this.openAi     = openAi;
        this.anthropic  = anthropic;
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
     * <p>Returns null when shadow mode is off, so the bean exists for injection
     * but the shadow path is skipped — the alternative, a conditional bean, makes
     * every injection point optional for a flag that flips once.
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
        String vendor = workflow.getVendor() == null
                ? "" : workflow.getVendor().trim().toLowerCase(Locale.ROOT);

        ChatModel model = switch (vendor) {
            case "openai"    -> requireModel(openAi.getIfAvailable(), "openai", workflowName);
            case "anthropic" -> requireModel(anthropic.getIfAvailable(), "anthropic", workflowName);
            default -> throw new IllegalStateException(
                    "Unknown AI vendor '" + workflow.getVendor() + "' for workflow '" + workflowName
                            + "'. Supported: openai, anthropic.");
        };

        log.info("AI workflow '{}' bound to {}", workflowName, workflow);

        return ChatClient.builder(model)
                .defaultOptions(optionsFor(vendor, workflow))
                .build();
    }

    private ChatOptions optionsFor(String vendor, AiWorkflowProperties.Workflow workflow) {
        // Model and temperature are set per client rather than globally, because
        // the whole point of this configuration is that the three workflows do
        // not share a model.
        return "anthropic".equals(vendor)
                ? AnthropicChatOptions.builder()
                        .model(workflow.getModel())
                        .temperature(workflow.getTemperature())
                        .build()
                : OpenAiChatOptions.builder()
                        .model(workflow.getModel())
                        .temperature(workflow.getTemperature())
                        .build();
    }

    private <T extends ChatModel> T requireModel(T model, String vendor, String workflowName) {
        if (model == null) {
            throw new IllegalStateException(
                    "Workflow '" + workflowName + "' is configured for vendor '" + vendor
                            + "' but no " + vendor + " ChatModel is available. "
                            + "Check that the API key for that provider is set.");
        }
        return model;
    }
}
