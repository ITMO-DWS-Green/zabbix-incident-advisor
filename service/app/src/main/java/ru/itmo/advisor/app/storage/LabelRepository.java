package ru.itmo.advisor.app.storage;

import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.itmo.advisor.domain.model.FaultLabel;

@Repository
public class LabelRepository {

    private final JdbcClient jdbc;

    public LabelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns false when the fault ID already belongs to a different label. */
    public boolean save(FaultLabel l) {
        return jdbc.sql("""
                        INSERT INTO fault_label (fault_id, scenario_id, type, source, started_at, ended_at)
                        VALUES (:f, :sc, :t, :s, :st, :en)
                        ON CONFLICT (fault_id) DO UPDATE SET ended_at = EXCLUDED.ended_at
                        WHERE fault_label.scenario_id = EXCLUDED.scenario_id
                          AND fault_label.type = EXCLUDED.type
                          AND fault_label.source = EXCLUDED.source
                          AND fault_label.started_at = EXCLUDED.started_at""")
                .param("f", l.faultId()).param("sc", l.scenarioId()).param("t", l.type()).param("s", l.source())
                .param("st", Timestamp.from(l.start())).param("en", Timestamp.from(l.end()))
                .update() == 1;
    }

    /** {@code scenarioId = null} — все сценарии. */
    public List<FaultLabel> find(String scenarioId) {
        String where = scenarioId == null ? "" : " WHERE scenario_id = :sc";
        var spec = jdbc.sql("SELECT * FROM fault_label" + where + " ORDER BY started_at");
        if (scenarioId != null) {
            spec = spec.param("sc", scenarioId);
        }
        return spec.query((rs, n) -> new FaultLabel(rs.getString("scenario_id"), rs.getString("fault_id"),
                rs.getString("type"), rs.getString("source"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("ended_at").toInstant())).list();
    }

    /** Load the selected label set without truncation. */
    public List<FaultLabel> findByIds(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM fault_label WHERE fault_id IN (:ids) ORDER BY started_at")
                .param("ids", ids)
                .query((rs, n) -> new FaultLabel(rs.getString("scenario_id"), rs.getString("fault_id"),
                        rs.getString("type"), rs.getString("source"), rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("ended_at").toInstant())).list();
    }

    public List<String> scenarios() {
        return jdbc.sql("SELECT DISTINCT scenario_id FROM fault_label ORDER BY scenario_id")
                .query(String.class).list();
    }
}
