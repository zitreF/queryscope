package me.fertiz.queryscope.demo.report;

import java.time.LocalDateTime;

public record AuditEvent(long id, LocalDateTime occurredAt, String message) {
}
