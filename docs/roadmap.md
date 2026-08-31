# Roadmap

The project grows through small, reviewable changes so progress remains visible
without sacrificing code quality.

```mermaid
gantt
    title 60-day portfolio growth path
    dateFormat  YYYY-MM-DD
    section Foundation
    Buildable scaffold and docs portal      :done, foundation, 2026-05-27, 3d
    Service validation and DTOs             :active, dto, 2026-05-30, 7d
    section Platform
    Docker Compose hardening                :compose, after dto, 7d
    Kubernetes manifests and probes         :k8s, after compose, 10d
    section Quality
    Unit and integration tests              :tests, 2026-06-03, 20d
    CI quality gates                        :ci, after tests, 7d
    section Observability
    Prometheus metrics and dashboards       :metrics, 2026-06-15, 12d
    OpenTelemetry tracing                   :tracing, after metrics, 10d
```

## Near-Term Backlog

| Priority | Improvement |
| --- | --- |
| 1 | Completed: add DTOs and validation to account and payment APIs |
| 2 | Completed: add service-layer boundaries before controllers call repositories |
| 3 | In progress: account and payment H2 coverage complete; transaction persistence coverage next |
| 4 | Add idempotency-key handling for payment creation |
| 5 | Add OpenAPI YAML and Swagger UI documentation |
| 6 | Add health probes and resource limits to Kubernetes manifests |
| 7 | Add Prometheus dashboard documentation and screenshots |

## Commit Standard

Daily commits should be genuine, focused, and easy to review. A good commit
changes one thing, includes verification notes in the PR, and improves either the
runtime system or the public portfolio story.
