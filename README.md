# FinBank Microservices Lab

FinBank Microservices Lab is a portfolio-grade cloud banking system built with Java,
Spring Boot, Kafka, MySQL, Docker, Kubernetes, and observability tooling.

The live documentation portal is designed for recruiters and engineers who want a
quick look at the architecture, service responsibilities, API surface, and delivery
roadmap:

`https://j4vedskh.github.io/finbank-microservices-lab/`

## Services

| Service | Port | Responsibility |
| --- | ---: | --- |
| Account Service | 8081 | Customer accounts and balances |
| Payment Service | 8082 | Payment creation and event publishing |
| Transaction Service | 8083 | Payment event consumption and history |

## Build And Local Support

Build all modules with JDK 17:

```bash
mvn -T 1C clean package
```

This packages the application JARs; it does not build or publish the service
container images.

Start the checked-in support containers:

```bash
docker-compose up -d
```

Compose starts MySQL, Kafka, Zookeeper, Prometheus, Grafana, and Jaeger. It does
not start the three Spring Boot services or provide a verified end-to-end demo.

### Kubernetes starter status

The files under `k8s/` are a held deployment scaffold, not a runnable Minikube
quick start. Before they can be used as a complete stack, they still need:

- published or locally loaded service images instead of placeholder names
- in-cluster Kafka and initialization for the three MySQL databases
- for durable or production use, managed credentials and persistent storage
- a documented access path, health probes, and resource limits
- a Kubernetes Prometheus deployment with the scrape path aligned to
  `/actuator/prometheus`

Docker, Kubernetes, Helm, and CI changes remain intentionally on hold.

## Documentation

Install and build the documentation site:

```bash
pip install -r docs/requirements.txt
mkdocs build --strict
```

The docs use Material for MkDocs and Mermaid diagrams so the architecture stays
versioned next to the code. A locally vendored, read-only Swagger UI renders a
credential-free public projection generated from the canonical OpenAPI contract,
and the strict docs build validates the contract and viewer assets offline.
