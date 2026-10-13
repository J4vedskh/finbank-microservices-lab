package com.banking.account.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class GrafanaDashboardContractTest {

    private static final String DASHBOARD_RELATIVE_PATH =
            "ops/grafana/finbank-service-overview.json";
    private static final String PROMETHEUS_INPUT = "${DS_PROMETHEUS}";
    private static final Pattern METRIC_NAME =
            Pattern.compile("(?<![A-Za-z0-9_:])([A-Za-z_:][A-Za-z0-9_:]*)\\s*\\{");
    private static final Set<String> ALLOWED_METRICS = Set.of(
            "up",
            "http_server_requests_seconds_count",
            "http_server_requests_seconds_sum",
            "jvm_memory_used_bytes",
            "jvm_threads_live_threads",
            "process_uptime_seconds");
    private static final List<String> EXPECTED_EXPRESSIONS = List.of(
            "min(up{group=~\"$service_group\", job=~\"$job\", instance=~\"$instance\"})",
            "count(up{group=~\"$service_group\", job=~\"$job\", instance=~\"$instance\"})",
            "sum(increase(http_server_requests_seconds_count{group=~\"$service_group\", job=~\"$job\", instance=~\"$instance\", status=~\"5..\", uri!~\"/actuator.*\"}[$__range]))",
            "max(process_uptime_seconds{group=~\"$service_group\", job=~\"$job\", instance=~\"$instance\"})",
            "up{group=~\"$service_group\", job=~\"$job\", instance=~\"$instance\"}",
            "sum by (job, instance, outcome) (rate(http_server_requests_seconds_count{group=~\"$service_group\", job=~\"$job\", instance=~\"$instance\", uri!~\"/actuator.*\"}[$__rate_interval]))",
            "sum by (job, instance) (rate(http_server_requests_seconds_sum{group=~\"$service_group\", job=~\"$job\", instance=~\"$instance\", uri!~\"/actuator.*\"}[$__rate_interval])) / sum by (job, instance) (rate(http_server_requests_seconds_count{group=~\"$service_group\", job=~\"$job\", instance=~\"$instance\", uri!~\"/actuator.*\"}[$__rate_interval]))",
            "sum by (job, instance) (jvm_memory_used_bytes{group=~\"$service_group\", job=~\"$job\", instance=~\"$instance\", area=\"heap\"})",
            "jvm_threads_live_threads{group=~\"$service_group\", job=~\"$job\", instance=~\"$instance\"}");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void dashboardIsPortableAndPanelsDoNotOverlap() throws IOException {
        JsonNode dashboard = readDashboard();

        assertThat(dashboard.path("title").asText()).isEqualTo("FinBank Service Runtime Overview");
        assertThat(dashboard.path("id").isNull()).isTrue();
        assertThat(dashboard.path("uid").isNull()).isTrue();
        assertThat(dashboard.path("schemaVersion").asInt()).isGreaterThanOrEqualTo(39);
        assertThat(dashboard.path("refresh").asText()).isEqualTo("30s");
        assertThat(dashboard.path("time").path("from").asText()).isEqualTo("now-6h");

        JsonNode inputs = dashboard.path("__inputs");
        assertThat(inputs).hasSize(1);
        assertThat(inputs.get(0).path("name").asText()).isEqualTo("DS_PROMETHEUS");
        assertThat(inputs.get(0).path("pluginId").asText()).isEqualTo("prometheus");

        JsonNode panels = dashboard.path("panels");
        assertThat(panels).hasSize(9);
        Set<Integer> panelIds = new HashSet<>();
        List<PanelRectangle> rectangles = new ArrayList<>();
        for (JsonNode panel : panels) {
            int id = panel.path("id").asInt();
            assertThat(panelIds.add(id)).as("unique panel id %s", id).isTrue();
            assertThat(panel.path("description").asText()).isNotBlank();
            assertThat(panel.path("fieldConfig").path("defaults").path("noValue").asText())
                    .isEqualTo("No data");
            assertThat(panel.has("alert")).isFalse();
            assertDatasource(panel.path("datasource"));
            for (JsonNode target : panel.path("targets")) {
                assertDatasource(target.path("datasource"));
            }

            JsonNode grid = panel.path("gridPos");
            PanelRectangle rectangle = new PanelRectangle(
                    id,
                    grid.path("x").asInt(),
                    grid.path("y").asInt(),
                    grid.path("w").asInt(),
                    grid.path("h").asInt());
            assertThat(rectangle.x()).isBetween(0, 23);
            assertThat(rectangle.width()).isBetween(1, 24);
            assertThat(rectangle.x() + rectangle.width()).isLessThanOrEqualTo(24);
            assertThat(rectangle.height()).isPositive();
            rectangles.add(rectangle);
        }

        for (int first = 0; first < rectangles.size(); first++) {
            for (int second = first + 1; second < rectangles.size(); second++) {
                assertThat(overlaps(rectangles.get(first), rectangles.get(second)))
                        .as("panels %s and %s must not overlap", rectangles.get(first).id(), rectangles.get(second).id())
                        .isFalse();
            }
        }
    }

    @Test
    void queriesUseOnlyVerifiedMetricsAndBoundedLabels() throws IOException {
        JsonNode dashboard = readDashboard();
        List<String> expressions = new ArrayList<>();
        Set<String> metricNames = new HashSet<>();

        for (JsonNode panel : dashboard.path("panels")) {
            for (JsonNode target : panel.path("targets")) {
                String expression = target.path("expr").asText();
                expressions.add(expression);
                assertThat(expression)
                        .contains("group=~\"$service_group\"")
                        .contains("job=~\"$job\"")
                        .contains("instance=~\"$instance\"");
                if (expression.contains("rate(")) {
                    assertThat(expression).contains("[$__rate_interval]");
                }
                if (expression.contains("increase(")) {
                    assertThat(expression).contains("[$__range]");
                }
                Matcher matcher = METRIC_NAME.matcher(expression);
                while (matcher.find()) {
                    metricNames.add(matcher.group(1));
                }
            }
        }

        assertThat(expressions).containsExactlyElementsOf(EXPECTED_EXPRESSIONS);
        assertThat(metricNames).containsExactlyInAnyOrderElementsOf(ALLOWED_METRICS);
        assertThat(String.join("\n", expressions))
                .contains("uri!~\"/actuator.*\"")
                .doesNotContain(
                        "by (uri)",
                        "{{uri}}",
                        "exception",
                        "request_id",
                        "payload",
                        "authorization",
                        "cookie",
                        "header",
                        "client_address",
                        "http://",
                        "https://");
    }

    @Test
    void dashboardHasExpectedScanOrderUnitsAndTargetFilters() throws IOException {
        JsonNode dashboard = readDashboard();
        Map<String, String> expectedUnits = Map.of(
                "Selected targets", "short",
                "5xx responses in range", "short",
                "Longest process uptime", "s",
                "Target availability", "short",
                "HTTP request outcomes", "reqps",
                "Average HTTP duration", "s",
                "JVM heap used", "bytes",
                "Live JVM threads", "short");

        List<String> titles = new ArrayList<>();
        for (JsonNode panel : dashboard.path("panels")) {
            String title = panel.path("title").asText();
            titles.add(title);
            if (expectedUnits.containsKey(title)) {
                assertThat(panel.path("fieldConfig").path("defaults").path("unit").asText())
                        .isEqualTo(expectedUnits.get(title));
            }
        }
        assertThat(titles).containsExactly(
                "Scrape health",
                "Selected targets",
                "5xx responses in range",
                "Longest process uptime",
                "Target availability",
                "HTTP request outcomes",
                "Average HTTP duration",
                "JVM heap used",
                "Live JVM threads");

        List<String> variableNames = StreamSupport.stream(
                        dashboard.path("templating").path("list").spliterator(), false)
                .map(variable -> variable.path("name").asText())
                .toList();
        assertThat(variableNames).containsExactly("service_group", "job", "instance");

        JsonNode serviceGroup = dashboard.path("templating").path("list").get(0);
        assertDatasource(serviceGroup.path("datasource"));
        assertThat(serviceGroup.path("includeAll").asBoolean()).isFalse();
        assertThat(serviceGroup.path("multi").asBoolean()).isFalse();
        assertThat(serviceGroup.path("current").path("value").asText()).isEqualTo("microservices");
        assertThat(serviceGroup.path("definition").asText()).isEqualTo("label_values(up, group)");

        for (JsonNode variable : List.of(
                dashboard.path("templating").path("list").get(1),
                dashboard.path("templating").path("list").get(2))) {
            assertDatasource(variable.path("datasource"));
            assertThat(variable.path("includeAll").asBoolean()).isTrue();
            assertThat(variable.path("multi").asBoolean()).isTrue();
        }
        assertThat(dashboard.path("templating").path("list").get(1).path("definition").asText())
                .isEqualTo("label_values(up{group=~\"$service_group\"}, job)");
        assertThat(dashboard.path("templating").path("list").get(2).path("definition").asText())
                .isEqualTo("label_values(up{group=~\"$service_group\", job=~\"$job\"}, instance)");
    }

    private JsonNode readDashboard() throws IOException {
        return objectMapper.readTree(Files.readString(dashboardPath()));
    }

    private Path dashboardPath() {
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", "."));
        Path dashboard = root.resolve(DASHBOARD_RELATIVE_PATH);
        if (Files.exists(dashboard)) {
            return dashboard;
        }
        return Path.of("..").resolve(DASHBOARD_RELATIVE_PATH).normalize();
    }

    private void assertDatasource(JsonNode datasource) {
        assertThat(datasource.path("type").asText()).isEqualTo("prometheus");
        assertThat(datasource.path("uid").asText()).isEqualTo(PROMETHEUS_INPUT);
    }

    private boolean overlaps(PanelRectangle first, PanelRectangle second) {
        return first.x() < second.x() + second.width()
                && first.x() + first.width() > second.x()
                && first.y() < second.y() + second.height()
                && first.y() + first.height() > second.y();
    }

    private record PanelRectangle(int id, int x, int y, int width, int height) {
    }
}
