package com.example.orders.payment.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Knobs for {@link SimulatedPaymentGateway}. Mutable on purpose: tests and chaos experiments can
 * raise {@code transientFailureRate} on a running service.
 */
@ConfigurationProperties(prefix = "payment.gateway")
public class PaymentGatewayProperties {

    /** Charges above this amount are declined. */
    private BigDecimal declineAbove = new BigDecimal("5000.00");

    /** Probability (0.0 - 1.0) that a charge attempt fails with a transient error. */
    private volatile double transientFailureRate = 0.0;

    public BigDecimal getDeclineAbove() {
        return declineAbove;
    }

    public void setDeclineAbove(BigDecimal declineAbove) {
        this.declineAbove = declineAbove;
    }

    public double getTransientFailureRate() {
        return transientFailureRate;
    }

    public void setTransientFailureRate(double transientFailureRate) {
        if (transientFailureRate < 0.0 || transientFailureRate > 1.0) {
            throw new IllegalArgumentException("transientFailureRate must be between 0 and 1: " + transientFailureRate);
        }
        this.transientFailureRate = transientFailureRate;
    }
}
