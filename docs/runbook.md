# Runbook

This runbook keeps local build, documentation, and platform commands in one
place so the project is easy to inspect.

## Build

Use JDK 17:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -T 1C clean package
```

## Documentation

Install the docs dependencies and build the site:

```bash
pip install -r docs/requirements.txt
mkdocs build --strict
```

Preview locally:

```bash
mkdocs serve
```

The strict build validates the OpenAPI document, unique operation IDs,
per-service server mappings, operator security alternatives, pinned Swagger UI
checksums, and generated API Explorer files. The explorer is deliberately
read-only, uses a generated public specification with no authentication schemes,
and loads its Swagger UI code and styles from checked-in assets rather than a CDN.

## Payment Outbox Operator Access

The payment service exposes restricted recovery and handoff-inspection routes.
Select exactly one authentication mode. Basic remains the default:

```text
PAYMENT_RECOVERY_AUTHENTICATION_MODE=basic
PAYMENT_RECOVERY_OPERATOR_USERNAME=<dedicated-operator-name>
PAYMENT_RECOVERY_OPERATOR_PASSWORD_HASH=<Spring-style {bcrypt} hash>
```

In Basic mode, when both credential values are blank, no fallback operator
exists and both routes return HTTP `401`. Supplying only one value, an invalid
username, plaintext password, a non-BCrypt value, or credentials without a valid
operator transport stops startup instead of weakening access. Keep the raw
client password in an approved secret store, not in repository files or shell
history.

External JWT mode disables HTTP Basic and requires all three values:

```text
PAYMENT_RECOVERY_AUTHENTICATION_MODE=jwt
PAYMENT_RECOVERY_EXTERNAL_JWT_ISSUER_URI=https://identity.example/issuer
PAYMENT_RECOVERY_EXTERNAL_JWT_JWK_SET_URI=https://identity.example/.well-known/jwks.json
PAYMENT_RECOVERY_EXTERNAL_JWT_AUDIENCE=finbank-payment-operations
```

JWT mode accepts RS256 only. It validates exact issuer and audience, requires
`iat` and `exp`, honors `nbf` when present, and requires a nonblank visible-ASCII
`sub` no longer than 100 characters. Time checks allow 60 seconds of clock skew.
The subject becomes the recovery audit actor. Scope mapping is deliberately
narrow and accepts the standard `scope` string or `scp` collection:

| Scope | Capability |
| --- | --- |
| `payment.outbox.recovery` | Submit an audited recovery command |
| `payment.outbox.handoff-inspection` | Inspect bounded local handoff metadata |

Unknown scopes are ignored. JWT mode rejects local Basic credentials; Basic
mode rejects JWT settings, so a partial or mixed configuration cannot silently
weaken authentication. Bearer tokens must never be stored, logged, or copied
into recovery reasons. IdP provisioning, JWK availability and rotation, and
real-token end-to-end validation remain deployment responsibilities.

### Operator Transport

Direct transport is the default:

```text
PAYMENT_RECOVERY_TRANSPORT_MODE=direct
```

An active operator then requires `server.ssl.enabled=true` and a request through
the application HTTPS connector. Trusted-proxy addresses must be empty. Configure
the standard Spring Boot `server.ssl.*` keystore settings through the deployment
secret mechanism; this repository does not provide a TLS connector.

For deliberate TLS termination at one or more reverse proxies, configure exact
immediate-peer addresses:

```text
PAYMENT_RECOVERY_TRANSPORT_MODE=trusted-proxy
PAYMENT_RECOVERY_TRUSTED_PROXY_ADDRESSES=10.20.30.40,2001:db8::40
```

These values are exact numeric IPv4 or IPv6 literals—not DNS names or CIDRs.
Duplicates, blanks, IPv4-mapped IPv6 aliases, and ambiguous representations stop
startup. Application SSL is optional in this mode, and direct HTTPS still works.
Only `/internal/payment-outbox/**` can be presented as secure by the proxy filter.
The immediate socket peer must match the configured list, there must be exactly
one `X-Forwarded-Proto: https` header value, and no `Forwarded` header may be
present. Missing, non-HTTPS, repeated, comma-separated, or conflicting values
fail the secure-channel check before authentication. `X-Forwarded-For`,
`X-Forwarded-Host`, and `X-Forwarded-Port` never establish trust.

Keep `server.forward-headers-strategy=none`; an active operator refuses startup
if that setting is changed. The reverse proxy must:

1. Strip every client-supplied `Forwarded` and `X-Forwarded-*` header.
2. Add one `X-Forwarded-Proto: https` only after its own TLS handshake succeeds.
3. Be the only network peer allowed to reach the clear-HTTP service listener.
4. Preserve its actual source address so the service sees the configured peer.

Clients must never manufacture the trusted protocol header. Address matching
cannot protect a deployment whose trusted proxy forwards an attacker's header.
Network exposure, certificates, and proxy rules remain deployment
responsibilities. Real IdP/JWK availability, rotation, and token interoperability
remain unqualified. Public payment routes plus health, info, and Prometheus
endpoints remain allowlisted. Apart from the framework `/error` dispatch,
unlisted payment-service paths are denied.

Successful recovery reasons are retained in the successful-command audit table.
Never include credentials, tokens, event payloads, personal data, or secrets.
Rejected business commands use a different journal containing only requested
event id, fixed rejection code, and server time. If that journal is unavailable,
the API returns a generic `503` rather than the original `404` or `409`.

The held Kubernetes Prometheus configuration still uses its default `/metrics` scrape path;
aligning it to `/actuator/prometheus` remains pending until platform work is
released. Both paths stay allowlisted so this security change does not further
restrict the existing scraper.

## Local Dead-Letter Handoffs

Each committed terminal outbox cycle creates one row in
`payment_outbox_dead_letter_handoff`. It contains only the source event id,
exhaustion sequence and time, final attempt count, and safe failure type. The
source outbox row continues to own the topic, key, and payload.

Use the restricted endpoint for normal inspection. Omitting `limit` returns at
most 50 rows; the maximum is 100. When `nextCursor` is non-null, pass it as the
exclusive `afterId` in the next request:

```bash
curl --user "<operator-username>" \
  --cacert "<payment-service-ca.pem>" \
  "https://localhost:8082/internal/payment-outbox/dead-letter-handoffs?limit=50"
```

That localhost example is for Basic authentication over direct application TLS.
In a trusted-proxy deployment, call the proxy's externally managed HTTPS URL and
let the proxy add the protocol header. In JWT mode, use the approved identity
client to send an `Authorization: Bearer` token with
`payment.outbox.handoff-inspection`; do not paste tokens into shell history.

The response contains only handoff id, event id, exhaustion sequence and time,
attempt count, and nullable safe failure type. It does not return topic, event
key, payload, payment/account data, exception messages, or recovery metadata.
A null `nextCursor` means no further row was observed for that request; a later
terminal cycle can still create a newer handoff.

Approved read-only database access remains a break-glass fallback:

```sql
SELECT id,
       outbox_event_id,
       exhaustion_sequence,
       exhausted_at,
       attempt_count,
       failure_type
FROM payment_outbox_dead_letter_handoff
ORDER BY exhausted_at, id;
```

Recovery preserves the prior handoff and appends another only if a later retry
cycle also exhausts. After recovery and successful publication, opt-in outbox
retention deletes the handoffs immediately before deleting the old source event.
Neither the endpoint nor this table is evidence of Kafka DLT publication or
consumer receipt. Independently available external delivery remains future
work.

## Outbox Retention

Published outbox and recovery-journal cleanup is disabled by default. Before
enabling it, choose retention periods that satisfy the audit and incident
investigation policy. The equivalent environment settings are:

```text
PAYMENT_OUTBOX_RETENTION_ENABLED=true
PAYMENT_OUTBOX_RETENTION_PUBLISHED_RETENTION_DAYS=30
PAYMENT_OUTBOX_RETENTION_REJECTION_RETENTION_DAYS=30
PAYMENT_OUTBOX_RETENTION_BATCH_SIZE=100
PAYMENT_OUTBOX_RETENTION_CLEANUP_DELAY_MS=86400000
```

The service accepts retention periods from `1..36500` days and batch sizes from
`1..1000`, and rejects cleanup delays below one second at startup. Each run
claims at most one configured batch of strictly old `PUBLISHED` events and one
batch of old rejection rows. Recovery audits and dead-letter handoffs are
removed before each eligible event. It does not delete payments, pending
retries, or exhausted events; all of those event categories remain in
nonterminal `PENDING` state. Enabling retention permanently removes eligible
local event payloads and recovery history; it does not confirm downstream
consumer completion.

The repository currently validates cleanup behavior with H2. Qualify lock
contention and the new exhaustion-sequence column, handoff table, constraints,
and indexes against the target MySQL version. Use a reviewed schema migration
and explicitly backfill any already-exhausted production rows before treating
the handoff or retention jobs as production-ready.

## Local Infrastructure

```bash
docker-compose up -d
```

The compose file starts MySQL, Kafka, Zookeeper, Prometheus, Grafana, and Jaeger.

## Kubernetes

```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/mysql-deployment.yaml
kubectl apply -f k8s/account-deployment.yaml
kubectl apply -f k8s/payment-deployment.yaml
kubectl apply -f k8s/transaction-deployment.yaml
```

## Useful Ports

| Tool or service | Port |
| --- | ---: |
| Account Service | 8081 |
| Payment Service | 8082 |
| Transaction Service | 8083 |
| MySQL | 3306 |
| Kafka | 9092 |
| Prometheus | 9090 |
| Grafana | 3000 |
| Jaeger | 16686 |
