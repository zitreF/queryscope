package me.fertiz.queryscope.demo.customer;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;
    private final CustomerCardService customerCardService;
    private final CustomerDashboardService customerDashboardService;

    public CustomerController(CustomerService customerService, CustomerCardService customerCardService,
                              CustomerDashboardService customerDashboardService) {
        this.customerService = customerService;
        this.customerCardService = customerCardService;
        this.customerDashboardService = customerDashboardService;
    }

    @GetMapping("/naive")
    public List<CustomerSummary> naive() {
        return customerService.summariesNaive();
    }

    @GetMapping("/optimized")
    public List<CustomerSummary> optimized() {
        return customerService.summariesOptimized();
    }

    @GetMapping("/{id}/card")
    public CustomerCard card(@PathVariable long id) {
        return customerCardService.card(id);
    }

    @GetMapping("/{id}/dashboard")
    public CustomerDashboard dashboard(@PathVariable long id) {
        return customerDashboardService.dashboard(id);
    }
}
