# Decisiones de Diseño y Arquitectura (DECISIONS.md)

Este documento detalla los supuestos asumidos, las decisiones técnicas adoptadas, lo que se omitió intencionalmente y las recomendaciones clave para la transición a un entorno de producción distribuido.

---

## 1. Contexto y Enfoque de Arquitectura

El objetivo fue construir una solución **concisa, robusta, altamente mantenible y con la menor cantidad de código posible**, aplicando principios **KISS**, **YAGNI** y **SOLID** sobre **Java 21**:

- **Separación limpia de responsabilidades**:
  - `CategoryPolicy`: Centraliza las reglas de negocio por categoría (TTL y límites de unidades). Hace trivial que Marketing agregue o modifique categorías por temporada sin tocar la lógica central del inventario (Principio Abierto/Cerrado).
  - `Product`: Entidad de dominio thread-safe que gestiona el stock físico, las reservas activas, la purga de expiraciones y el control de avisos de stock bajo.
  - `InternalReservation`: Rastrea el ciclo de vida de la reserva (`ACTIVE`, `CONFIRMED`) y su expiración.
  - `DefaultInventoryService`: Fachada del servicio que orquesta productos, valida entradas y garantiza la idempotencia ante reintentos de la aplicación móvil.
  - `Inventory`: Factoría estática pública respetando el contrato exigido por las pruebas.

- **Concurrencia granular (High Season / Black Friday)**:
  - En lugar de bloquear todo el servicio con sincronizaciones globales, la sincronización se realiza **a nivel de producto (`synchronized (product)`)**.
  - Múltiples clientes comprando productos distintos operan en paralelo sin contención. Las compras concurrentes sobre el mismo SKU se serializan atómicamente, eliminando por completo cualquier riesgo de sobreventa (*race condition*).

---

## 2. Supuestos de Negocio Asumidos

1. **Idempotencia ante reintentos de red de la App Móvil**:
   - *Contexto*: La app móvil envía cada producto como un pedido independiente y lo reenvía automáticamente si la conexión es lenta.
   - *Supuesto*: Si ingresa una petición `reserve` con un `orderId` que ya tiene una reserva activa para el mismo SKU y cantidad, el servicio retorna la reserva existente sin descontar stock adicional. Si se intenta reusar un `orderId` existente con parámetros distintos, confirmado o expirado, se lanza `IllegalStateException`.

2. **Umbral y Rearme de la Alerta a Compras (`StockAlertListener`)**:
   - *Umbral*: Se activa cuando las unidades disponibles quedan en **5 o menos (`<= 5`)**.
   - *Disparo*: Ocurre durante la reserva, en el momento en que las unidades disponibles descienden al umbral.
   - *No repetición*: Mientras el producto no sea reabastecido, las siguientes reservas que sigan disminuyendo el stock (ej. 5 -> 3 -> 1 -> 0) **no vuelven a disparar la alerta**.
   - *Rearme*: La alerta se rearma en dos escenarios:
     1. Cuando compras ingresa nuevas unidades mediante `addStock(sku, quantity)`.
     2. Cuando una reserva no pagada expira y devuelve el stock disponible por encima del umbral (`> 5`).

3. **Expiración Lazy (Perezosa)**:
   - Las reservas se evalúan contra el `Clock` inyectado. Una reserva se considera expirada cuando `now >= expiresAt`.
   - La liberación de unidades no depende de un hilo de sondeo continuo en memoria, sino que se ejecuta en tiempo real (*on-demand*) al calcular disponibilidad o al procesar nuevas reservas. Esto optimiza el uso de CPU y garantiza consistencia inmediata.

4. **Confirmación de Compra**:
   - Al confirmarse el pago (`confirm`), las unidades se descuentan permanentemente del stock físico (`stock -= quantity`) y la reserva pasa a estado `CONFIRMED`.
   - Si se invoca `confirm` sobre una orden inexistente, expirada o ya confirmada, se lanza `IllegalStateException`.

---

