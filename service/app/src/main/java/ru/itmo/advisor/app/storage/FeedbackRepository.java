package ru.itmo.advisor.app.storage;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.itmo.advisor.domain.model.Feedback;

@Repository
public class FeedbackRepository {

    private final JdbcClient jdbc;

    public FeedbackRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Feedback f) {
        jdbc.sql("INSERT INTO feedback (incident_id, verdict, actual_source, comment, author, created_at) "
                        + "VALUES (:i, :v, :s, :c, :a, :t)")
                .param("i", f.incidentId()).param("v", f.verdict().name()).param("s", f.actualSource())
                .param("c", f.comment()).param("a", f.author()).param("t", Timestamp.from(f.createdAt()))
                .update();
    }

    public List<Feedback> findByIncident(UUID incidentId) {
        return jdbc.sql("SELECT * FROM feedback WHERE incident_id = :i ORDER BY created_at")
                .param("i", incidentId)
                .query((rs, n) -> new Feedback(rs.getObject("incident_id", UUID.class),
                        Feedback.Verdict.valueOf(rs.getString("verdict")), rs.getString("actual_source"),
                        rs.getString("comment"), rs.getString("author"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
