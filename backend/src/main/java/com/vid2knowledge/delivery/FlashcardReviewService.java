package com.vid2knowledge.delivery;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.usage.application.IdempotencyConflictException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class FlashcardReviewService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int QUEUE_LIMIT = 50;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public FlashcardReviewService(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        this(jdbc, transactions, mapper, Clock.systemUTC());
    }

    FlashcardReviewService(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
        this.clock = clock;
    }

    public List<DueCard> due(CurrentActor learner) {
        return allCards(learner).stream()
                .filter(card -> !card.dueAt().isAfter(clock.instant()))
                .sorted(Comparator.comparing(DueCard::dueAt).thenComparing(DueCard::cardId))
                .limit(QUEUE_LIMIT)
                .toList();
    }

    public ReviewSummary summary(CurrentActor learner) {
        List<DueCard> cards = allCards(learner);
        int due = (int) cards.stream().filter(card -> !card.dueAt().isAfter(clock.instant())).count();
        Integer mastered = jdbc.queryForObject(
                """
                SELECT count(*) FROM flashcard_memory_states f
                JOIN assignments a ON a.id = f.assignment_id AND a.organization_id = f.organization_id
                JOIN assignment_recipients ar ON ar.assignment_id = f.assignment_id
                  AND ar.organization_id = f.organization_id AND ar.user_id = f.user_id
                WHERE f.organization_id = ? AND f.user_id = ? AND f.stability_days >= 21
                  AND a.state = 'PUBLISHED' AND a.available_at <= ?
                """,
                Integer.class, learner.organizationId(), learner.userId(), Timestamp.from(clock.instant())
        );
        List<LocalDate> reviewDates = jdbc.query(
                """
                SELECT DISTINCT (reviewed_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS review_date
                FROM flashcard_review_log
                WHERE organization_id = ? AND user_id = ?
                ORDER BY review_date DESC
                """,
                (result, row) -> result.getObject("review_date", LocalDate.class),
                learner.organizationId(), learner.userId()
        );
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        int reviewedToday = jdbc.queryForObject(
                """
                SELECT count(*) FROM flashcard_review_log
                WHERE organization_id = ? AND user_id = ?
                  AND (reviewed_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date = ?
                """,
                Integer.class, learner.organizationId(), learner.userId(), today
        );
        return new ReviewSummary(cards.size(), due, mastered == null ? 0 : mastered,
                reviewedToday, streak(new HashSet<>(reviewDates), today));
    }

    public ReviewResult review(
            CurrentActor learner,
            UUID assignmentId,
            String cardId,
            FsrsScheduler.Rating rating,
            String idempotencyKey
    ) {
        ReviewResult result = transactions.execute(status ->
                applyReview(learner, assignmentId, cardId, rating, idempotencyKey)
        );
        if (result == null) {
            throw new IllegalStateException("Could not persist flashcard review");
        }
        return result;
    }

    private ReviewResult applyReview(
            CurrentActor learner,
            UUID assignmentId,
            String cardId,
            FsrsScheduler.Rating rating,
            String idempotencyKey
    ) {
        jdbc.queryForObject(
                "SELECT CAST(pg_advisory_xact_lock(hashtextextended(?, 0)) AS text)",
                String.class, assignmentId + "|" + learner.userId() + "|" + cardId
        );
        List<StoredReview> existing = jdbc.query(
                """
                SELECT id, card_id, rating, next_due_at, stability_after, difficulty_after,
                       state_after, review_count_after, lapse_count_after, reviewed_at
                FROM flashcard_review_log
                WHERE assignment_id = ? AND user_id = ? AND idempotency_key = ?
                """,
                (result, row) -> new StoredReview(
                        result.getObject("id", UUID.class), result.getString("card_id"),
                        FsrsScheduler.Rating.valueOf(result.getString("rating")),
                        result.getTimestamp("next_due_at").toInstant(), result.getDouble("stability_after"),
                        result.getDouble("difficulty_after"), result.getString("state_after"),
                        result.getInt("review_count_after"), result.getInt("lapse_count_after"),
                        result.getTimestamp("reviewed_at").toInstant()
                ),
                assignmentId, learner.userId(), idempotencyKey
        );
        if (!existing.isEmpty()) {
            StoredReview stored = existing.getFirst();
            if (!stored.cardId().equals(cardId) || stored.rating() != rating) {
                throw new IdempotencyConflictException();
            }
            return stored.toResult();
        }

        CardSource card = card(learner, assignmentId, cardId);
        List<FsrsScheduler.MemoryState> states = jdbc.query(
                """
                SELECT stability_days, difficulty, due_at, last_reviewed_at,
                       state, review_count, lapse_count
                FROM flashcard_memory_states
                WHERE assignment_id = ? AND user_id = ? AND card_id = ? FOR UPDATE
                """,
                (result, row) -> new FsrsScheduler.MemoryState(
                        result.getDouble("stability_days"), result.getDouble("difficulty"),
                        result.getTimestamp("due_at").toInstant(), result.getTimestamp("last_reviewed_at").toInstant(),
                        result.getString("state"), result.getInt("review_count"), result.getInt("lapse_count")
                ),
                assignmentId, learner.userId(), cardId
        );
        FsrsScheduler.MemoryState before = states.stream().findFirst().orElse(null);
        Instant now = clock.instant();
        FsrsScheduler.ScheduledReview scheduled = FsrsScheduler.schedule(before, rating, now);
        jdbc.update(
                """
                INSERT INTO flashcard_memory_states(
                    organization_id, assignment_id, user_id, package_revision_id, card_id,
                    state, stability_days, difficulty, due_at, last_reviewed_at,
                    review_count, lapse_count, algorithm_version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (assignment_id, user_id, card_id) DO UPDATE SET
                    state = EXCLUDED.state, stability_days = EXCLUDED.stability_days,
                    difficulty = EXCLUDED.difficulty, due_at = EXCLUDED.due_at,
                    last_reviewed_at = EXCLUDED.last_reviewed_at,
                    review_count = EXCLUDED.review_count, lapse_count = EXCLUDED.lapse_count,
                    algorithm_version = EXCLUDED.algorithm_version, updated_at = EXCLUDED.updated_at
                """,
                learner.organizationId(), assignmentId, learner.userId(), card.revisionId(), cardId,
                scheduled.state(), scheduled.stabilityDays(), scheduled.difficulty(),
                Timestamp.from(scheduled.dueAt()), Timestamp.from(now), scheduled.reviewCount(),
                scheduled.lapseCount(), FsrsScheduler.VERSION, Timestamp.from(now), Timestamp.from(now)
        );
        UUID reviewId = UuidV7Generator.generate();
        jdbc.update(
                """
                INSERT INTO flashcard_review_log(
                    id, organization_id, assignment_id, user_id, card_id, idempotency_key,
                    rating, reviewed_at, previous_due_at, next_due_at, scheduled_seconds,
                    elapsed_days, retrievability, stability_before, stability_after,
                    difficulty_before, difficulty_after, state_before, state_after,
                    review_count_after, lapse_count_after, algorithm_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                reviewId, learner.organizationId(), assignmentId, learner.userId(), cardId, idempotencyKey,
                rating.name(), Timestamp.from(now), before == null ? null : Timestamp.from(before.dueAt()),
                Timestamp.from(scheduled.dueAt()), scheduled.scheduledSeconds(), scheduled.elapsedDays(),
                scheduled.retrievability(), before == null ? null : before.stabilityDays(),
                scheduled.stabilityDays(), before == null ? null : before.difficulty(), scheduled.difficulty(),
                before == null ? null : before.state(), scheduled.state(), scheduled.reviewCount(),
                scheduled.lapseCount(), FsrsScheduler.VERSION
        );
        jdbc.update(
                """
                UPDATE learner_progress
                SET status = CASE WHEN status = 'ASSIGNED' THEN 'STARTED' ELSE status END,
                    started_at = COALESCE(started_at, ?), progress_percent = GREATEST(progress_percent, 1),
                    updated_at = ?
                WHERE organization_id = ? AND assignment_id = ? AND user_id = ?
                """,
                Timestamp.from(now), Timestamp.from(now), learner.organizationId(), assignmentId, learner.userId()
        );
        return jdbc.query(
                """
                SELECT id, card_id, rating, next_due_at, stability_after, difficulty_after,
                       state_after, review_count_after, lapse_count_after, reviewed_at
                FROM flashcard_review_log WHERE id = ?
                """,
                (result, row) -> new StoredReview(
                        result.getObject("id", UUID.class), result.getString("card_id"),
                        FsrsScheduler.Rating.valueOf(result.getString("rating")),
                        result.getTimestamp("next_due_at").toInstant(), result.getDouble("stability_after"),
                        result.getDouble("difficulty_after"), result.getString("state_after"),
                        result.getInt("review_count_after"), result.getInt("lapse_count_after"),
                        result.getTimestamp("reviewed_at").toInstant()
                ),
                reviewId
        ).getFirst().toResult();
    }

    private List<DueCard> allCards(CurrentActor learner) {
        Instant now = clock.instant();
        List<AssignmentCards> assignments = jdbc.query(
                """
                SELECT a.id, a.title, a.package_revision_id, pr.content_json::text AS content
                FROM assignment_recipients ar
                JOIN assignments a ON a.id = ar.assignment_id AND a.organization_id = ar.organization_id
                JOIN package_revisions pr ON pr.id = a.package_revision_id AND pr.organization_id = a.organization_id
                WHERE ar.organization_id = ? AND ar.user_id = ? AND a.state = 'PUBLISHED' AND a.available_at <= ?
                ORDER BY a.available_at, a.id
                """,
                (result, row) -> new AssignmentCards(
                        result.getObject("id", UUID.class), result.getString("title"),
                        result.getObject("package_revision_id", UUID.class), mapper.readTree(result.getString("content"))
                ),
                learner.organizationId(), learner.userId(), Timestamp.from(now)
        );
        Map<String, CardMemory> memory = new HashMap<>();
        jdbc.query(
                """
                SELECT assignment_id, card_id, state, due_at, stability_days, difficulty,
                       review_count, lapse_count
                FROM flashcard_memory_states WHERE organization_id = ? AND user_id = ?
                """,
                result -> {
                    UUID assignmentId = result.getObject("assignment_id", UUID.class);
                    String cardId = result.getString("card_id");
                    memory.put(key(assignmentId, cardId), new CardMemory(
                            result.getString("state"), result.getTimestamp("due_at").toInstant(),
                            result.getDouble("stability_days"), result.getDouble("difficulty"),
                            result.getInt("review_count"), result.getInt("lapse_count")
                    ));
                },
                learner.organizationId(), learner.userId()
        );
        List<DueCard> cards = new ArrayList<>();
        for (AssignmentCards assignment : assignments) {
            if (!PrerequisiteAccess.isUnlocked(jdbc, learner, assignment.id())) {
                continue;
            }
            for (JsonNode card : assignment.content().path("flashcards")) {
                String cardId = card.path("id").asText();
                if (cardId.isBlank()) {
                    continue;
                }
                CardMemory state = memory.get(key(assignment.id(), cardId));
                JsonNode source = card.path("source");
                cards.add(new DueCard(
                        assignment.id(), assignment.title(), assignment.revisionId(), cardId,
                        card.path("question").asText(), card.path("answer").asText(),
                        assignment.content().path("video").path("youtubeUrl").asText(),
                        source.path("timestampSeconds").asLong(card.path("timestampSeconds").asLong(0)),
                        source.path("verificationStatus").asText("unverified"),
                        state == null ? "NEW" : state.state(), state == null ? clock.instant() : state.dueAt(),
                        state == null ? 0 : state.stability(), state == null ? 0 : state.difficulty(),
                        state == null ? 0 : state.reviewCount(), state == null ? 0 : state.lapseCount()
                ));
            }
        }
        return cards;
    }

    private CardSource card(CurrentActor learner, UUID assignmentId, String cardId) {
        PrerequisiteAccess.requireUnlocked(jdbc, learner, assignmentId);
        List<CardSource> matches = jdbc.query(
                """
                SELECT a.package_revision_id, pr.content_json::text AS content
                FROM assignment_recipients ar
                JOIN assignments a ON a.id = ar.assignment_id AND a.organization_id = ar.organization_id
                JOIN package_revisions pr ON pr.id = a.package_revision_id AND pr.organization_id = a.organization_id
                WHERE ar.organization_id = ? AND ar.user_id = ? AND a.id = ?
                  AND a.state = 'PUBLISHED' AND a.available_at <= ?
                """,
                (result, row) -> new CardSource(
                        result.getObject("package_revision_id", UUID.class), mapper.readTree(result.getString("content"))
                ),
                learner.organizationId(), learner.userId(), assignmentId, Timestamp.from(clock.instant())
        );
        CardSource source = matches.stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found")
        );
        for (JsonNode candidate : source.content().path("flashcards")) {
            if (cardId.equals(candidate.path("id").asText())) {
                return source;
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flashcard not found");
    }

    private static int streak(Set<LocalDate> reviewDates, LocalDate today) {
        LocalDate cursor = reviewDates.contains(today) ? today : today.minusDays(1);
        int streak = 0;
        while (reviewDates.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    private static String key(UUID assignmentId, String cardId) {
        return assignmentId + "|" + cardId;
    }

    public record DueCard(
            UUID assignmentId, String assignmentTitle, UUID packageRevisionId, String cardId,
            String question, String answer, String youtubeUrl, long timestampSeconds, String verificationStatus,
            String state, Instant dueAt, double stabilityDays, double difficulty,
            int reviewCount, int lapseCount
    ) {
    }

    public record ReviewSummary(
            int totalCards, int dueCards, int masteredCards, int reviewsToday, int currentStreakDays
    ) {
    }

    public record ReviewResult(
            UUID reviewId, String cardId, FsrsScheduler.Rating rating, Instant nextDueAt,
            double stabilityDays, double difficulty, String state,
            int reviewCount, int lapseCount, Instant reviewedAt
    ) {
    }

    private record AssignmentCards(UUID id, String title, UUID revisionId, JsonNode content) {
    }

    private record CardSource(UUID revisionId, JsonNode content) {
    }

    private record CardMemory(
            String state, Instant dueAt, double stability, double difficulty, int reviewCount, int lapseCount
    ) {
    }

    private record StoredReview(
            UUID id, String cardId, FsrsScheduler.Rating rating, Instant nextDueAt,
            double stability, double difficulty, String state, int reviewCount, int lapseCount, Instant reviewedAt
    ) {
        ReviewResult toResult() {
            return new ReviewResult(
                    id, cardId, rating, nextDueAt, stability, difficulty, state,
                    reviewCount, lapseCount, reviewedAt
            );
        }
    }
}
