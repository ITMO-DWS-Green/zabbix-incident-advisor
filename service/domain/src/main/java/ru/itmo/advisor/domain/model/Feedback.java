package ru.itmo.advisor.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Оценка разбора инженером.
 *
 * @param actualSource настоящий первоисточник, если разбор ошибся
 */
public record Feedback(UUID incidentId, Verdict verdict, String actualSource, String comment, String author,
                       Instant createdAt) {

    public enum Verdict { CORRECT, WRONG }
}
