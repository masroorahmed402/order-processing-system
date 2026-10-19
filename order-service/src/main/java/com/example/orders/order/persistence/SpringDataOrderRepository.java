package com.example.orders.order.persistence;

import com.example.orders.order.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SpringDataOrderRepository extends JpaRepository<Order, UUID> {
}
