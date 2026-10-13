# Grafana Dashboard

FinBank includes an importable Grafana starter dashboard at
[`ops/grafana/finbank-service-overview.json`](https://github.com/J4vedskh/finbank-microservices-lab/blob/main/ops/grafana/finbank-service-overview.json).
It is deployment-independent: import it into a Grafana instance that already
has a Prometheus data source, then choose that source when Grafana asks for it.
The JSON does not add provisioning, containers, Kubernetes resources, alert
rules, or custom business metrics.

## Before You Import

The dashboard reads only the default metrics that the three Spring Boot
services export through `/actuator/prometheus`. It uses the Prometheus `up`,
`http_server_requests_seconds_*`, `process_uptime_seconds`,
`jvm_memory_used_bytes`, and `jvm_threads_live_threads` metrics.

The held Kubernetes Prometheus configuration still scrapes `/metrics`, while
the services export at `/actuator/prometheus`. Until that held platform change
is released, Kubernetes-backed panels will show **DOWN** or **No data**. That
is an expected integration blocker, not proof that the dashboard JSON or the
services are broken. Verify the local endpoints first as described in the
[runbook](runbook.md#local-metrics-verification).

To import the starter, open Grafana's **Dashboards** page, select **New** then
**Import**, and upload the JSON file. Select the Prometheus data source when
prompted. The dashboard starts in the last six hours and refreshes every
30 seconds; adjust those controls in Grafana when investigating a different
period.

## Filters and Panels

The **Service group** (`$service_group`) filter defaults to the repository's
`microservices` scrape label, keeping unrelated targets out of the FinBank
view on a shared Prometheus data source. Use **Job** (`$job`) and **Instance**
(`$instance`) to narrow that group further. Job and instance allow multiple
selections and default to all targets inside the selected service group. If a
filter has no matching target, panels show **No data** rather than pretending
the value is zero. A deployment that uses a different value for the `group`
label must select its FinBank group before interpreting the panels. If the
Prometheus target label key is renamed or absent, adapt the dashboard JSON's
`group` selectors first; choosing another value cannot repair a missing key.

The dashboard contains nine panels:

| Panel | What it shows |
| --- | --- |
| Scrape health | The worst selected target state: **UP** or **DOWN**. |
| Selected targets | Number of selected Prometheus targets. |
| 5xx responses in range | Non-Actuator HTTP 5xx responses in the chosen time range. |
| Longest process uptime | The highest current uptime across selected instances. |
| Target availability | Per-target scrape state over time. |
| HTTP request outcomes | Non-Actuator request rate split by Micrometer outcome. |
| Average HTTP duration | Mean request duration by target. This is an average, **not p95**. |
| JVM heap used | Current heap memory used by target. |
| Live JVM threads | Current live-thread count by target. |

## Reading the Dashboard Safely

Zero means Prometheus returned a real zero value. **No data** means there was
no usable matching time series, which can result from unavailable scraping, an
unavailable data source, or filters that match no target. Start with **Scrape
health** and **Selected targets** before interpreting the application panels.

Panel colors are supporting cues, not the only message. The health panel uses
explicit **UP** and **DOWN** text; every panel has a title, description, legend
or displayed value, and a direct **No data** label. The 5xx panel's red
threshold begins at one response, but it creates no alert. Grafana's responsive
grid stacks panels on narrower screens, while the panel titles, labels, direct
state text, and threshold values remain available for keyboard and assistive
technology users through the Grafana interface.

This is a runtime overview starter only. It does not calculate percentiles,
provide p95 latency, create alerts, prove a live Grafana deployment, or show
payment, ledger, Kafka, database, or other custom business metrics. No live
screenshot is included or claimed. A verified screenshot remains pending until
the held Kubernetes scrape-path alignment allows the service targets to be
scraped successfully.
