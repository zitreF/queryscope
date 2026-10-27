package me.fertiz.queryscope.demo.report;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OrderRevenue(String customer, LocalDate placedAt, BigDecimal total) {
}
