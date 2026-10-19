package com.example.orders.payment.gateway;

/**
 * Port to a card processor. Implementations must honour {@link ChargeRequest#idempotencyKey()}:
 * repeating a request with the same key returns the original result and never charges twice
 * (the way Stripe or Adyen idempotency keys work).
 */
public interface PaymentGateway {

    /**
     * @return approved or declined; a decline is final
     * @throws TransientPaymentException when the processor could not be reached or timed out;
     *                                   nothing was charged and the call may be retried
     */
    ChargeResult charge(ChargeRequest request);
}
