CREATE TABLE retention_settings (
    singleton  BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    incidents  TEXT NOT NULL,
    llm_audit  TEXT NOT NULL
);
