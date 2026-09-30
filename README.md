# Orderly — E-Commerce Order Service

A Spring Boot backend for placing e-commerce orders, built to demonstrate the
distributed-systems concepts that come up in backend interviews: **idempotent
writes**, **optimistic locking**, and **event-driven decoupling** via Kafka.

## Features

- **Order placement** (`POST /api/orders`) with client-supplied idempotency keys —
  safe to retry, never double-charges, never creates duplicates
- **Stock reservation with optimistic locking** — concurrent orders racing for the
  last item fail fast with `409 Conflict` instead of silently overselling
- **Mock payment gateway** behind a swappable `PaymentService` interface
- **Kafka events** — every paid order publishes an `OrderCreated` event to the
  `orders` topic *after* the DB transaction commits
- **Notification consumer** — a `@KafkaListener` that simulates sending the order
  confirmation email
- **Product catalog** endpoints (list / create products with stock)
- **Consistent error responses** via a global `@RestControllerAdvice`
- **Bean Validation** on all request DTOs; money handled exclusively in `BigDecimal`

## Tech stack

Java 17 · Spring Boot 3.3 · Spring Data JPA · Spring Kafka · Bean Validation ·
PostgreSQL 16 · Kafka 3.8 (KRaft, no Zookeeper) · Lombok · JUnit 5 + Mockito

## Architecture

```
                        +------------------+
                        |   REST client    |
                        +--------+---------+
                                 | POST /api/orders
                                 v
                        +--------+---------+
                        | OrderController  |  201 created / 200 idempotent replay
                        +--------+---------+
                                 |
                                 v
                        +--------+-----------------------------------+
                        | OrderService  (@Transactional)            |
                        |  1. idempotency check                     |
                        |  2. reserve stock (optimistic locking)  --+--> PostgreSQL
                        |  3. persist order + items (PENDING)       |    products
                        |  4. charge mock payment gateway         --+    customer_orders
                        |  5. mark PAID                           |    order_items
                        +--------+-----------------------------------+
                                 | after commit
                                 v
                        +--------+---------+
                        | Kafka topic      |
                        | "orders"         |
                        +--------+---------+
                                 |
                                 v
                        +--------+---------+
                        | NotificationSvc  |  @KafkaListener,
                        | (group: orderly- |  group "orderly-notifications"
                        |  notifications)  |  logs "Sending confirmation email…"
                        +------------------+
```

## API reference

| Method | Endpoint              | Description                                              |
|--------|-----------------------|----------------------------------------------------------|
| POST   | `/api/orders`         | Place an order. `201` on create, `200` on idempotent replay |
| GET    | `/api/orders/{id}`    | Fetch a single order                                     |
| GET    | `/api/orders?email=`  | List a customer's orders, newest first                   |
| GET    | `/api/products`       | List all products                                        |
| POST   | `/api/products`       | Create a product with initial stock                      |

Error responses always look like this:

```json
{
  "timestamp": "2026-09-30T05:00:00Z",
  "status": 409,
  "error": "Conflict",
  "message": "Concurrent stock update detected, please retry your order.",
  "path": "/api/orders"
}
```

## Run it

Start the infrastructure (Postgres + Kafka in KRaft mode):

```bash
docker compose up -d
```

Then run the app:

```bash
mvn spring-boot:run
```

The API is on `http://localhost:8084`. Run the tests with `mvn test`.

## Try it

```bash
# 1. Seed a product
curl -X POST localhost:8084/api/products \
  -H 'Content-Type: application/json' \
  -d '{"name":"Mechanical Keyboard","price":129.99,"stock":50}'

# 2. Place an order -> 201 Created
curl -X POST localhost:8084/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"order-abc-123",
       "customerEmail":"jane@example.com",
       "items":[{"productId":1,"quantity":2}]}'

# 3. Retry with the SAME idempotency key -> 200 OK, same order returned,
#    no duplicate row, no second charge (watch the logs: no new payment line)
curl -X POST localhost:8084/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"order-abc-123",
       "customerEmail":"jane@example.com",
       "items":[{"productId":1,"quantity":2}]}'

# 4. Fetch the order
curl localhost:8084/api/orders/1

# 5. Ordering more than is in stock -> 422
curl -X POST localhost:8084/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"order-xyz-999",
       "customerEmail":"jane@example.com",
       "items":[{"productId":1,"quantity":9999}]}'
```

Watch the application logs after step 2 — you'll see the mock payment approval
followed by `Sending confirmation email to jane@example.com for order 1` from
the Kafka consumer.

## Design decisions & trade-offs

**Idempotency keys vs. blind retries.** Networks fail after the server has
already done the work, so clients *will* retry `POST /api/orders`. The
client-generated `idempotencyKey` (unique column) makes retries safe: a replay
returns the original order with `200` instead of creating a duplicate or
charging twice. The application-level "check-then-insert" has a race, so the
unique constraint is the real backstop — on a constraint violation the service
re-reads and returns the winning order.

**Optimistic vs. pessimistic locking.** Stock decrements use `@Version`
optimistic locking rather than `SELECT … FOR UPDATE`. Contention on a single
product row is rare, so we avoid holding a DB row lock for the duration of a
request (which would serialize all orders for hot products and risk lock
timeouts under load). The trade-off: on a genuine race the loser gets a `409`
and must retry, instead of transparently waiting. For this workload — mostly
reads, occasional conflicting writes — that is the right call.

**Events decouple notifications.** The order write path never calls the
notification code directly; it publishes `OrderCreated` to Kafka. That means
email sending can be slow, retried, or scaled independently, and new consumers
(analytics, fulfilment, fraud checks) can subscribe without touching order
logic. The event is published *after* the DB transaction commits, so a rolled-
back order can never emit a phantom event.

**Next step: the transactional outbox.** After-commit publishing (used here)
still has a tiny window — commit succeeds, then the process crashes before the
`send()`. The production hardening is the outbox pattern: write the event to an
`outbox` table in the *same* DB transaction, then have a relay publish it to
Kafka and mark it sent. Same atomicity guarantee, no distributed transaction.
