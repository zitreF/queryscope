package me.fertiz.queryscope.demo.report;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final AuditReportService auditReportService;

    public ReportController(AuditReportService auditReportService) {
        this.auditReportService = auditReportService;
    }

    @GetMapping("/customer-activity")
    public List<CustomerActivity> customerActivity(@RequestParam(defaultValue = "10") int limit) {
        return auditReportService.mostActiveCustomers(limit);
    }
}
