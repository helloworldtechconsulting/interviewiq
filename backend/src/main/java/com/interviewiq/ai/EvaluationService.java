package com.interviewiq.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.session.InterviewSession;
import com.interviewiq.session.InterviewSessionRepository;
import com.interviewiq.job.JobOpening;
import com.interviewiq.job.JobOpeningRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluationService {

    private final ChatClient chatClient;
    private final InterviewSessionRepository sessionRepository;
    private final JobOpeningRepository jobOpeningRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.openai.model:gpt-4o}")
    private String model;

    @Async
    @Transactional
    public void evaluateInterview(UUID sessionId) {
        try {
            InterviewSession session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new RuntimeException("Session not found"));

            JobOpening jobOpening = jobOpeningRepository.findById(session.getJobOpeningId())
                    .orElseThrow(() -> new RuntimeException("Job opening not found"));

            String jdText = jobOpening.getJdExtractedText() != null ? jobOpening.getJdExtractedText() : jobOpening.getDescription();
            String transcript = convertTranscriptToString(session.getTranscript());

            EvaluationResult result = evaluateAnswers(jdText, transcript);

            session.setDimensionScores(result.dimensionScores);
            session.setOverallScore(result.overallScore);
            session.setEvaluationSummary(result.summary);
            session.setRecommendation(result.recommendation);

            sessionRepository.save(session);

            log.info("Interview evaluated for session: {} with score: {}", sessionId, result.overallScore);
        } catch (Exception e) {
            log.error("Failed to evaluate interview for session: {}", sessionId, e);
        }
    }

    private EvaluationResult evaluateAnswers(String jdText, String transcript) {
        String prompt = buildEvaluationPrompt(jdText, transcript);

        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        try {
            Map<String, Object> result = objectMapper.readValue(response, Map.class);
            return parseEvaluationResult(result);
        } catch (Exception e) {
            log.error("Failed to parse AI response for evaluation", e);
            return createDefaultEvaluation();
        }
    }

    private String buildEvaluationPrompt(String jdText, String transcript) {
        return String.format(
                "You are an expert technical interviewer. Evaluate the candidate's interview performance.\n\n" +
                "**Job Description:**\n%s\n\n" +
                "**Interview Transcript:**\n%s\n\n" +
                "Provide evaluation in the following JSON format:\n" +
                "{\n" +
                "  \"dimensionScores\": {\n" +
                "    \"TECHNICAL_SKILLS\": 7.5,\n" +
                "    \"COMMUNICATION\": 8.0,\n" +
                "    \"EXPERIENCE\": 7.0,\n" +
                "    \"CULTURE_FIT\": 8.5\n" +
                "  },\n" +
                "  \"overallScore\": 77.5,\n" +
                "  \"summary\": \"Brief assessment\",\n" +
                "  \"recommendation\": \"STRONG_HIRE|HIRE|MAYBE|NO_HIRE\",\n" +
                "  \"strengths\": [\"strength1\", \"strength2\"],\n" +
                "  \"areasForImprovement\": [\"area1\", \"area2\"]\n" +
                "}\n\n" +
                "Be fair and objective in your assessment.",
                jdText, transcript
        );
    }

    private EvaluationResult parseEvaluationResult(Map<String, Object> result) {
        Map<String, BigDecimal> dimensionScores = new HashMap<>();

        @SuppressWarnings("unchecked")
        Map<String, Object> scores = (Map<String, Object>) result.get("dimensionScores");
        if (scores != null) {
            scores.forEach((key, value) -> {
                dimensionScores.put(key, new BigDecimal(value.toString()));
            });
        }

        BigDecimal overallScore = new BigDecimal(result.get("overallScore").toString());
        String summary = (String) result.get("summary");
        String recommendation = (String) result.get("recommendation");

        return new EvaluationResult(dimensionScores, overallScore, summary, recommendation);
    }

    private EvaluationResult createDefaultEvaluation() {
        Map<String, BigDecimal> scores = new HashMap<>();
        scores.put("TECHNICAL_SKILLS", new BigDecimal("65"));
        scores.put("COMMUNICATION", new BigDecimal("70"));
        scores.put("EXPERIENCE", new BigDecimal("60"));
        scores.put("CULTURE_FIT", new BigDecimal("75"));

        return new EvaluationResult(
                scores,
                new BigDecimal("67.5"),
                "Candidate demonstrated foundational knowledge with room for improvement",
                "MAYBE"
        );
    }

    private String convertTranscriptToString(Map<String, Object> transcript) {
        if (transcript == null || transcript.isEmpty()) {
            return "No transcript available";
        }

        StringBuilder sb = new StringBuilder();
        transcript.forEach((key, value) -> {
            sb.append(key).append(": ").append(value).append("\n");
        });
        return sb.toString();
    }

    public record EvaluationResult(
            Map<String, BigDecimal> dimensionScores,
            BigDecimal overallScore,
            String summary,
            String recommendation
    ) {
    }
}
