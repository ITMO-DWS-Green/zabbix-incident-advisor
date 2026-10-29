package ru.itmo.advisor.domain.model;

/**
 * Объяснение для человека.
 *
 * @param summary  1–3 предложения — уходит комментарием к проблеме в Zabbix
 * @param details  подробности в Markdown — для карточки в интерфейсе
 * @param generatedBy кто написал: {@code template} или имя модели LLM
 */
public record Explanation(String summary, String details, String generatedBy) {
}
