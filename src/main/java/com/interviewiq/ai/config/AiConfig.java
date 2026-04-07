package com.interviewiq.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI configuration.
 *
 * <p>Spring AI's OpenAI starter auto-configures a {@link ChatClient.Builder} but does
 * NOT create a {@link ChatClient} bean by default. This configuration promotes the
 * builder to a fully constructed singleton {@code ChatClient} that injection points
 * in workers ({@code QuestionGenerationWorker}, {@code EvaluationWorker}) can share.
 *
 * <p>The default model and temperature are set in {@code application.yml} under
 * {@code spring.ai.openai.chat.options}:
 * <pre>
 *   model: gpt-4o
 *   temperature: 0.2
 * </pre>
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
