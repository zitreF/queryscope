package me.fertiz.queryscope.demo.digest;

import me.fertiz.queryscope.demo.customer.CustomerService;
import me.fertiz.queryscope.demo.customer.CustomerSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class DigestJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(DigestJob.class);

    private final CustomerService customerService;

    public DigestJob(CustomerService customerService) {
        this.customerService = customerService;
    }

    @Scheduled(initialDelay = 10, fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void prepareDigest() {
        List<CustomerSummary> summaries = customerService.summariesNaive();
        BigDecimal revenue = summaries.stream()
                .map(CustomerSummary::totalSpent)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        LOGGER.info("Digest ready: {} customers, {} in revenue", summaries.size(), revenue);
    }
}
