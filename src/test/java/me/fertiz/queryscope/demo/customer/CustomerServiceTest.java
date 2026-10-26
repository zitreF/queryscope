package me.fertiz.queryscope.demo.customer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class CustomerServiceTest {

    @Autowired
    private CustomerService customerService;

    @Test
    void optimizedQueryReturnsSameSummariesAsNaiveOne() {
        List<CustomerSummary> naive = customerService.summariesNaive();

        assertEquals(40, naive.size());
        assertEquals(naive, customerService.summariesOptimized());
    }
}
