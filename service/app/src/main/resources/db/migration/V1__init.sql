-- Инциденты: проблемы Zabbix, которые разбирает расширение
CREATE TABLE incident (
    id               UUID PRIMARY KEY,
    problem_event_id BIGINT      NOT NULL UNIQUE,
    trigger_name     TEXT        NOT NULL,
    severity         INT         NOT NULL,
    hosts            JSONB       NOT NULL DEFAULT '[]',
    tags             JSONB       NOT NULL DEFAULT '[]',
    started_at       TIMESTAMPTZ NOT NULL,
    resolved_at      TIMESTAMPTZ,
    status           TEXT        NOT NULL,
    analysis_status  TEXT        NOT NULL,
    analysis         JSONB,
    analysis_error   TEXT,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX incident_started_at ON incident (started_at DESC);
CREATE INDEX incident_open ON incident (status) WHERE status = 'OPEN';

-- Оценка разбора инженером (идея 13)
CREATE TABLE feedback (
    id            BIGSERIAL PRIMARY KEY,
    incident_id   UUID        NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    verdict       TEXT        NOT NULL,
    actual_source TEXT,
    comment       TEXT,
    author        TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX feedback_incident ON feedback (incident_id);

-- Разметка сбоев от test-server (идея 7)
CREATE TABLE fault_label (
    fault_id    TEXT PRIMARY KEY,
    scenario_id TEXT        NOT NULL,
    type        TEXT        NOT NULL,
    source      TEXT        NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL,
    ended_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX fault_label_scenario ON fault_label (scenario_id);

-- Журнал вызовов LLM (идея 4): что ушло в модель — уже замаскированное
CREATE TABLE llm_audit (
    id          BIGSERIAL PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL,
    purpose     TEXT        NOT NULL,
    model       TEXT,
    request     TEXT        NOT NULL,
    response    TEXT,
    duration_ms BIGINT      NOT NULL,
    error       TEXT
);
