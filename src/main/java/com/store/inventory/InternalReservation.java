package com.store.inventory;

import com.store.inventory.api.Reservation;
import java.time.Instant;
import java.util.Objects;

final class InternalReservation {

    enum Status {
        ACTIVE,
        CONFIRMED
    }

    private final String orderId;
    private final String sku;
    private final int quantity;
    private final Instant expiresAt;
    private volatile Status status = Status.ACTIVE;

    InternalReservation(String orderId, String sku, int quantity, Instant expiresAt) {
        this.orderId = Objects.requireNonNull(orderId);
        this.sku = Objects.requireNonNull(sku);
        this.quantity = quantity;
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    String orderId() {
        return orderId;
    }

    String sku() {
        return sku;
    }

    int quantity() {
        return quantity;
    }

    Instant expiresAt() {
        return expiresAt;
    }

    Status status() {
        return status;
    }

    boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    boolean isActive(Instant now) {
        return status == Status.ACTIVE && !isExpired(now);
    }

    void confirm() {
        this.status = Status.CONFIRMED;
    }

    Reservation toApi() {
        return new Reservation(orderId, sku, quantity, expiresAt);
    }
}
