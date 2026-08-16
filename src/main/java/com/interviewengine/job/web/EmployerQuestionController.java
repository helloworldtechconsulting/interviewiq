package com.interviewengine.job.web;

import com.interviewengine.job.domain.EmployerQuestion;
import com.interviewengine.job.service.EmployerQuestionService;
import com.interviewengine.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The employer question bank (PRD v2.1 §7.5.8, §11 — four new endpoints).
 *
 * <ul>
 *   <li>{@code GET    /api/v1/jobs/{jobId}/questions}         — list, with safety status</li>
 *   <li>{@code POST   /api/v1/jobs/{jobId}/questions}         — upload; returns per-question results</li>
 *   <li>{@code DELETE /api/v1/jobs/{jobId}/questions/{id}}    — remove</li>
 *   <li>{@code PUT    /api/v1/jobs/{jobId}/questions/order}   — reorder</li>
 * </ul>
 *
 * <p>The upload response deliberately returns a result <em>per question</em>
 * rather than a single success flag. A partially-refused upload is the normal
 * case — a recruiter pastes ten questions and one touches marital status — and
 * §7.5.8 requires each refusal to name its category so they can correct it.
 */
@RestController
@RequestMapping("/api/v1/jobs/{jobId}/questions")
public class EmployerQuestionController {

    private final EmployerQuestionService questionService;

    public EmployerQuestionController(EmployerQuestionService questionService) {
        this.questionService = questionService;
    }

    @GetMapping
    public ApiResponse<List<QuestionResponse>> list(@PathVariable UUID jobId) {
        return ApiResponse.ok(questionService.list(jobId).stream().map(QuestionResponse::from).toList());
    }

    /**
     * Uploads questions, either as a list or as pasted text one per line.
     *
     * <p>Both forms from §7.5.8 land here: a CSV parsed client-side into
     * {@code questions}, or a textarea's contents in {@code pastedText}.
     */
    @PostMapping
    public ApiResponse<UploadResponse> upload(@PathVariable UUID jobId,
                                              @Valid @RequestBody UploadRequest request) {

        List<String> texts = request.questions() != null && !request.questions().isEmpty()
                ? request.questions()
                : questionService.parsePastedQuestions(request.pastedText());

        List<EmployerQuestion> saved = questionService.addQuestions(jobId, texts);

        List<QuestionResponse> results = saved.stream().map(QuestionResponse::from).toList();
        long approved = results.stream().filter(QuestionResponse::usable).count();

        return ApiResponse.ok(new UploadResponse(
                results.size(),
                (int) approved,
                results.size() - (int) approved,
                results));
    }

    @DeleteMapping("/{questionId}")
    public ApiResponse<Void> delete(@PathVariable UUID jobId, @PathVariable UUID questionId) {
        questionService.delete(questionId);
        return ApiResponse.ok(null);
    }

    /**
     * Reorders the bank.
     *
     * <p>Order is not cosmetic: when an employer supplies more questions than the
     * tier holds, the extras rotate across candidates in this order, so it
     * decides which questions every candidate is guaranteed to be asked.
     */
    @PutMapping("/order")
    public ApiResponse<Void> reorder(@PathVariable UUID jobId,
                                     @Valid @RequestBody ReorderRequest request) {
        questionService.reorder(jobId, request.questionIds());
        return ApiResponse.ok(null);
    }

    // ── DTOs ────────────────────────────────────────────────────────────────

    public record UploadRequest(List<String> questions, String pastedText) {}

    public record ReorderRequest(
            @NotEmpty(message = "An ordering is required.") List<UUID> questionIds) {}

    /**
     * @param acceptedCount questions that cleared the safety filter
     * @param refusedCount  questions refused; each carries its category below
     */
    public record UploadResponse(int totalCount,
                                 int acceptedCount,
                                 int refusedCount,
                                 List<QuestionResponse> questions) {}

    /**
     * @param rejectionReason the prohibited category, named so the employer can
     *                        correct the question (§7.5.8)
     * @param usable          whether this question will be asked
     */
    public record QuestionResponse(UUID id,
                                   String questionText,
                                   String safetyStatus,
                                   String rejectionReason,
                                   int displayOrder,
                                   boolean usable,
                                   OffsetDateTime createdAt) {

        static QuestionResponse from(EmployerQuestion q) {
            return new QuestionResponse(
                    q.getId(),
                    q.getQuestionText(),
                    q.getSafetyStatus().name(),
                    q.getRejectionReason(),
                    q.getDisplayOrder(),
                    q.isUsable(),
                    q.getCreatedAt());
        }
    }
}
