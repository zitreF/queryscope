package me.fertiz.queryscope.demo.report;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

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
}
