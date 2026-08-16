package com.interviewengine.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.interviewengine.job.domain.DurationTier;
import com.interviewengine.shared.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Stage 2 of two-stage question generation: assembling one candidate's question
 * set from the opening's bank (PRD v2.1 §7.5, INTIQ-17).
 *
 * <h2>Fixed core plus rotating tail</h2>
 *
 * <p>Each set is:
 *
 * <ul>
 *   <li><strong>Core</strong> — identical for every candidate on the opening.
 *       This is what makes two candidates comparable at all. Employer questions
 *       occupy this segment first (§7.5.8), then the bank's automatically
 *       selected core fills the rest.</li>
 *   <li><strong>Rotating</strong> — sampled from the remaining bank, seeded on
 *       the candidate id. Gives variety and defeats leaking: candidates for one
 *       role compare notes within hours, and a rotating tail means the third
 *       candidate cannot fully prepare from what the first posted.</li>
 *   <li><strong>Resume-anchored</strong> — supplied by the caller, unique to the
 *       person.</li>
 * </ul>
 *
 * <h2>The sampling is seeded, not random</h2>
 *
 * <p>{@code new Random(candidateId.hashCode())} rather than an unseeded one.
 * Two consequences that both matter: a regenerated set is identical to the
 * original, so a retry after a transient failure does not hand the candidate a
 * different interview; and a support question of the form "why did this
 * candidate get that question" has an answer that can be reproduced rather than
 * guessed at.
 *
 * <p>It also means the sampling is <em>not</em> cryptographic and must never be
 * used as one. It is a shuffle, chosen for reproducibility.
 *
 * <h2>No LLM call happens here</h2>
 *
 * <p>The rotating tail is pure selection over questions that already exist. On a
 * 25-candidate opening that is 25 sets assembled for the cost of one bank
 * generation plus the resume calls — the saving that makes two-stage cheaper
 * than what it replaces, not just better.
 */
@Service
public class QuestionAssemblyService {

    private static final Logger log = LoggerFactory.getLogger(QuestionAssemblyService.class);

    /**
     * Roughly a fifth of an interview is resume-anchored (§7.5), so the bank
     * supplies the rest. Expressed as a divisor rather than a constant so it
     * scales with the tier — a 25-question Comprehensive interview should not
     * have the same three resume questions as a 10-question Quick screen.
     */
    private static final int RESUME_SHARE_DIVISOR = 5;

    private final ObjectMapper objectMapper;

    public QuestionAssemblyService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Builds the question set for one candidate.
     *
     * @param bankJson          the opening's bank, from {@link QuestionBankService}
     * @param employerQuestions the employer's own questions, asked first
     * @param resumeQuestions   resume-anchored questions, or empty when no résumé
     * @param tier              sets how many questions the interview needs
     * @param candidateId       the sampling seed
     * @return the ordered question set as a JSON array, ready to persist
     */
    public String assemble(String bankJson,
                           List<String> employerQuestions,
                           List<String> resumeQuestions,
                           DurationTier tier,
                           UUID candidateId) {
        return assemble(bankJson, employerQuestions, resumeQuestions, tier, candidateId, List.of());
    }

