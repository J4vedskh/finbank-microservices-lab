# Resilience

FinBank's payment path should protect customers from duplicate charges, partial
processing, and unclear failure states. This page defines the first resilience
rules before they are implemented in service code.

## Payment Resilience Goals

| Goal | Rule | First implementation target |
| --- | --- | --- |
| Prevent duplicate charges | Require an idempotency key for payment creation. | Implemented at the Payment API boundary |
| Make retries safe | Return the original payment result when the same key is repeated. | Implemented with a non-raw SHA-256 key digest and database uniqueness |
| Keep ledger writes consistent | Process each payment event once per payment id. | Implemented with persisted payment identity and database uniqueness |
| Expose recoverable failures | Distinguish validation, downstream, and retryable failures. | API error model and status field |
| Preserve auditability | Store request key, payment id, event id, and ledger id together. | Payment and transaction persistence |

## Idempotent Payment Flow

```mermaid
sequenceDiagram
    participant Client
    participant Payments as Payment Service
    participant Store as Payment DB
    participant Relay as Outbox Relay
    participant Kafka as Kafka
    participant Ledger as Transaction Service

    Client->>Payments: POST /payments with Idempotency-Key
    Payments->>Store: Find payment by key
    alt Existing key
        Store-->>Payments: Existing payment result
        Payments-->>Client: Return original result
    else New key
        Payments->>Store: Atomically create payment + PENDING outbox event
        Payments-->>Client: Return new payment id
        Relay->>Store: Lock due outbox event
        Relay->>Kafka: Publish stored payment event
        Kafka-->>Relay: Broker acknowledgement
        Relay->>Store: Mark event and payment PUBLISHED
        Kafka-->>Ledger: Deliver event
        Ledger->>Ledger: Ignore duplicate event ids
    end
```

## Retry Boundaries

| Boundary | Current behavior | Remaining work |
| --- | --- | --- |
| Client to Payment Service | Exact retries return the original result through the idempotency key. | Define key expiry and retention. |
| Payment Service to database | The request fails visibly when its atomic payment/outbox transaction fails. | Add bounded transient database retry only after failure classification exists. |
| Payment outbox to Kafka | A scheduled relay waits for acknowledgement, uses capped exponential delays, and stops after five failed sends by default. | Add dead-letter routing and operator-controlled requeue. |
| Transaction Service consumer | Malformed or persistence failures reach the Kafka container; duplicate payment events are safe. | Configure bounded backoff and dead-letter routing. |

## Transaction Event Idempotency

The transaction service stores the originating payment id with every new ledger
entry. An exact Kafka redelivery returns the existing entry without writing a
second row. Reusing the same payment id with different account or amount data
fails as a conflict. A database unique constraint protects concurrent consumers;
the losing writer re-reads and accepts only the matching entry. Kafka retry and
dead-letter policy remain separate planned work. Payment creation and event
consumption share a two-decimal amount contract so an accepted payment cannot
later fail ledger validation because of database rounding.

## Payment Request Idempotency

`POST /payments` requires an opaque, case-sensitive `Idempotency-Key`. The
service stores only its SHA-256 hash, avoiding raw-key disclosure and database
collation differences. This unkeyed digest is not protection for a predictable
key if the database is exposed, so clients should generate high-entropy keys.
An exact retry returns the original payment without a
second insert or Kafka send request. Reusing a key for different account or
amount data returns HTTP `409 Conflict`; a database unique constraint also
protects concurrent requests.

New payment creation atomically commits the payment and one `PENDING` outbox
event. A scheduled relay publishes the stored event key and payload, waits for a
bounded broker acknowledgement, and records `PUBLISHED` or `PENDING_RETRY` state.
This removes the database-success/process-crash gap that existed when the
service sent directly after committing the payment.

Publication remains at least once. A timeout, or a crash after broker
acknowledgement but before the outbox status commit, can cause a duplicate send.
The transaction service's payment-id uniqueness accepts identical redelivery
without a second ledger row. The relay retains all outbox rows; dead-letter
routing, operator-controlled requeue, and retention remain future work.

## Bounded Outbox Retry

The default policy makes five total broker send attempts. After failed attempt
`n`, the next delay is `min(5 seconds × 2^(n-1), 5 minutes)`. Delay timing starts
when the failure is observed, so time spent waiting for the broker does not make
the next attempt immediately due. Both the base delay, cap, and maximum attempts
are configurable.

| Property | Default | Purpose |
| --- | ---: | --- |
| `payment.outbox.retry-delay-ms` | 5000 | First retry delay |
| `payment.outbox.max-retry-delay-ms` | 300000 | Exponential delay cap |
| `payment.outbox.max-attempts` | 5 | Total send attempts, including the initial attempt |
| `payment.outbox.send-timeout-ms` | 5000 | Maximum acknowledgement wait per attempt |

When the final attempt fails, the outbox row receives an exhaustion timestamp,
the payment moves to `PUBLISH_EXHAUSTED`, and the row is excluded from normal
polling. This means the relay exhausted its policy; it does not prove Kafka never
accepted the event because a timeout can be ambiguous. The stored error is only
the exception type so broker messages do not leak secrets. Manual requeue and
dead-letter handling are still required.

## Internal Recovery Boundary

The payment service now has a transactional recovery operation for an exhausted
outbox row. It locks the row, verifies that both the outbox and payment are in
the matching exhaustion state, clears the exhaustion marker, resets the
per-cycle attempt count, and makes the unchanged stored event due again. The
previous safe error type remains available until the next publication outcome.

Recovery itself never calls Kafka. The normal outbox publisher performs the new
attempt after the recovery transaction commits, preserving the same locking,
acknowledgement, and retry rules. A second recovery call is rejected once the
new cycle is active. Requeueing authorizes another idempotent delivery attempt;
it does not claim that the earlier timed-out delivery failed.

This operation is deliberately not exposed through HTTP yet. The service has no
operator authentication boundary, and an unauthenticated financial-event
requeue endpoint would be unsafe. A future adapter must be deny-by-default,
record authenticated actor and reason, make recovery commands idempotent, and
return no event payload or sensitive persistence fields.

## Failure States

| State | Meaning | Operator action |
| --- | --- | --- |
| `CREATED` | Payment accepted and awaiting event processing. | Watch for stuck records older than the SLA. |
| `PUBLISHED` | Payment event was acknowledged by Kafka. | Confirm consumer lag remains low. |
| `COMPLETED` | Ledger entry was written successfully. | No action required. |
| `PENDING_RETRY` | A retryable dependency failed. | Review retry queue and dependency health. |
| `PUBLISH_EXHAUSTED` | The outbox exhausted its publication attempts; delivery may still be unknown. | Inspect Kafka and ledger state before future manual requeue. |

## Implementation Checklist

- [x] Require an `Idempotency-Key` header for payment creation.
- [x] Persist a non-raw key digest with the payment result and enforce uniqueness.
- [x] Add duplicate event detection in the transaction service.
- [x] Add tests for repeated payment requests with the same key.
- [x] Add a transactional outbox for recoverable payment event publication.
- [x] Add bounded exponential retry and terminal relay exhaustion handling.
- [x] Add an internal locked recovery boundary for exhausted events.
- Add a secured operator adapter, recovery audit log, and dead-letter routing.
- Add dashboard panels for retry count, duplicate events, and stuck payments.
