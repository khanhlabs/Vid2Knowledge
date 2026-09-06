package com.vid2knowledge.delivery;

import java.time.Duration;
import java.time.Instant;

/** FSRS-6 default-parameter scheduler using the public DSR formulas. */
public final class FsrsScheduler {
    public static final String VERSION = "FSRS-6-default-2026-07";
    private static final double[] W = {
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194,
            0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629,
            1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542
    };
    private static final double SECONDS_PER_DAY = 86_400.0;
    private static final double REQUESTED_RETENTION = 0.9;
    private static final long MAX_INTERVAL_SECONDS = 36_500L * 86_400L;

    private FsrsScheduler() {
    }

    public static ScheduledReview schedule(MemoryState current, Rating rating, Instant now) {
        int grade = rating.grade;
        if (current == null) {
            double stability = W[grade - 1];
            double difficulty = initialDifficulty(grade);
            long seconds = initialInterval(rating, stability);
            return new ScheduledReview(
                    stability, difficulty, now.plusSeconds(seconds),
                    grade == 1 ? "LEARNING" : grade == 2 ? "LEARNING" : "REVIEW",
                    1, grade == 1 ? 1 : 0, 0, 1, seconds
            );
        }

        double elapsedDays = Math.max(0, Duration.between(current.lastReviewedAt(), now).toSeconds() / SECONDS_PER_DAY);
        double retrievability = forgettingCurve(elapsedDays, current.stabilityDays());
        double difficulty = nextDifficulty(current.difficulty(), grade);
        boolean sameDay = elapsedDays < 1;
        double stability;
        if (sameDay) {
            double increase = Math.exp(W[17] * (grade - 3 + W[18]))
                    * Math.pow(current.stabilityDays(), -W[19]);
            if (grade >= 2) {
                increase = Math.max(1, increase);
            }
            stability = current.stabilityDays() * increase;
        } else if (grade == 1) {
            stability = W[11] * Math.pow(difficulty, -W[12])
                    * (Math.pow(current.stabilityDays() + 1, W[13]) - 1)
                    * Math.exp(W[14] * (1 - retrievability));
        } else {
            double hardPenalty = grade == 2 ? W[15] : 1;
            double easyBonus = grade == 4 ? W[16] : 1;
            stability = current.stabilityDays() * (
                    Math.exp(W[8]) * (11 - difficulty)
                            * Math.pow(current.stabilityDays(), -W[9])
                            * (Math.exp(W[10] * (1 - retrievability)) - 1)
                            * hardPenalty * easyBonus + 1
            );
        }
        stability = clamp(stability, 0.01, 36_500);
        long seconds = nextInterval(rating, sameDay, stability);
        return new ScheduledReview(
                stability, difficulty, now.plusSeconds(seconds), grade == 1 ? "RELEARNING" : "REVIEW",
                current.reviewCount() + 1, current.lapseCount() + (grade == 1 ? 1 : 0),
                elapsedDays, retrievability, seconds
        );
    }

    public static double forgettingCurve(double elapsedDays, double stabilityDays) {
        double factor = Math.pow(0.9, -1 / W[20]) - 1;
        return clamp(Math.pow(1 + factor * elapsedDays / stabilityDays, -W[20]), 0, 1);
    }

    private static double initialDifficulty(int grade) {
        return clamp(W[4] - Math.exp(W[5] * (grade - 1)) + 1, 1, 10);
    }

    private static double nextDifficulty(double current, int grade) {
        double delta = -W[6] * (grade - 3);
        double damped = current + delta * (10 - current) / 9;
        double meanReverted = W[7] * initialDifficulty(4) + (1 - W[7]) * damped;
        return clamp(meanReverted, 1, 10);
    }

    private static long initialInterval(Rating rating, double stability) {
        return switch (rating) {
            case AGAIN -> 60;
            case HARD -> 10 * 60;
            case GOOD, EASY -> intervalForStability(stability);
        };
    }

    private static long nextInterval(Rating rating, boolean sameDay, double stability) {
        if (sameDay && rating == Rating.AGAIN) {
            return 10 * 60;
        }
        if (sameDay && rating == Rating.HARD) {
            return 30 * 60;
        }
        return intervalForStability(stability);
    }

    private static long intervalForStability(double stability) {
        double factor = Math.pow(0.9, -1 / W[20]) - 1;
        double days = stability / factor * (Math.pow(REQUESTED_RETENTION, -1 / W[20]) - 1);
        return Math.max(86_400, Math.min(MAX_INTERVAL_SECONDS, Math.round(days * SECONDS_PER_DAY)));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public enum Rating {
        AGAIN(1), HARD(2), GOOD(3), EASY(4);

        private final int grade;

        Rating(int grade) {
            this.grade = grade;
        }
    }

    public record MemoryState(
            double stabilityDays, double difficulty, Instant dueAt, Instant lastReviewedAt,
            String state, int reviewCount, int lapseCount
    ) {
    }

    public record ScheduledReview(
            double stabilityDays, double difficulty, Instant dueAt, String state,
            int reviewCount, int lapseCount, double elapsedDays, double retrievability,
            long scheduledSeconds
    ) {
    }
}
