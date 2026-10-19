package com.example.orders.inventory;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class InventoryServiceApplication {

    /** Config lives in inventory-service.yml so all three services can share one test classpath. */
    public static final String CONFIG_NAME = "spring.config.name=inventory-service";

    public static void main(String[] args) {
        new SpringApplicationBuilder(InventoryServiceApplication.class).properties(CONFIG_NAME).run(args);
    }
}
