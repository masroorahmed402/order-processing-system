package com.example.orders.order;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OrderServiceApplication {

    /** Config lives in order-service.yml so all three services can share one test classpath. */
    public static final String CONFIG_NAME = "spring.config.name=order-service";

    public static void main(String[] args) {
        new SpringApplicationBuilder(OrderServiceApplication.class).properties(CONFIG_NAME).run(args);
    }
}
