package com.interviewengine.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the provider seam in {@link AiConfig} (PRD v2.1 §9.1).
 *
 * <h2>What is actually being asserted</h2>
 *
 * <p>The claim this configuration makes is specific: <em>switching AI provider is
 * configuration, not code.</em> That claim is only worth anything if it is
 * checked, and it cannot be checked by calling a real provider — that would test
 * OpenAI's uptime, cost money, and pass or fail for reasons unrelated to this
 * code.
 *
 * <p>So the tests below use fake {@link ChatModel} implementations whose class
 * names imitate real Spring AI ones. That is the whole mechanism: vendor keys are
 * derived from the model class, so a fake named {@code DeepSeekChatModel} proves
 * the resolution path for a provider this build has never seen — which is exactly
 * the scenario the design is for.
 */
class AiConfigTest {

    // ── Fakes named after the classes Spring AI would supply ─────────────────

    static class OpenAiChatModel implements ChatModel {
        @Override public ChatResponse call(Prompt prompt) { return null; }
    }

    static class AnthropicChatModel implements ChatModel {
        @Override public ChatResponse call(Prompt prompt) { return null; }
    }

    static class VertexAiGeminiChatModel implements ChatModel {
        @Override public ChatResponse call(Prompt prompt) { return null; }
    }

    /** A provider that did not exist when this code was written. That is the point. */
    static class DeepSeekChatModel implements ChatModel {
        @Override public ChatResponse call(Prompt prompt) { return null; }
    }

    private static ObjectProvider<ChatModel> provided(ChatModel... models) {
        return new ObjectProvider<>() {
            @Override public ChatModel getObject(Object... args) { return models[0]; }
            @Override public ChatModel getObject() { return models[0]; }
            @Override public ChatModel getIfAvailable() { return models.length == 0 ? null : models[0]; }
            @Override public ChatModel getIfUnique() { return models.length == 1 ? models[0] : null; }
            @Override public Stream<ChatModel> stream() { return Stream.of(models); }
            @Override public Stream<ChatModel> orderedStream() { return Stream.of(models); }
        };
    }

    private static AiWorkflowProperties props(String vendor, String model) {
        AiWorkflowProperties p = new AiWorkflowProperties();
        p.getQuestion().setVendor(vendor);
        p.getQuestion().setModel(model);
        return p;
    }

    // =========================================================================
    // Vendor key derivation
    // =========================================================================

    @Test
    void vendorKeysAreDerivedFromTheModelClassName() {
        assertThat(AiConfig.vendorKeyOf(new OpenAiChatModel())).isEqualTo("openai");
        assertThat(AiConfig.vendorKeyOf(new AnthropicChatModel())).isEqualTo("anthropic");
        assertThat(AiConfig.vendorKeyOf(new VertexAiGeminiChatModel())).isEqualTo("vertexaigemini");
        assertThat(AiConfig.vendorKeyOf(new DeepSeekChatModel())).isEqualTo("deepseek");
    }

    /** Config is written by humans; casing and hyphens should not matter. */
    @Test
    void vendorNamesNormaliseAwayCasingAndSeparators() {
        assertThat(AiConfig.normalize("Vertex-AI-Gemini")).isEqualTo("vertexaigemini");
        assertThat(AiConfig.normalize("  OpenAI  ")).isEqualTo("openai");
        assertThat(AiConfig.normalize("vertex_ai_gemini")).isEqualTo("vertexaigemini");
        assertThat(AiConfig.normalize(null)).isEmpty();
    }

    // =========================================================================
    // The claim: a new provider needs no Java change
    // =========================================================================

    /**
     * The headline test. {@code DeepSeekChatModel} is not referenced anywhere in
     * {@code src/main}; it resolves purely because its starter would have put a
     * bean on the classpath and configuration named it.
     */
    @Test
    void aProviderThisCodeHasNeverHeardOfResolvesFromConfigurationAlone() {
        AiConfig config = new AiConfig(props("deepseek", "deepseek-chat"),
                provided(new DeepSeekChatModel()));

        assertThat(config.questionChatClient()).isNotNull();
    }

