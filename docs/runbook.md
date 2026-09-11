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

## Recovery Operator Access

The payment service exposes one restricted recovery route. Supply both values
through the runtime secret mechanism; never commit either value:

```text
PAYMENT_RECOVERY_OPERATOR_USERNAME=<dedicated-operator-name>
PAYMENT_RECOVERY_OPERATOR_PASSWORD_HASH=<Spring-style {bcrypt} hash>
```

When both values are blank, no fallback operator exists and the recovery route
returns HTTP `401`. Supplying only one value, an invalid username, plaintext
password, a non-BCrypt value, or credentials without `server.ssl.enabled=true`
stops startup instead of weakening access. Configure the standard Spring Boot
`server.ssl.*` keystore settings through the deployment secret mechanism before
activating the operator. Keep the raw client password in an approved secret
store, not in repository files or shell history.

This is a single-operator HTTP Basic boundary that is disabled by default. The
application does not enforce loopback-only exposure or provide a TLS connector,
but it requires a secure recovery request. The repository does not configure a
separate clear-HTTP connector; the security filter redirects only an insecure
servlet request that reaches it.
Network exposure and TLS material remain deployment responsibilities. Proxy-only
TLS termination and forwarded-scheme trust are not supported yet; external
identity integration is also pending. Public payment routes plus health, info,
and Prometheus endpoints remain allowlisted, while unlisted payment-service
paths are denied.

Recovery reasons are retained in the audit table. Never include credentials,
tokens, event payloads, personal data, or secrets. The held Kubernetes
Prometheus configuration still uses its default `/metrics` scrape path;
aligning it to `/actuator/prometheus` remains pending until platform work is
released. Both paths stay allowlisted so this security change does not further
restrict the existing scraper.

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
