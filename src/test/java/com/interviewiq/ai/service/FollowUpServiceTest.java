package com.interviewiq.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The follow-up decision (PRD v2.1 §7.5.2 step 9).
 *
 * <p>This call sits on the critical path — a candidate is waiting in silence for
 * the next question — so every failure mode here must degrade to "no follow-up"
 * rather than to an error.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FollowUpServiceTest {

    @Mock ChatClient chatClient;
    @Mock PromptTemplateService prompts;

    private FollowUpService service;

    @BeforeEach
    void setUp() {
        service = new FollowUpService(
                chatClient, prompts, new PiiRedactionService(),
                new QuestionSafetyFilter(), new ObjectMapper());

        when(prompts.render(anyString(), org.mockito.ArgumentMatchers.<Map<String, Object>>any()))
                .thenReturn("rendered prompt");
    }

    /** Stubs the chat client's fluent chain to return a canned body. */
    private void givenModelReturns(String content) {
        var spec = mock(ChatClient.ChatClientRequestSpec.class);
        var call = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenReturn(content);
    }

    private void givenModelThrows() {
        when(chatClient.prompt()).thenThrow(new RuntimeException("provider unavailable"));
    }

    // =========================================================================
    // The happy paths
    // =========================================================================

    @Test
    void asksAFollowUpWhenTheModelSaysSo() {
        givenModelReturns("""
                { "followUp": true, "question": "What specifically did you change to cut the latency?" }
                """);

        var decision = service.decide("How did you improve performance?", "We made it faster.", null);

        assertThat(decision.shouldFollowUp()).isTrue();
        assertThat(decision.question()).contains("specifically");
    }

    @Test
    void doesNotAskWhenTheModelDeclines() {
        givenModelReturns("{ \"followUp\": false, \"question\": null }");

        assertThat(service.decide("Q", "A thorough answer.", null).shouldFollowUp()).isFalse();
    }

    @Test
    void toleratesMarkdownFencesAroundTheJson() {
        givenModelReturns("""
                ```json
                { "followUp": true, "question": "Can you give a concrete example?" }
                ```
                """);

        assertThat(service.decide("Q", "A vague answer.", null).shouldFollowUp()).isTrue();
    }

    // =========================================================================
    // Never probe a candidate who did not answer
    // =========================================================================

    @Test
    void skipsTheCallEntirelyForABlankAnswer() {
        // A skipped question means the candidate had nothing to say. Probing is
        // uncomfortable and produces nothing — and skipping saves a call on the
        // critical path.
        assertThat(service.decide("Q", "", null).shouldFollowUp()).isFalse();
        assertThat(service.decide("Q", null, null).shouldFollowUp()).isFalse();
        assertThat(service.decide("Q", "   ", null).shouldFollowUp()).isFalse();
    }

    // =========================================================================
    // Degradation — a candidate is waiting
    // =========================================================================

    @Test
    void continuesWithoutAFollowUpWhenTheProviderFails() {
        givenModelThrows();

        // The next bank question is a perfectly good outcome; an exception here
        // would break a live interview over an optional probe.
        assertThat(service.decide("Q", "An answer.", null).shouldFollowUp()).isFalse();
    }

    @Test
    void continuesWithoutAFollowUpOnUnparseableOutput() {
        givenModelReturns("I think you should ask about their testing approach.");

        assertThat(service.decide("Q", "An answer.", null).shouldFollowUp()).isFalse();
    }

    @Test
    void continuesWithoutAFollowUpOnAnEmptyResponse() {
        givenModelReturns("");

        assertThat(service.decide("Q", "An answer.", null).shouldFollowUp()).isFalse();
    }

    @Test
    void treatsAFollowUpFlagWithNoQuestionAsNoFollowUp() {
        givenModelReturns("{ \"followUp\": true, \"question\": \"\" }");

        assertThat(service.decide("Q", "An answer.", null).shouldFollowUp()).isFalse();
    }

    // =========================================================================
    // The safety filter applies here too
    // =========================================================================

    @Test
    void refusesAFollowUpThatDriftsIntoAProhibitedTopic() {
        givenModelReturns("""
                { "followUp": true, "question": "You mentioned a career break — are you married?" }
                """);

        // Mid-interview is the worst possible place to discover the safety filter
        // was only wired to the upload path.
        assertThat(service.decide("Q", "I took a break in 2024.", null).shouldFollowUp()).isFalse();
    }

    @Test
    void allowsALegitimateProbe() {
        givenModelReturns("""
                { "followUp": true, "question": "Which part of that migration did you own personally?" }
                """);

        assertThat(service.decide("Q", "We migrated the whole platform.", null).shouldFollowUp()).isTrue();
    }
}
