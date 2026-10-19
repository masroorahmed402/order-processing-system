package com.example.orders.payment;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class PaymentServiceApplication {

    /** Config lives in payment-service.yml so all three services can share one test classpath. */
    public static final String CONFIG_NAME = "spring.config.name=payment-service";

    public static void main(String[] args) {
        new SpringApplicationBuilder(PaymentServiceApplication.class).properties(CONFIG_NAME).run(args);
    }
}
