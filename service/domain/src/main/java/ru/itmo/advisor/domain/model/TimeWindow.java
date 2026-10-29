package ru.itmo.advisor.domain.model;

import java.time.Duration;
import java.time.Instant;

/** Полуинтервал [from, to). */
public record TimeWindow(Instant from, Instant to) {

    public boolean contains(Instant t) {
        return !t.isBefore(from) && t.isBefore(to);
    }

    public Duration length() {
        return Duration.between(from, to);
    }
}
