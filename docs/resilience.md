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
    participant Kafka as Kafka
    participant Ledger as Transaction Service

    Client->>Payments: POST /payments with Idempotency-Key
    Payments->>Store: Find payment by key
    alt Existing key
        Store-->>Payments: Existing payment result
        Payments-->>Client: Return original result
    else New key
        Payments->>Store: Create payment as CREATED
        Payments->>Kafka: Publish payment-created event
        Payments-->>Client: Return new payment id
        Kafka-->>Ledger: Deliver event
        Ledger->>Ledger: Ignore duplicate event ids
    end
```

## Planned Retry Boundaries

The following policies are design targets. They are not runtime guarantees until
the matching retry, recovery-state, and dead-letter work is implemented.

| Boundary | Planned policy | Completion signal |
| --- | --- | --- |
| Client to Payment Service | Client may retry with the same idempotency key. | Original result is returned or request expires. |
| Payment Service to database | Service retries short transient connection failures. | Database confirms write or returns non-retryable error. |
| Payment Service to Kafka | Service retries publish failures before marking payment pending. | Event is acknowledged or payment enters recovery state. |
| Transaction Service consumer | Consumer retries event processing with backoff. | Ledger write succeeds or event is sent to dead-letter handling. |

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

The first successful database insert still requests Kafka publication
asynchronously. A process failure between those operations can leave a payment
without a published event, and an exact retry deliberately does not publish it
again. A transactional outbox is the next resilience increment for closing that
recovery gap.

## Failure States

| State | Meaning | Operator action |
| --- | --- | --- |
| `CREATED` | Payment accepted and awaiting event processing. | Watch for stuck records older than the SLA. |
| `PUBLISHED` | Payment event was acknowledged by Kafka. | Confirm consumer lag remains low. |
| `COMPLETED` | Ledger entry was written successfully. | No action required. |
| `PENDING_RETRY` | A retryable dependency failed. | Review retry queue and dependency health. |
| `FAILED` | A non-retryable validation or processing error occurred. | Expose a clear client-facing error and audit trail. |

## Implementation Checklist

- [x] Require an `Idempotency-Key` header for payment creation.
- [x] Persist a non-raw key digest with the payment result and enforce uniqueness.
- [x] Add duplicate event detection in the transaction service.
- [x] Add tests for repeated payment requests with the same key.
- Add a transactional outbox for recoverable payment event publication.
- Add dashboard panels for retry count, duplicate events, and stuck payments.
