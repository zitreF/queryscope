package me.fertiz.queryscope.demo.customer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class CustomerCardService {

    private final JdbcClient jdbc;

    public CustomerCardService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public CustomerCard card(long id) {
        long orders = jdbc.sql("select count(*) from orders where customer_id = ?")
                .param(id)
                .query(Long.class)
                .single();
        return new CustomerCard(id, nameOf(id), orders, "Hello, " + firstName(id));
    }

    private String firstName(long id) {
        return nameOf(id).split(" ")[0];
    }

    private String nameOf(long id) {
        return jdbc.sql("select name from customers where id = ?")
                .param(id)
                .query(String.class)
                .single();
    }
}
