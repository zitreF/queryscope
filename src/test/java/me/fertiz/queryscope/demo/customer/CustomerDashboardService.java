package me.fertiz.queryscope.demo.customer;

import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class CustomerDashboardService {

    private final JdbcClient jdbc;
    private final ExecutorService loaders = Executors.newFixedThreadPool(4);

    public CustomerDashboardService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public CustomerDashboard dashboard(long id) {
        List<Long> orderIds = jdbc.sql("select id from orders where customer_id = ? order by id")
                .param(id)
                .query(Long.class)
                .list();
        BigDecimal spent = orderIds.stream()
                .map(orderId -> CompletableFuture.supplyAsync(() -> totalOf(orderId), loaders))
                .toList()
                .stream()
                .map(CompletableFuture::join)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CustomerDashboard(id, orderIds.size(), spent);
    }

    @PreDestroy
    void stopLoaders() {
        loaders.shutdownNow();
    }

    private BigDecimal totalOf(long orderId) {
        return jdbc.sql("select total from orders where id = ?")
                .param(orderId)
                .query(BigDecimal.class)
                .single();
    }
}
