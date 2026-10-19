package com.example.orders.payment.gateway;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedPaymentGatewayTest {

    private final PaymentGatewayProperties properties = new PaymentGatewayProperties();

    private static ChargeRequest request(String amount) {
        UUID orderId = UUID.randomUUID();
        return new ChargeRequest(orderId.toString(), orderId, new BigDecimal(amount));
    }

    @Test
    void approvesAmountsUpToTheLimit() {
        SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(properties, () -> 0.99);

        ChargeRequest atLimit = request("5000.00");
        assertThat(gateway.charge(atLimit)).isInstanceOf(ChargeResult.Approved.class);
        assertThat(gateway.chargeCount(atLimit.orderId())).isEqualTo(1);
    }

    @Test
    void declinesAmountsAboveTheLimitWithoutCharging() {
        SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(properties, () -> 0.99);
        ChargeRequest tooMuch = request("5000.01");

        ChargeResult result = gateway.charge(tooMuch);

        assertThat(result).isInstanceOf(ChargeResult.Declined.class);
        assertThat(((ChargeResult.Declined) result).reason()).contains("exceeds limit 5000.00");
        assertThat(gateway.chargeCount(tooMuch.orderId())).isZero();
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameResultAndChargesOnce() {
        SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(properties, () -> 0.99);
        ChargeRequest request = request("10.00");

        ChargeResult first = gateway.charge(request);
        ChargeResult second = gateway.charge(request);

        assertThat(second).isEqualTo(first);
        assertThat(gateway.chargeCount(request.orderId())).isEqualTo(1);
    }

    @Test
    void failsTransientlyAtTheConfiguredRate() {
        properties.setTransientFailureRate(0.3);
        SimulatedPaymentGateway unlucky = new SimulatedPaymentGateway(properties, () -> 0.29);
        SimulatedPaymentGateway lucky = new SimulatedPaymentGateway(properties, () -> 0.30);
        ChargeRequest request = request("10.00");

        assertThatThrownBy(() -> unlucky.charge(request)).isInstanceOf(TransientPaymentException.class);
        assertThat(unlucky.chargeCount(request.orderId())).isZero();
        assertThat(lucky.charge(request)).isInstanceOf(ChargeResult.Approved.class);
    }

    @Test
    void rateOfOneAlwaysFailsAndZeroNeverDoes() {
        properties.setTransientFailureRate(1.0);
        SimulatedPaymentGateway alwaysFails = new SimulatedPaymentGateway(properties);
        for (int i = 0; i < 100; i++) {
            assertThatThrownBy(() -> alwaysFails.charge(request("1.00"))).isInstanceOf(TransientPaymentException.class);
        }
        properties.setTransientFailureRate(0.0);
        for (int i = 0; i < 100; i++) {
            assertThat(alwaysFails.charge(request("1.00"))).isInstanceOf(ChargeResult.Approved.class);
        }
    }

    @Test
    void rejectsInvalidRates() {
        assertThatThrownBy(() -> properties.setTransientFailureRate(1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setTransientFailureRate(-0.1)).isInstanceOf(IllegalArgumentException.class);
    }
}
