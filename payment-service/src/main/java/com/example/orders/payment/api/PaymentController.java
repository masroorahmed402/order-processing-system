package com.example.orders.payment.api;

import com.example.orders.payment.domain.PaymentRecord;
import com.example.orders.payment.domain.PaymentRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Read-only endpoint for operators. Services never call each other over HTTP. */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentRepository payments;

    public PaymentController(PaymentRepository payments) {
        this.payments = payments;
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<PaymentRecord> get(@PathVariable UUID orderId) {
        return ResponseEntity.of(payments.find(orderId));
    }
}