## 3. Lo que se dejó fuera deliberadamente

1. **Base de Datos y Transaccionalidad Externa**:
   - El ejercicio indica explícitamente: *"Por ahora los datos pueden vivir en memoria"*. No se introdujeron capas ORM, SQL ni drivers JDBC innecesarios.
2. **Frameworks Pesados (Spring Boot, Quarkus, Guice)**:
   - Se utilizó Java 21 estándar, lo que mantiene el tiempo de ejecución de las pruebas por debajo de **1 segundo** y elimina dependencias transitivas.
3. **Daemon Thread de Limpieza en Background**:
   - Se evitó crear un `ScheduledExecutorService` en segundo plano para limpiar expirados. En pruebas unitarias, los hilos en segundo plano introducen no-determinismo y fugas de recursos (*thread leaks*). La expiración *lazy* cubre el 100% de la funcionalidad de forma determinística.
4. **Implementación de Canales de Comunicación (Email, Slack, SMS)**:
   - Se mantuvo la abstracción en `StockAlertListener`. El servicio de inventario solo notifica el evento; el enrutamiento a múltiples canales es responsabilidad del consumidor del listener.

---

## 4. Cambios recomendados antes de ir a Producción

El README señala: *"El inventario se migrará a una base de datos y el servicio correrá en varias instancias"*. Para ese entorno productivo, los pasos prioritarios son:

### A. Persistencia y Concurrencia Distribuida (Multi-instancia)
La sincronización en memoria de la JVM (`synchronized`) no protege contra condiciones de carrera cuando hay múltiples instancias del servicio tras un balanceador de carga.
- **Opción Relacional (PostgreSQL)**:
  - Usar transacciones ACID con bloqueo pesimista a nivel de fila:
    ```sql
    SELECT stock, reserved FROM products WHERE sku = :sku FOR UPDATE;
    ```
    O actualización atómica condicional:
    ```sql
    UPDATE products
    SET available_stock = available_stock - :qty
    WHERE sku = :sku AND available_stock >= :qty;
    ```
- **Opción Caché Distribuida (Redis)**:
  - Para eventos de venta masiva (*Flash Sales*), ejecutar la lógica de reserva en Redis mediante un **Script Lua atómico** o claves con TTL automático (`SETEX`), logrando latencias de respuesta sub-milisegundo antes de asentar en la base de datos principal.

### B. Idempotencia a Nivel de Almacenamiento
- Crear una tabla `orders_reservations` con una clave primaria o restricción única (`UNIQUE CONSTRAINT`) sobre `order_id`. Esto asegura que reintentos concurrentes que lleguen a instancias distintas sean resueltos de manera atómica por el motor de base de datos.

### C. Alertas Resilientes: Patrón Outbox Transaccional
- Invocar servicios externos (correo, webhooks, colas) dentro de la misma transacción de reserva introduce riesgos de latencia y fallo en cascada.
- **Solución**: Registrar el evento de alerta en una tabla `outbox` dentro de la transacción de la reserva. Un proceso asíncrono (o CDC vía Debezium) publicará el mensaje a **Apache Kafka** o **RabbitMQ**, garantizando entrega *at-least-once* hacia los múltiples canales de notificación.

### D. Liberación Proactiva de Reservas Expiradas
- En un sistema distribuido, no se debe depender únicamente de la lectura bajo demanda:
  - Programar un worker distribuido (usando *pg_cron*, *Temporal* o eventos de expiración de Redis *Keyspace Notifications*) para liberar reservas abandonadas y publicar eventos de negocio (`order.reservation_expired`).

### E. Observabilidad y Monitoreo
- Métricas con **Micrometer / Prometheus**:
  - Reservas activas por categoría.
  - Tasa de carritos abandonados (expirados sin pago).
  - Frecuencia y tiempos de espera en bloqueos de stock.
- Trazabilidad distribuida con **OpenTelemetry**:
  - Propagación de `TraceId` desde el pedido móvil para auditar el ciclo de vida completo de la reserva.
