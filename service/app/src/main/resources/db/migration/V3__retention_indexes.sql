CREATE INDEX incident_retention ON incident (resolved_at) WHERE status = 'RESOLVED';
CREATE INDEX llm_audit_created_at ON llm_audit (created_at);
