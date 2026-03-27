package com.interviewiq.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.job.JobOpening;
import com.interviewiq.job.JobOpeningRepository;
import com.interviewiq.candidate.Candidate;
import com.interviewiq.candidate.CandidateRepository;
import com.interviewiq.session.InterviewSession;
import com.interviewiq.session.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuestionGenerationService {

    private final ChatClient chatClient;
    private final InterviewSessionRepository sessionRepository;
    private final JobOpeningRepository jobOpeningRepository;
    private final CandidateRepository candidateRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.interview.max-questions:12}")
    private int maxQuestions;

    @Value("${app.interview.min-questions:8}")
    private int minQuestions;

    @Value("${app.openai.model:gpt-4o}")
    private String model;

    @Async
    @Transactional
    public void generateQuestionsForSession(UUID sessionId) {
        try {
            InterviewSession session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new RuntimeException("Session not found"));

            JobOpening jobOpening = jobOpeningRepository.findById(session.getJobOpeningId())
                    .orElseThrow(() -> new RuntimeException("Job opening not found"));

            Candidate candidate = candidateRepository.findById(session.getCandidateId())
                    .orElseThrow(() -> new RuntimeException("Candidate not found"));

            String jdText = jobOpening.getJdExtractedText() != null ? jobOpening.getJdExtractedText() : jobOpening.getDescription();
            String resumeText = candidate.getResumeExtractedText();

            Map<String, Object> questions = generateQuestions(jdText, resumeText);

            session.setQuestions(questions);
            sessionRepository.save(session);

            log.info("Questions generated for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Failed to generate questions for session: {}", sessionId, e);
        }
    }

    private Map<String, Object> generateQuestions(String jdText, String resumeText) {
        String prompt = buildQuestionGenerationPrompt(jdText, resumeText);

        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        try {
            Map<String, Object> result = objectMapper.readValue(response, Map.class);
            return result;
        } catch (Exception e) {
            log.error("Failed to parse AI response for questions", e);
            return createDefaultQuestions();
        }
    }

    private String buildQuestionGenerationPrompt(String jdText, String resumeText) {
        return String.format(
                "You are an expert technical interviewer. Based on the job description and candidate's resume, generate %d-%d interview questions.\n\n" +
                "**Job Description:**\n%s\n\n" +
                "**Candidate Resume:**\n%s\n\n" +
                "Generate questions in the following JSON format:\n" +
                "{\n" +
                "  \"questions\": [\n" +
                "    {\n" +
                "      \"id\": 1,\n" +
                "      \"question\": \"Question text?\",\n" +
                "      \"dimension\": \"TECHNICAL|BEHAVIORAL|SCENARIO|CULTURE_FIT\",\n" +
                "      \"rationale\": \"Why this question\",\n" +
                "      \"estimatedDuration\": 180\n" +
                "    }\n" +
                "  ]\n" +
                "}\n\n" +
                "Ensure diverse question types and balanced dimensions.",
                minQuestions, maxQuestions, jdText, resumeText
        );
    }

    private Map<String, Object> createDefaultQuestions() {
        List<Map<String, Object>> defaultQuestions = List.of(
                createQuestion(1, "Tell us about your professional background and experience.", "BEHAVIORAL", "Understanding candidate's career trajectory", 180),
                createQuestion(2, "What are your main technical strengths?", "TECHNICAL", "Assessing technical competency", 180),
                createQuestion(3, "Describe a challenging project you worked on and how you solved it.", "SCENARIO", "Evaluating problem-solving ability", 240),
                createQuestion(4, "How do you approach learning new technologies?", "BEHAVIORAL", "Assessing learning capability", 180),
                createQuestion(5, "What interests you about this role and our company?", "CULTURE_FIT", "Evaluating cultural alignment", 150)
        );

        Map<String, Object> result = new HashMap<>();
        result.put("questions", defaultQuestions);
        result.put("totalCount", defaultQuestions.size());
        return result;
    }

    private Map<String, Object> createQuestion(int id, String question, String dimension, String rationale, int duration) {
        Map<String, Object> q = new HashMap<>();
        q.put("id", id);
        q.put("question", question);
        q.put("dimension", dimension);
        q.put("rationale", rationale);
        q.put("estimatedDuration", duration);
        return q;
    }
}
