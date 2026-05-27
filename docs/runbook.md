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
