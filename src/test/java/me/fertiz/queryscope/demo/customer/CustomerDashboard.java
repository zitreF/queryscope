package me.fertiz.queryscope.demo.customer;

import java.math.BigDecimal;

public record CustomerDashboard(long id, int orders, BigDecimal spent) {
}
