package me.fertiz.queryscope.demo.report;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AuditReportService {

    private final JdbcClient jdbc;

    public AuditReportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CustomerActivity> mostActiveCustomers(int limit) {
        return jdbc.sql("""
                        select c.name as customer, count(*) as events
                        from audit_events e
                        join customers c on c.email = e.actor_email
                        group by c.name
                        order by events desc
                        limit ?
                        """)
                .param(limit)
                .query(CustomerActivity.class)
                .list();
    }

    public List<AuditEvent> recentReturns(int limit) {
        return jdbc.sql("""
                        select id, occurred_at, message
                        from audit_events
                        where type = 'RETURN'
                        order by occurred_at desc
                        """)
                .query(AuditEvent.class)
                .list()
                .stream()
                .limit(limit)
                .toList();
    }

    public int recordVisits(String email, int count) {
        long nextId = jdbc.sql("select coalesce(max(id), 0) + 1 from audit_events")
                .query(Long.class)
                .single();
        for (int index = 0; index < count; index++) {
            jdbc.sql("insert into audit_events (id, occurred_at, type, actor_email, message) values (?, ?, ?, ?, ?)")
                    .params(nextId + index, LocalDateTime.now(), "VISIT", email, "Opened the shop")
                    .update();
        }
        return count;
    }
}
