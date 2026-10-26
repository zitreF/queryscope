package me.fertiz.queryscope.demo.customer;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping("/naive")
    public List<CustomerSummary> naive() {
        return customerService.summariesNaive();
    }

    @GetMapping("/optimized")
    public List<CustomerSummary> optimized() {
        return customerService.summariesOptimized();
    }
}
