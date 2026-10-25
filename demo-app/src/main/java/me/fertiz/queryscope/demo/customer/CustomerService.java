package me.fertiz.queryscope.demo.customer;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerRepository customers;

    public CustomerService(CustomerRepository customers) {
        this.customers = customers;
    }

    public List<CustomerSummary> summariesNaive() {
        return customers.findAll(Sort.by("id")).stream()
                .map(CustomerSummary::of)
                .toList();
    }

    public List<CustomerSummary> summariesOptimized() {
        return customers.findAllWithOrders().stream()
                .map(CustomerSummary::of)
                .toList();
    }
}
