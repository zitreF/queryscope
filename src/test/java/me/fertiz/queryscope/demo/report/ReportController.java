package me.fertiz.queryscope.demo.report;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final AuditReportService auditReportService;
    private final RevenueSheetService revenueSheetService;

    public ReportController(AuditReportService auditReportService, RevenueSheetService revenueSheetService) {
        this.auditReportService = auditReportService;
        this.revenueSheetService = revenueSheetService;
    }

    @GetMapping("/customer-activity")
    public List<CustomerActivity> customerActivity(@RequestParam(defaultValue = "10") int limit) {
        return auditReportService.mostActiveCustomers(limit);
    }

    @GetMapping("/recent-returns")
    public List<AuditEvent> recentReturns(@RequestParam(defaultValue = "20") int limit) {
        return auditReportService.recentReturns(limit);
    }

    @GetMapping(value = "/daily-revenue", produces = "text/csv")
    public String dailyRevenue(@RequestParam(defaultValue = "365") int days) {
        return revenueSheetService.dailyRevenue(days);
    }

    @PostMapping("/visits")
    public int recordVisits(@RequestParam(defaultValue = "customer1@example.com") String email,
                            @RequestParam(defaultValue = "20") int count) {
        return auditReportService.recordVisits(email, count);
    }
}
