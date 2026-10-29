package ru.itmo.advisor.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * Сервис, который может быть первопричиной. Список подозреваемых отсортирован: первый — самый вероятный.
 *
 * @param reasons почему он под подозрением — короткие фразы для объяснения
 */
public record Suspect(String host, double score, Instant firstDeviationAt, List<String> reasons) {

    public Suspect {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
