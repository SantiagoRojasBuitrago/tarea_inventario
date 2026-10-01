package com.store.inventory;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class DefaultInventoryService implements InventoryService {

    private final Clock clock;
    private final StockAlertListener alertListener;
    private final ConcurrentHashMap<String, Product> products = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, InternalReservation> reservations = new ConcurrentHashMap<>();

    DefaultInventoryService(Clock clock, StockAlertListener alertListener) {
        this.clock = Objects.requireNonNull(clock);
        this.alertListener = Objects.requireNonNull(alertListener);
    }

    @Override
    public void registerProduct(String sku, ProductCategory category) {
        if (sku == null || sku.isBlank() || category == null) {
            throw new IllegalArgumentException("SKU and category must not be null or blank");
        }
        products.compute(sku, (key, existing) -> {
            if (existing == null) {
                return new Product(sku, category);
            }
            existing.setCategory(category);
            return existing;
        });
    }

    @Override
    public void addStock(String sku, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
        Product product = products.get(sku);
        if (product == null) {
            throw new IllegalArgumentException("Product not registered: " + sku);
        }
        product.addStock(quantity);
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        if (orderId == null || orderId.isBlank() || sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("orderId and SKU must not be null or blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }

        Product product = products.get(sku);
        if (product == null) {
            throw new InsufficientStockException(sku, quantity, 0);
        }

        synchronized (product) {
            InternalReservation existing = reservations.get(orderId);
            if (existing != null) {
                Instant now = clock.instant();
                if (existing.isActive(now)) {
                    if (existing.sku().equals(sku) && existing.quantity() == quantity) {
                        return existing.toApi();
                    }
                    throw new IllegalStateException("Order already reserved with different parameters");
                }
                if (existing.status() == InternalReservation.Status.CONFIRMED) {
                    throw new IllegalStateException("Order already confirmed");
                }
                throw new IllegalStateException("Reservation expired");
            }

            InternalReservation created = product.reserve(orderId, quantity, clock, alertListener);
            reservations.put(orderId, created);
            return created.toApi();
        }
    }

    @Override
    public void confirm(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalStateException("orderId must not be null or blank");
        }

        InternalReservation reservation = reservations.get(orderId);
        if (reservation == null) {
            throw new IllegalStateException("No reservation found for order: " + orderId);
        }

        Product product = products.get(reservation.sku());
        if (product == null) {
            throw new IllegalStateException("Product not found: " + reservation.sku());
        }

        synchronized (product) {
            product.confirmSold(reservation, clock.instant());
        }
    }

    @Override
    public int available(String sku) {
        if (sku == null) {
            return 0;
        }
        Product product = products.get(sku);
        if (product == null) {
            return 0;
        }
        return product.available(clock.instant());
    }
}
