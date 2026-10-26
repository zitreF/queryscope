package me.fertiz.queryscope.demo.customer;

import java.math.BigDecimal;
import java.util.List;

public record CustomerSummary(long id, String name, int orders, BigDecimal totalSpent) {

    public static CustomerSummary of(Customer customer) {
        List<CustomerOrder> orders = customer.getOrders();
        BigDecimal totalSpent = orders.stream()
                .map(CustomerOrder::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CustomerSummary(customer.getId(), customer.getName(), orders.size(), totalSpent);
    }
}
