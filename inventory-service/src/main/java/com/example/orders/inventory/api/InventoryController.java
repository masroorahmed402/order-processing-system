package com.example.orders.inventory.api;

import com.example.orders.inventory.domain.InventoryRepository;
import com.example.orders.inventory.domain.Reservation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Read-only endpoints for operators. Services never call each other over HTTP. */
@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryRepository inventory;

    public InventoryController(InventoryRepository inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/{sku}")
    public ResponseEntity<Map<String, Object>> stock(@PathVariable String sku) {
        var stock = inventory.stockOf(sku);
        return stock.isPresent()
                ? ResponseEntity.ok(Map.of("sku", sku, "stock", stock.getAsInt()))
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/reservations/{orderId}")
    public ResponseEntity<Reservation> reservation(@PathVariable UUID orderId) {
        return ResponseEntity.of(inventory.findReservation(orderId));
    }
}
