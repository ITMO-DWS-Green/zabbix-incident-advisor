-- Инцидент — группа связанных проблем (#40). Каскад по карте связей даёт один инцидент и один разбор.
-- В incident остаётся «шапка»: problem_event_id — первая проблема, hosts и tags — по всем проблемам,
-- severity — наибольшая, resolved_at — когда закрылась последняя.
CREATE TABLE incident_problem (
    event_id     BIGINT PRIMARY KEY,
    incident_id  UUID        NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    trigger_name TEXT        NOT NULL,
    severity     INT         NOT NULL,
    hosts        JSONB       NOT NULL DEFAULT '[]',
    tags         JSONB       NOT NULL DEFAULT '[]',
    started_at   TIMESTAMPTZ NOT NULL,
    resolved_at  TIMESTAMPTZ
);
CREATE INDEX incident_problem_incident ON incident_problem (incident_id);

-- Прежние инциденты — по одной проблеме
INSERT INTO incident_problem (event_id, incident_id, trigger_name, severity, hosts, tags, started_at, resolved_at)
SELECT problem_event_id, id, trigger_name, severity, hosts, tags, started_at, resolved_at
FROM incident;
