package me.fertiz.queryscope.demo.report;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RevenueSheetService {

    private static final LocalDate LAST_DAY = LocalDate.of(2026, 10, 1);

    private final JdbcClient jdbc;

    public RevenueSheetService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String dailyRevenue(int days) {
        List<OrderRevenue> orders = jdbc.sql("""
                        select c.name as customer, o.placed_at, o.total
                        from orders o
                        join customers c on c.id = o.customer_id
                        """)
                .query(OrderRevenue.class)
                .list();
        Map<String, BigDecimal> revenue = new HashMap<>();
        for (OrderRevenue order : orders) {
            revenue.merge(order.customer() + ";" + order.placedAt(), order.total(), BigDecimal::add);
        }
        List<String> customers = orders.stream().map(OrderRevenue::customer).distinct().sorted().toList();
        String sheet = "day;customer;revenue\n";
        for (int offset = days - 1; offset >= 0; offset--) {
            LocalDate day = LAST_DAY.minusDays(offset);
            for (String customer : customers) {
                sheet += day + ";" + customer + ";" + revenue.getOrDefault(customer + ";" + day, BigDecimal.ZERO) + "\n";
            }
        }
        return sheet;
    }
}
