package com.store.inventory;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.StockAlertListener;
import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class Product {

    private static final int LOW_STOCK_THRESHOLD = 5;

    private final String sku;
    private volatile ProductCategory category;
    private int stock = 0;
    private final Map<String, InternalReservation> activeReservations = new LinkedHashMap<>();
    private int activeReservedUnits = 0;
    private boolean alertSent = false;

    Product(String sku, ProductCategory category) {
        this.sku = Objects.requireNonNull(sku);
        this.category = Objects.requireNonNull(category);
    }

    String sku() {
        return sku;
    }

    ProductCategory category() {
        return category;
    }

    void setCategory(ProductCategory category) {
        this.category = Objects.requireNonNull(category);
    }

    synchronized void addStock(int quantity) {
        this.stock += quantity;
        this.alertSent = false;
    }

    synchronized InternalReservation reserve(String orderId, int quantity, Clock clock, StockAlertListener alertListener) {
        Instant now = clock.instant();
        purgeExpired(now);

        CategoryPolicy policy = CategoryPolicy.of(category);
        if (policy.maxUnitsPerOrder() != null && quantity > policy.maxUnitsPerOrder()) {
            throw new OrderLimitExceededException(sku, quantity, policy.maxUnitsPerOrder());
        }

        int availableUnits = stock - activeReservedUnits;
        if (quantity > availableUnits) {
            throw new InsufficientStockException(sku, quantity, availableUnits);
        }

        Instant expiresAt = now.plus(policy.ttl());
        InternalReservation reservation = new InternalReservation(orderId, sku, quantity, expiresAt);

        activeReservations.put(orderId, reservation);
        activeReservedUnits += quantity;

        checkLowStockAlert(alertListener, stock - activeReservedUnits);

        return reservation;
    }

    synchronized void confirmSold(InternalReservation reservation, Instant now) {
        if (!reservation.isActive(now)) {
            throw new IllegalStateException("Reservation is not active: " + reservation.orderId());
        }
        activeReservations.remove(reservation.orderId());
        activeReservedUnits -= reservation.quantity();
        stock -= reservation.quantity();
        reservation.confirm();
    }

    synchronized int available(Instant now) {
        purgeExpired(now);
        return Math.max(0, stock - activeReservedUnits);
    }

    private void purgeExpired(Instant now) {
        Iterator<Map.Entry<String, InternalReservation>> it = activeReservations.entrySet().iterator();
        while (it.hasNext()) {
            InternalReservation res = it.next().getValue();
            if (res.isExpired(now)) {
                it.remove();
                activeReservedUnits -= res.quantity();
            }
        }
        if (stock - activeReservedUnits > LOW_STOCK_THRESHOLD) {
            alertSent = false;
        }
    }

    private void checkLowStockAlert(StockAlertListener alertListener, int availableUnits) {
        if (availableUnits <= LOW_STOCK_THRESHOLD) {
            if (!alertSent) {
                alertSent = true;
                if (alertListener != null) {
                    alertListener.onLowStock(sku, availableUnits);
                }
            }
        } else {
            alertSent = false;
        }
    }
}
