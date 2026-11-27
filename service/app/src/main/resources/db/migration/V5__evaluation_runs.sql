-- Самодостаточные снимки без внешних ключей: очистка исходных данных не меняет отчёт.
CREATE TABLE evaluation_run (
    id                  UUID PRIMARY KEY,
    created_at          TIMESTAMPTZ NOT NULL,
    window_from         TIMESTAMPTZ NOT NULL,
    window_to           TIMESTAMPTZ NOT NULL,
    schema_version      INTEGER NOT NULL,
    methodology_version TEXT NOT NULL,
    payload             JSONB NOT NULL,
    CHECK (window_from < window_to),
    CHECK (window_to - window_from <= interval '31 days')
);
CREATE INDEX evaluation_run_created_at ON evaluation_run (created_at DESC, id DESC);
