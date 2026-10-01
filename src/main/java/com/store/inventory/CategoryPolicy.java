package com.store.inventory;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public record CategoryPolicy(Duration ttl, Integer maxUnitsPerOrder) {

    public CategoryPolicy {
        Objects.requireNonNull(ttl);
        if (maxUnitsPerOrder != null && maxUnitsPerOrder <= 0) {
            throw new IllegalArgumentException("Max units must be positive");
        }
    }

    private static final Map<ProductCategory, CategoryPolicy> POLICIES = new ConcurrentHashMap<>();

    static {
        POLICIES.put(ProductCategory.STANDARD, new CategoryPolicy(Duration.ofMinutes(15), null));
        POLICIES.put(ProductCategory.PRE_ORDER, new CategoryPolicy(Duration.ofHours(24), null));
        POLICIES.put(ProductCategory.FLASH_SALE, new CategoryPolicy(Duration.ofMinutes(5), 2));
    }

    public static CategoryPolicy of(ProductCategory category) {
        if (category == null) {
            throw new IllegalArgumentException("Category cannot be null");
        }
        CategoryPolicy policy = POLICIES.get(category);
        if (policy == null) {
            throw new IllegalArgumentException("No policy for category: " + category);
        }
        return policy;
    }

    public static void registerPolicy(ProductCategory category, CategoryPolicy policy) {
        POLICIES.put(Objects.requireNonNull(category), Objects.requireNonNull(policy));
    }
}
