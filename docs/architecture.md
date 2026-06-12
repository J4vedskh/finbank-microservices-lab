# Architecture

The system is organized around independent business capabilities. Each service
owns its runtime, API surface, persistence model, and operational metadata.

## Service Responsibilities

| Service | Owns | Publishes | Consumes |
| --- | --- | --- | --- |
| Account Service | Customer accounts and balances | None yet | None yet |
| Payment Service | Payment requests and status | `payments` Kafka events | None yet |
| Transaction Service | Transaction ledger entries | None yet | `payments` Kafka events |

## Payment Sequence

```mermaid
sequenceDiagram
    participant Client
    participant Payments as Payment Service
    participant DB as Payment DB
    participant Kafka as Kafka payments topic
    participant Ledger as Transaction Service
    participant LedgerDB as Transaction DB

    Client->>Payments: POST /payments
    Payments->>DB: Save payment as CREATED
    Payments->>Kafka: Publish payment event
    Payments-->>Client: Return payment id and status
    Kafka-->>Ledger: Deliver payment event
    Ledger->>LedgerDB: Save transaction as COMPLETED
```

## Deployment Topology

```mermaid
flowchart TB
    subgraph Cluster["Kubernetes namespace: banking"]
        Ingress["Future ingress or API gateway"]
        AccountPod["account-service deployment"]
        PaymentPod["payment-service deployment"]
        TransactionPod["transaction-service deployment"]
        MySQLPod["mysql deployment"]
        PrometheusPod["prometheus"]
    end

    Ingress --> AccountPod
    Ingress --> PaymentPod
    Ingress --> TransactionPod
    AccountPod --> MySQLPod
    PaymentPod --> MySQLPod
    TransactionPod --> MySQLPod
    PrometheusPod --> AccountPod
    PrometheusPod --> PaymentPod
    PrometheusPod --> TransactionPod
```

## Observability View

```mermaid
flowchart LR
    Services["Spring Boot services"] --> Actuator["Actuator endpoints"]
    Actuator --> Prometheus["Prometheus scrape jobs"]
    Prometheus --> Grafana["Grafana dashboards"]
    Services --> Logs["Structured logs"]
    Services --> Traces["Future OpenTelemetry traces"]
    Traces --> Jaeger["Jaeger trace search"]
```

The detailed signal plan is tracked in the
[observability guide](observability.md).

## Design Direction

The lab will evolve toward realistic service contracts, validation, error
handling, database migrations, resilience patterns, and documented operational
workflows while keeping each daily change reviewable.