    @Test
    void eachWorkflowCanUseADifferentProvider() {
        AiWorkflowProperties p = new AiWorkflowProperties();
        p.getQuestion().setVendor("openai");
        p.getQuestion().setModel("gpt-5.6-luna");
        p.getFollowup().setVendor("deepseek");
        p.getFollowup().setModel("deepseek-chat");
        p.getEvaluation().setVendor("anthropic");
        p.getEvaluation().setModel("claude-haiku-4-5");

        AiConfig config = new AiConfig(p, provided(
                new OpenAiChatModel(), new AnthropicChatModel(), new DeepSeekChatModel()));

        assertThat(config.questionChatClient()).isNotNull();
        assertThat(config.followupChatClient()).isNotNull();
        assertThat(config.evaluationChatClient()).isNotNull();
    }

    // =========================================================================
    // Aliases
    // =========================================================================

    @Test
    void friendlyAliasesResolveToTheDerivedKey() {
        for (String alias : List.of("claude", "Claude", "CLAUDE")) {
            AiConfig config = new AiConfig(props(alias, "claude-haiku-4-5"),
                    provided(new AnthropicChatModel()));
            assertThat(config.questionChatClient())
                    .as("alias '%s' should resolve to anthropic", alias)
                    .isNotNull();
        }
    }

    /** "google" is what someone types; "vertexaigemini" is what the class is called. */
    @Test
    void googleResolvesToTheGeminiModelClass() {
        AiConfig config = new AiConfig(props("google", "gemini-2.5-flash-lite"),
                provided(new VertexAiGeminiChatModel()));

        assertThat(config.questionChatClient()).isNotNull();
    }

    @Test
    void anUnanticipatedProviderCanBeAliasedWithoutABuild() {
        AiWorkflowProperties p = props("cheap", "deepseek-chat");
        p.getVendorAliases().put("cheap", "deepseek");

        AiConfig config = new AiConfig(p, provided(new DeepSeekChatModel()));

        assertThat(config.questionChatClient()).isNotNull();
    }

    // =========================================================================
    // Failure modes — these matter more than the happy path
    // =========================================================================

    /**
     * A misconfigured vendor must fail at startup, not on the first candidate's
     * interview. Evaluation runs on a background worker; a runtime failure there
     * surfaces as a report that silently never arrives.
     */
    @Test
    void anUnknownVendorFailsFastAndSaysWhatIsAvailable() {
        AiConfig config = new AiConfig(props("groq", "llama-3.3-70b"),
                provided(new OpenAiChatModel(), new AnthropicChatModel()));

        assertThatThrownBy(config::questionChatClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("groq")
                .hasMessageContaining("openai")
                .hasMessageContaining("anthropic");
    }

    /**
     * The most likely real-world misconfiguration: the starter is on the
     * classpath but the API key is unset, so Spring AI never creates the bean.
     * The message has to point at the key rather than at the vendor string,
     * which is correct.
     */
    @Test
    void aVendorWhoseApiKeyIsMissingExplainsThatRatherThanJustFailing() {
        AiConfig config = new AiConfig(props("anthropic", "claude-haiku-4-5"),
                provided(new OpenAiChatModel()));

        assertThatThrownBy(config::questionChatClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API key");
    }

    @Test
    void aBlankVendorIsRejected() {
        AiConfig config = new AiConfig(props("", "gpt-5.6-luna"), provided(new OpenAiChatModel()));

        assertThatThrownBy(config::questionChatClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no vendor configured");
    }

    /**
     * Model IDs go stale — this file has already outlived {@code gpt-4o} and
     * {@code gpt-5.4-nano}. A blank model must not reach the provider as an
     * empty string and come back as an opaque 400.
     */
    @Test
    void aBlankModelIsRejected() {
        AiConfig config = new AiConfig(props("openai", "  "), provided(new OpenAiChatModel()));

        assertThatThrownBy(config::questionChatClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no model configured");
    }

    @Test
    void noProvidersAtAllFailsWithAUsefulMessage() {
        AiConfig config = new AiConfig(props("openai", "gpt-5.6-luna"), provided());

        assertThatThrownBy(config::questionChatClient)
                .isInstanceOf(IllegalStateException.class);
    }
}
