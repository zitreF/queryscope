package me.fertiz.queryscope.demo.report;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class AuditReportService {

    private final JdbcClient jdbc;

    public AuditReportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<EventTypeCount> countMatching(String term) {
        return jdbc.sql("""
                        select type, count(*) as events
                        from audit_events
                        where lower(message) like ?
                        group by type
                        order by events desc
                        """)
                .param("%" + term.toLowerCase(Locale.ROOT) + "%")
                .query(EventTypeCount.class)
                .list();
    }
}
