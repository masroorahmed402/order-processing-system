package com.example.orders.order.api;

import com.example.orders.order.application.OrderPlacementService;
import com.example.orders.order.application.OrderQueryService;
import com.example.orders.order.application.PlacementResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
public class OrderController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final OrderPlacementService placement;
    private final OrderQueryService queries;

    public OrderController(OrderPlacementService placement, OrderQueryService queries) {
        this.placement = placement;
        this.queries = queries;
    }

    /** 201 for a new order, 200 when replaying a request already seen with this Idempotency-Key. */
    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        PlacementResult result = placement.place(idempotencyKey, request.toCommand());
        OrderResponse body = OrderResponse.from(result.order());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .location(URI.create("/orders/" + body.orderId()))
                .body(body);
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(@PathVariable UUID orderId) {
        return OrderResponse.from(queries.get(orderId));
    }
}