    /**
     * Assembly that also honours auto-retirement (INTIQ-93).
     *
     * <p>Retired questions are dropped from the rotating pool but <strong>not</strong>
     * from the core. A retired core question is a real problem — it means the
     * comparability set contains something that does not discriminate — but
     * silently dropping it mid-opening is worse, because candidates interviewed
     * before and after the retirement would then have different cores and their
     * scores would stop being comparable without anything saying so. The core is
     * fixed for the life of the opening by design; fixing a bad core question is
     * a regenerate-the-bank operation, not a quiet omission.
     *
     * @param retiredQuestionIds bank ids the retirement sweep has taken out
     */
    public String assemble(String bankJson,
                           List<String> employerQuestions,
                           List<String> resumeQuestions,
                           DurationTier tier,
                           UUID candidateId,
                           List<String> retiredQuestionIds) {

        List<ObjectNode> bank = readBank(bankJson);
        List<String> coreIds = readCoreIds(bankJson);
        Set<String> retired = new HashSet<>(retiredQuestionIds);

        int target = tier.getQuestionCount();
        int resumeSlots = Math.min(resumeQuestions.size(), Math.max(1, target / RESUME_SHARE_DIVISOR));

        List<ObjectNode> chosen = new ArrayList<>(target);

        // ── 1. Employer questions take the core segment first (§7.5.8) ───────
        for (String text : employerQuestions) {
            if (chosen.size() >= target - resumeSlots) {
                break;
            }
            chosen.add(question(text, "EMPLOYER", "RELEVANCE"));
        }

        // ── 2. The bank's comparability core ─────────────────────────────────
        Map<String, ObjectNode> byId = new LinkedHashMap<>();
        bank.forEach(q -> byId.put(q.get("id").asText(), q));

        for (String coreId : coreIds) {
            if (chosen.size() >= target - resumeSlots) {
                break;
            }
            ObjectNode q = byId.remove(coreId);
            if (q != null) {
                chosen.add(fromBank(q, "CORE"));
            }
        }

        // ── 3. Rotating tail, seeded on the candidate ────────────────────────
        // Retired questions leave the rotating pool here. The core above is
        // deliberately untouched — see the method comment.
        List<ObjectNode> remaining = new ArrayList<>(byId.values().stream()
                .filter(q -> !retired.contains(q.get("id").asText()))
                .toList());
        Collections.shuffle(remaining, new Random(candidateId.hashCode()));

        for (ObjectNode q : remaining) {
            if (chosen.size() >= target - resumeSlots) {
                break;
            }
            chosen.add(fromBank(q, "ROTATING"));
        }

        // ── 4. Resume-anchored, interleaved rather than appended ─────────────
        List<ObjectNode> resume = new ArrayList<>(resumeSlots);
        for (int i = 0; i < resumeSlots; i++) {
            resume.add(question(resumeQuestions.get(i), "RESUME", "RELEVANCE"));
        }
        List<ObjectNode> ordered = interleave(chosen, resume);

        if (ordered.isEmpty()) {
            throw new AiServiceException("Assembled an empty question set for candidate " + candidateId);
        }

        ArrayNode out = objectMapper.createArrayNode();
        for (int i = 0; i < ordered.size(); i++) {
            ObjectNode q = ordered.get(i);
            q.put("order", i + 1);
            out.add(q);
        }

        log.debug("Assembled {} questions for candidateId={} (target {}, resume {})",
                ordered.size(), candidateId, target, resumeSlots);

        return out.toString();
    }

    /**
     * Spreads the resume questions through the set rather than clustering them.
     *
     * <p>Three consecutive questions about the candidate's own history reads as a
     * different interview inside the interview, and it front- or back-loads the
     * easiest questions depending on where they land. Spacing them keeps the
     * difficulty curve even and the conversation varied.
     */
    private List<ObjectNode> interleave(List<ObjectNode> main, List<ObjectNode> resume) {
        if (resume.isEmpty()) {
            return main;
        }
        List<ObjectNode> out = new ArrayList<>(main.size() + resume.size());
        int total = main.size() + resume.size();
        // Place a resume question at roughly even intervals, never first — the
        // opening question should be about the role, not the person's CV.
        int interval = Math.max(2, total / (resume.size() + 1));

        int resumeIndex = 0;
        for (int i = 0; i < main.size(); i++) {
            out.add(main.get(i));
            if (resumeIndex < resume.size() && (out.size() % interval == 0)) {
                out.add(resume.get(resumeIndex++));
            }
        }
        while (resumeIndex < resume.size()) {
            out.add(resume.get(resumeIndex++));
        }
        return out;
    }

    private ObjectNode fromBank(ObjectNode bankQuestion, String segment) {
        ObjectNode q = bankQuestion.deepCopy();
        q.put("segment", segment);
        q.put("source", "AI");
        // Carried through so per-question telemetry can attribute an outcome
        // back to the bank entry that produced it (INTIQ-93).
        q.put("bankQuestionId", bankQuestion.get("id").asText());
        return q;
    }

    private ObjectNode question(String text, String segment, String dimension) {
        ObjectNode q = objectMapper.createObjectNode();
        q.put("text", text);
        q.put("segment", segment);
        q.put("source", "EMPLOYER".equals(segment) ? "EMPLOYER" : "AI");
        q.put("dimension", dimension);
        q.put("category", segment);
        return q;
    }

    private List<ObjectNode> readBank(String bankJson) {
        try {
            JsonNode root = objectMapper.readTree(bankJson);
            List<ObjectNode> out = new ArrayList<>();
            for (JsonNode q : root.path("questions")) {
                out.add((ObjectNode) q);
            }
            return out;
        } catch (Exception e) {
            throw new AiServiceException("Question bank could not be read", e);
        }
    }

    private List<String> readCoreIds(String bankJson) {
        try {
            JsonNode root = objectMapper.readTree(bankJson);
            List<String> ids = new ArrayList<>();
            root.path("coreQuestionIds").forEach(n -> ids.add(n.asText()));
            return ids;
        } catch (Exception e) {
            throw new AiServiceException("Question bank core ids could not be read", e);
        }
    }
}
