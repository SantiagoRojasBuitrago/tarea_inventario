package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InventoryServiceTest {

    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(Clock.systemUTC(), (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
    }

    @Test
    void reservingReducesAvailableUnits() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 3);
        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void cannotReserveMoreThanAvailable() {
        service.addStock("SKU-1", 2);
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "SKU-1", 3));
    }

    @Test
    void confirmedUnitsStaySold() {
        service.addStock("SKU-1", 5);
        service.reserve("ORDER-1", "SKU-1", 2);
        service.confirm("ORDER-1");
        assertEquals(3, service.available("SKU-1"));
    }

    @Nested
    @DisplayName("Reglas por Categoría y Expiración")
    class CategoryAndExpirationTests {

        private MutableTestClock testClock;
        private InventoryService testService;

        @BeforeEach
        void init() {
            testClock = new MutableTestClock(Instant.parse("2026-10-01T10:00:00Z"));
            testService = Inventory.create(testClock, (sku, available) -> { });
            testService.registerProduct("STANDARD-SKU", ProductCategory.STANDARD);
            testService.registerProduct("PREORDER-SKU", ProductCategory.PRE_ORDER);
            testService.registerProduct("FLASHSALE-SKU", ProductCategory.FLASH_SALE);
        }

        @Test
        @DisplayName("STANDARD expira tras 15 minutos liberando unidades")
        void standardExpiresAfter15Minutes() {
            testService.addStock("STANDARD-SKU", 10);
            Reservation res = testService.reserve("ORD-STD", "STANDARD-SKU", 4);
            assertEquals(6, testService.available("STANDARD-SKU"));
            assertEquals(testClock.instant().plus(Duration.ofMinutes(15)), res.expiresAt());

            testClock.advance(Duration.ofMinutes(14));
            assertEquals(6, testService.available("STANDARD-SKU"));

            testClock.advance(Duration.ofMinutes(1));
            assertEquals(10, testService.available("STANDARD-SKU"));

            assertThrows(IllegalStateException.class, () -> testService.confirm("ORD-STD"));
        }

        @Test
        @DisplayName("FLASH_SALE expira tras 5 minutos liberando unidades")
        void flashSaleExpiresAfter5Minutes() {
            testService.addStock("FLASHSALE-SKU", 5);
            Reservation res = testService.reserve("ORD-FLASH", "FLASHSALE-SKU", 2);
            assertEquals(3, testService.available("FLASHSALE-SKU"));
            assertEquals(testClock.instant().plus(Duration.ofMinutes(5)), res.expiresAt());

            testClock.advance(Duration.ofMinutes(5));
            assertEquals(5, testService.available("FLASHSALE-SKU"));
        }

        @Test
        @DisplayName("PRE_ORDER expira tras 24 horas")
        void preOrderExpiresAfter24Hours() {
            testService.addStock("PREORDER-SKU", 20);
            Reservation res = testService.reserve("ORD-PRE", "PREORDER-SKU", 5);
            assertEquals(15, testService.available("PREORDER-SKU"));
            assertEquals(testClock.instant().plus(Duration.ofHours(24)), res.expiresAt());

            testClock.advance(Duration.ofHours(23));
            assertEquals(15, testService.available("PREORDER-SKU"));

            testClock.advance(Duration.ofHours(1));
            assertEquals(20, testService.available("PREORDER-SKU"));
        }

        @Test
        @DisplayName("FLASH_SALE no permite más de 2 unidades por pedido")
        void flashSaleRejectsOverLimit() {
            testService.addStock("FLASHSALE-SKU", 10);
            testService.reserve("ORD-OK-1", "FLASHSALE-SKU", 2);

            OrderLimitExceededException ex = assertThrows(
                OrderLimitExceededException.class,
                () -> testService.reserve("ORD-FAIL", "FLASHSALE-SKU", 3)
            );
            assertTrue(ex.getMessage().contains("Order limit for FLASHSALE-SKU is 2 units, requested 3"));
        }

        @Test
        @DisplayName("STANDARD y PRE_ORDER no tienen límite de unidades por pedido")
        void standardAndPreOrderHaveNoLimit() {
            testService.addStock("STANDARD-SKU", 50);
            testService.addStock("PREORDER-SKU", 50);

            assertNotNull(testService.reserve("ORD-STD-LARGE", "STANDARD-SKU", 20));
            assertNotNull(testService.reserve("ORD-PRE-LARGE", "PREORDER-SKU", 20));
        }
    }

    @Nested
    @DisplayName("Avisos a Compras (StockAlertListener)")
    class StockAlertTests {

        record AlertEvent(String sku, int available) {}

        @Test
        @DisplayName("Avisa cuando quedan 5 o menos disponibles y no repite mientras no se reabastezca")
        void alertFiresAtOrBelowFiveAndDoesNotRepeatUntilRestocked() {
            List<AlertEvent> alerts = new ArrayList<>();
            StockAlertListener listener = (sku, available) -> alerts.add(new AlertEvent(sku, available));

            InventoryService alertService = Inventory.create(Clock.systemUTC(), listener);
            alertService.registerProduct("ALERT-SKU", ProductCategory.STANDARD);
            alertService.addStock("ALERT-SKU", 10);

            alertService.reserve("ORD-1", "ALERT-SKU", 4);
            assertEquals(6, alertService.available("ALERT-SKU"));
            assertEquals(0, alerts.size());

            alertService.reserve("ORD-2", "ALERT-SKU", 1);
            assertEquals(5, alertService.available("ALERT-SKU"));
            assertEquals(1, alerts.size());
            assertEquals(new AlertEvent("ALERT-SKU", 5), alerts.get(0));

            alertService.reserve("ORD-3", "ALERT-SKU", 2);
            assertEquals(3, alertService.available("ALERT-SKU"));
            assertEquals(1, alerts.size());

            alertService.reserve("ORD-4", "ALERT-SKU", 3);
            assertEquals(0, alertService.available("ALERT-SKU"));
            assertEquals(1, alerts.size());

            alertService.addStock("ALERT-SKU", 10);
            assertEquals(10, alertService.available("ALERT-SKU"));

            alertService.reserve("ORD-5", "ALERT-SKU", 6);
            assertEquals(4, alertService.available("ALERT-SKU"));
            assertEquals(2, alerts.size());
            assertEquals(new AlertEvent("ALERT-SKU", 4), alerts.get(1));
        }

        @Test
        @DisplayName("Aviso se rearma si reservas expiradas devuelven el stock por encima del umbral")
        void alertRearmsIfExpiredReservationsRestoreStockAboveThreshold() {
            List<AlertEvent> alerts = new ArrayList<>();
            MutableTestClock testClock = new MutableTestClock(Instant.parse("2026-10-01T12:00:00Z"));
            InventoryService alertService = Inventory.create(testClock, (sku, avail) -> alerts.add(new AlertEvent(sku, avail)));

            alertService.registerProduct("SKU-RESTORE", ProductCategory.STANDARD);
            alertService.addStock("SKU-RESTORE", 10);

            alertService.reserve("ORD-EXPIRING", "SKU-RESTORE", 6);
            assertEquals(1, alerts.size());
            assertEquals(4, alerts.get(0).available());

            testClock.advance(Duration.ofMinutes(15));
            assertEquals(10, alertService.available("SKU-RESTORE"));

            alertService.reserve("ORD-NEW", "SKU-RESTORE", 6);
            assertEquals(2, alerts.size());
            assertEquals(4, alerts.get(1).available());
        }
    }

    @Nested
    @DisplayName("Idempotencia ante reintentos de la App Móvil")
    class IdempotencyTests {

        @Test
        @DisplayName("Reintento con el mismo orderId retorna la reserva existente sin duplicar descuento de stock")
        void duplicateReserveReturnsSameReservationWithoutDoubleDeducting() {
            service.addStock("SKU-1", 10);

            Reservation first = service.reserve("ORDER-RETRY", "SKU-1", 3);
            assertEquals(7, service.available("SKU-1"));

            Reservation second = service.reserve("ORDER-RETRY", "SKU-1", 3);
            assertEquals(7, service.available("SKU-1"));
            assertEquals(first.orderId(), second.orderId());
            assertEquals(first.sku(), second.sku());
            assertEquals(first.quantity(), second.quantity());
            assertEquals(first.expiresAt(), second.expiresAt());
        }

        @Test
        @DisplayName("Reutilizar orderId con diferente SKU o cantidad es rechazado")
        void reusingOrderIdWithDifferentParamsThrows() {
            service.addStock("SKU-1", 10);
            service.registerProduct("SKU-2", ProductCategory.STANDARD);
            service.addStock("SKU-2", 10);

            service.reserve("ORDER-CONFLICT", "SKU-1", 2);

            assertThrows(IllegalStateException.class, () -> service.reserve("ORDER-CONFLICT", "SKU-1", 3));
            assertThrows(IllegalStateException.class, () -> service.reserve("ORDER-CONFLICT", "SKU-2", 2));
        }

        @Test
        @DisplayName("Confirmar dos veces el mismo pedido lanza excepción")
        void confirmTwiceThrows() {
            service.addStock("SKU-1", 5);
            service.reserve("ORDER-DUP-CONFIRM", "SKU-1", 2);
            service.confirm("ORDER-DUP-CONFIRM");

            assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-DUP-CONFIRM"));
        }
    }

    @Nested
    @DisplayName("Validaciones y Casos Límite")
    class ValidationTests {

        @Test
        @DisplayName("addStock con cantidad no positiva o producto desconocido lanza excepción")
        void addStockValidations() {
            assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", 0));
            assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", -5));
            assertThrows(IllegalArgumentException.class, () -> service.addStock("UNKNOWN-SKU", 5));
        }

        @Test
        @DisplayName("reserve con cantidad no positiva lanza excepción")
        void reserveQuantityValidations() {
            service.addStock("SKU-1", 10);
            assertThrows(IllegalArgumentException.class, () -> service.reserve("ORD-1", "SKU-1", 0));
            assertThrows(IllegalArgumentException.class, () -> service.reserve("ORD-2", "SKU-1", -1));
        }

        @Test
        @DisplayName("reserve sobre producto desconocido lanza InsufficientStockException")
        void reserveUnknownProductThrowsInsufficientStockException() {
            assertThrows(InsufficientStockException.class, () -> service.reserve("ORD-1", "NON-EXISTENT", 2));
        }

        @Test
        @DisplayName("available sobre producto desconocido retorna 0")
        void availableReturnsZeroForUnknownProduct() {
            assertEquals(0, service.available("NON-EXISTENT"));
            assertEquals(0, service.available(null));
        }
    }

    @Nested
    @DisplayName("Concurrencia en Temporada Alta")
    class ConcurrencyTests {

        @Test
        @DisplayName("Cientos de compras simultáneas no provocan sobreventa de unidades")
        void concurrentPurchasesNeverOversell() throws InterruptedException {
            service.addStock("SKU-1", 50);

            int threads = 100;
            ExecutorService executor = Executors.newFixedThreadPool(16);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch endGate = new CountDownLatch(threads);

            AtomicInteger successfulReservations = new AtomicInteger(0);
            AtomicInteger failedReservations = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                final String orderId = "CONCURRENT-ORD-" + i;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        service.reserve(orderId, "SKU-1", 1);
                        successfulReservations.incrementAndGet();
                    } catch (InsufficientStockException e) {
                        failedReservations.incrementAndGet();
                    } catch (Exception e) {
                    } finally {
                        endGate.countDown();
                    }
                });
            }

            startGate.countDown();
            endGate.await();
            executor.shutdown();

            assertEquals(50, successfulReservations.get());
            assertEquals(50, failedReservations.get());
            assertEquals(0, service.available("SKU-1"));
        }
    }

    static final class MutableTestClock extends Clock {
        private Instant currentInstant;
        private final ZoneId zone = ZoneOffset.UTC;

        MutableTestClock(Instant start) {
            this.currentInstant = start;
        }

        void advance(Duration duration) {
            this.currentInstant = this.currentInstant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}
