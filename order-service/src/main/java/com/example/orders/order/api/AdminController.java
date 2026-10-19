package com.example.orders.order.api;

import com.example.orders.order.application.OrderRecoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** On-call tooling. In production this would sit behind admin authentication or an internal ALB. */
@RestController
@RequestMapping("/admin/orders")
public class AdminController {

    private final OrderRecoveryService recovery;

    public AdminController(OrderRecoveryService recovery) {
        this.recovery = recovery;
    }

    /** Resumes a stuck saga by re-emitting ORDER_CREATED (safe: every consumer is idempotent). */
    @PostMapping("/{orderId}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable UUID orderId) {
        boolean resumed = recovery.resume(orderId);
        return resumed
                ? ResponseEntity.accepted().body(Map.of("orderId", orderId, "resumed", true))
                : ResponseEntity.status(409).body(Map.of("orderId", orderId, "resumed", false,
                "detail", "Order is already in a terminal state"));
    }
}
