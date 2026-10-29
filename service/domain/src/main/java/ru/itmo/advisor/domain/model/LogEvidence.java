package ru.itmo.advisor.domain.model;

import java.time.Instant;

/**
 * Характерная запись лога в окне инцидента.
 *
 * @param pattern  обобщённый вид строки (числа и id заменены), чтобы похожие строки считались вместе
 * @param example  одна настоящая строка этого вида
 * @param count    сколько таких строк в окне
 * @param newInWindow строк такого вида не было до инцидента
 */
public record LogEvidence(String host, String itemId, String pattern, String example, int count,
                          Instant firstSeen, boolean newInWindow) {
}
