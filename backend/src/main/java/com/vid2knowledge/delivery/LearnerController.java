package com.vid2knowledge.delivery;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import com.vid2knowledge.common.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/learner")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class LearnerController {

    private final TenantAccessService access;
    private final LearnerService learners;
    private final FlashcardReviewService reviews;
    private final AssessmentService assessments;

    public LearnerController(
            TenantAccessService access,
            LearnerService learners,
            FlashcardReviewService reviews,
            AssessmentService assessments
    ) {
        this.access = access;
        this.learners = learners;
        this.reviews = reviews;
        this.assessments = assessments;
    }

    @GetMapping("/assignments")
    public List<LearnerService.AssignmentSummary> assignments(
            @PathVariable UUID organizationId,
            Authentication authentication
    ) {
        return learners.assignments(learner(organizationId, authentication));
    }

    @GetMapping("/assignments/{assignmentId}")
    public LearnerService.AssignmentView get(
            @PathVariable UUID organizationId,
            @PathVariable UUID assignmentId,
            Authentication authentication
    ) {
        return learners.get(learner(organizationId, authentication), assignmentId);
    }

    @PostMapping("/assignments/{assignmentId}/start")
    public LearnerService.AssignmentView start(
            @PathVariable UUID organizationId,
            @PathVariable UUID assignmentId,
            Authentication authentication
    ) {
        return learners.start(learner(organizationId, authentication), assignmentId);
    }

    @PostMapping("/assignments/{assignmentId}/attempts")
    public LearnerService.AttemptResult submit(
            @PathVariable UUID organizationId,
            @PathVariable UUID assignmentId,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 160) String idempotencyKey,
            @Valid @RequestBody SubmitAttempt request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return learners.submit(
                learner(organizationId, authentication), assignmentId, request.answers(), idempotencyKey,
                correlation(servletRequest)
        );
    }

    @GetMapping("/reviews/due")
    public List<FlashcardReviewService.DueCard> dueReviews(
            @PathVariable UUID organizationId,
            Authentication authentication
    ) {
        return reviews.due(learner(organizationId, authentication));
    }

    @GetMapping("/reviews/summary")
    public FlashcardReviewService.ReviewSummary reviewSummary(
            @PathVariable UUID organizationId,
            Authentication authentication
    ) {
        return reviews.summary(learner(organizationId, authentication));
    }

    @GetMapping("/assignments/{assignmentId}/assessments/overview")
    public AssessmentService.AssessmentOverview assessmentOverview(
            @PathVariable UUID organizationId,
            @PathVariable UUID assignmentId,
            Authentication authentication
    ) {
        return assessments.overview(learner(organizationId, authentication), assignmentId);
    }

    @PostMapping("/assignments/{assignmentId}/assessments")
    public AssessmentService.AssessmentSnapshot startAssessment(
            @PathVariable UUID organizationId,
            @PathVariable UUID assignmentId,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 160) String idempotencyKey,
            @Valid @RequestBody StartAssessment request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return assessments.start(
                learner(organizationId, authentication), assignmentId, request.mode(),
                idempotencyKey, correlation(servletRequest)
        );
    }

    @PostMapping("/assessments/{snapshotId}/submit")
    public AssessmentService.AssessmentResult submitAssessment(
            @PathVariable UUID organizationId,
            @PathVariable UUID snapshotId,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 160) String idempotencyKey,
            @Valid @RequestBody SubmitAssessment request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return assessments.submit(
                learner(organizationId, authentication), snapshotId, request.answers(),
                idempotencyKey, correlation(servletRequest)
        );
    }

    @PostMapping("/assignments/{assignmentId}/flashcards/{cardId}/reviews")
    public FlashcardReviewService.ReviewResult reviewFlashcard(
            @PathVariable UUID organizationId,
            @PathVariable UUID assignmentId,
            @PathVariable String cardId,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 160) String idempotencyKey,
            @Valid @RequestBody ReviewFlashcard request,
            Authentication authentication
    ) {
        return reviews.review(
                learner(organizationId, authentication), assignmentId, cardId,
                request.rating(), idempotencyKey
        );
    }

    @PostMapping("/assignments/{assignmentId}/feedback")
    @ResponseStatus(NO_CONTENT)
    public void feedback(
            @PathVariable UUID organizationId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody Feedback request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        learners.feedback(
                learner(organizationId, authentication), assignmentId, request.kind(), request.itemType(),
                request.itemId(), request.detail(), correlation(servletRequest)
        );
    }

    private CurrentActor learner(UUID organizationId, Authentication authentication) {
        return access.require(organizationId, authentication, CurrentActor.Role.LEARNER);
    }

    private static String correlation(HttpServletRequest request) {
        return request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE).toString();
    }

    public record SubmitAttempt(@NotEmpty @Size(max = 100) List<Integer> answers) {
    }

    public record Feedback(
            LearnerService.FeedbackKind kind,
            @Size(max = 24) String itemType,
            @Size(max = 120) String itemId,
            @Size(max = 2000) String detail
    ) {
    }

    public record ReviewFlashcard(@jakarta.validation.constraints.NotNull FsrsScheduler.Rating rating) {
    }

    public record StartAssessment(
            @jakarta.validation.constraints.NotNull AssessmentService.Mode mode
    ) {
    }

    public record SubmitAssessment(@NotEmpty @Size(max = 100) List<Integer> answers) {
    }
}
