package com.example.orders.order.persistence;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class JpaOrderRepository implements OrderRepository {

    private final SpringDataOrderRepository jpa;

    JpaOrderRepository(SpringDataOrderRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Order> findById(UUID id) {
        return jpa.findById(id);
    }

    @Override
    public Order save(Order order) {
        return jpa.save(order);
    }

    @Override
    public Order insert(Order order) {
        return jpa.saveAndFlush(order);
    }
}
