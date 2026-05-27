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

## Quick Start

Build all modules with JDK 17:

```bash
mvn -T 1C clean package
```

Run local infrastructure:

```bash
docker-compose up -d
```

Apply the Kubernetes manifests to a local Minikube cluster:

```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/mysql-deployment.yaml
kubectl apply -f k8s/account-deployment.yaml
kubectl apply -f k8s/payment-deployment.yaml
kubectl apply -f k8s/transaction-deployment.yaml
```

## Documentation

Install and build the documentation site:

```bash
pip install -r docs/requirements.txt
mkdocs build --strict
```

The docs use Material for MkDocs and Mermaid diagrams so the architecture stays
versioned next to the code.
