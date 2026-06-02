package com.mariaalpha.posttrade.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Sanity-checks every provisioned Grafana dashboard (issues 2.6.2 / 2.6.3 / 2.6.4):
 *
 * <ul>
 *   <li>parses as JSON
 *   <li>declares a stable {@code uid} and a recent {@code schemaVersion}
 *   <li>has unique panel ids inside one dashboard, and a unique uid across the set
 *   <li>every PromQL expression references a Prometheus metric that this codebase actually
 *       registers — so a renamed metric breaks the build instead of silently producing a "No data"
 *       panel in production
 *   <li>the Helm-chart copies (`charts/.../files/grafana-dashboards/`) match the compose-mounted
 *       originals byte-for-byte (no drift between local and k8s deployments)
 * </ul>
 */
class GrafanaDashboardTest {

  private static final Path REPO_ROOT = repoRoot();
  private static final Path COMPOSE_DASHBOARDS =
      REPO_ROOT.resolve("config/grafana/provisioning/dashboards");
  private static final Path HELM_DASHBOARDS =
      REPO_ROOT.resolve("charts/mariaalpha/files/grafana-dashboards");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  // Metrics whose names are produced by Prometheus / infrastructure itself, not registered in our
  // source. Allow-list them so the test doesn't false-positive on standard infrastructure queries.
  private static final Set<String> INFRA_METRICS =
      Set.of(
          "up",
          "scrape_duration_seconds",
          "scrape_samples_scraped",
          "process_cpu_seconds_total",
          "process_resident_memory_bytes");

  // Metric suffixes Micrometer/Prometheus appends automatically.
  private static final List<String> SYNTHETIC_SUFFIXES =
      List.of(
          "_total",
          "_count",
          "_sum",
          "_max",
          "_bucket",
          "_seconds_count",
          "_seconds_sum",
          "_seconds_max",
          "_seconds_bucket",
          "_seconds");

  private static final Pattern METRIC_REF =
      Pattern.compile("(?<![a-zA-Z0-9_:])(mariaalpha_[a-zA-Z0-9_]+)");

  static List<Path> dashboardFiles() throws IOException {
    try (Stream<Path> stream = Files.list(COMPOSE_DASHBOARDS)) {
      return stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
    }
  }

  @ParameterizedTest(name = "{0} parses cleanly")
  @MethodSource("dashboardFiles")
  void dashboardParses(Path file) throws IOException {
    JsonNode root = MAPPER.readTree(file.toFile());
    assertThat(root.isObject()).as("%s root is an object", file).isTrue();
    assertThat(root.path("uid").asText()).as("%s has uid", file).isNotBlank();
    assertThat(root.path("schemaVersion").asInt())
        .as("%s schemaVersion >= 36", file)
        .isGreaterThanOrEqualTo(36);
    assertThat(root.path("title").asText()).as("%s has title", file).isNotBlank();
    assertThat(root.path("panels").isArray()).as("%s has panels", file).isTrue();
  }

  @ParameterizedTest(name = "{0} panel ids are unique")
  @MethodSource("dashboardFiles")
  void panelIdsAreUnique(Path file) throws IOException {
    JsonNode root = MAPPER.readTree(file.toFile());
    Set<Integer> seen = new HashSet<>();
    for (JsonNode panel : root.withArray("panels")) {
      int id = panel.path("id").asInt(-1);
      assertThat(id).as("%s panel without id", file).isPositive();
      assertThat(seen.add(id)).as("%s duplicate panel id %d", file, id).isTrue();
    }
  }

  @Test
  void dashboardUidsAreUniqueAcrossSet() throws IOException {
    Set<String> seen = new HashSet<>();
    for (Path p : dashboardFiles()) {
      String uid = MAPPER.readTree(p.toFile()).path("uid").asText();
      assertThat(seen.add(uid)).as("duplicate uid '%s' in %s", uid, p).isTrue();
    }
  }

  @ParameterizedTest(name = "{0} promql metrics are registered in source")
  @MethodSource("dashboardFiles")
  void promqlMetricsExistInSource(Path file) throws IOException {
    Set<String> registered = collectRegisteredMetrics();
    JsonNode root = MAPPER.readTree(file.toFile());
    List<String> exprs = new ArrayList<>();
    collectExprs(root, exprs);
    assertThat(exprs).as("%s has at least one promql target", file).isNotEmpty();

    List<String> missing = new ArrayList<>();
    for (String expr : exprs) {
      Matcher m = METRIC_REF.matcher(expr);
      while (m.find()) {
        String metric = m.group(1);
        if (!isKnown(metric, registered)) {
          missing.add(metric + " (in expr: " + expr + ")");
        }
      }
    }
    assertThat(missing)
        .as("%s references unknown metrics — rename in source or fix dashboard query", file)
        .isEmpty();
  }

  @ParameterizedTest(name = "{0} matches the helm-chart copy")
  @MethodSource("dashboardFiles")
  void helmCopyMatches(Path composeFile) throws IOException {
    Path helmFile = HELM_DASHBOARDS.resolve(composeFile.getFileName());
    assertThat(Files.exists(helmFile))
        .as("missing helm copy of %s — run scripts/sync-dashboards.sh", composeFile.getFileName())
        .isTrue();
    byte[] compose = Files.readAllBytes(composeFile);
    byte[] helm = Files.readAllBytes(helmFile);
    assertThat(helm)
        .as(
            "helm chart copy of %s diverged from the compose original — re-copy to keep local "
                + "and k8s in sync",
            composeFile.getFileName())
        .isEqualTo(compose);
  }

  private static void collectExprs(JsonNode node, List<String> out) {
    if (node == null) {
      return;
    }
    if (node.isObject()) {
      JsonNode expr = node.get("expr");
      if (expr != null && expr.isTextual() && !expr.asText().isBlank()) {
        out.add(expr.asText());
      }
      node.fields().forEachRemaining(e -> collectExprs(e.getValue(), out));
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        collectExprs(child, out);
      }
    }
  }

  private static boolean isKnown(String metric, Set<String> registered) {
    if (INFRA_METRICS.contains(metric)) {
      return true;
    }
    if (registered.contains(metric)) {
      return true;
    }
    // Try stripping known Prometheus/Micrometer suffixes.
    for (String suffix : SYNTHETIC_SUFFIXES) {
      if (metric.endsWith(suffix)) {
        String stripped = metric.substring(0, metric.length() - suffix.length());
        if (registered.contains(stripped) || registered.contains(stripped + "_total")) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Walks every Java + Python file once and harvests {@code mariaalpha.*} / {@code mariaalpha_*}
   * literals. Cheap (~50 ms on this repo) and avoids hard-coding a list that goes stale on every
   * new metric.
   */
  static Set<String> collectRegisteredMetrics() throws IOException {
    Set<String> out = new HashSet<>();
    // Dotted Micrometer names → convert to underscores (Prometheus format).
    Pattern dotted = Pattern.compile("\"(mariaalpha\\.[a-zA-Z0-9._]+)\"");
    // Underscored Prometheus names (python prometheus_client / micrometer with raw names).
    Pattern underscored = Pattern.compile("\"(mariaalpha_[a-zA-Z0-9_]+)\"");
    Path[] scanRoots =
        new Path[] {
          REPO_ROOT.resolve("market-data-gateway/src"),
          REPO_ROOT.resolve("strategy-engine/src"),
          REPO_ROOT.resolve("execution-engine/src"),
          REPO_ROOT.resolve("order-manager/src"),
          REPO_ROOT.resolve("post-trade/src"),
          REPO_ROOT.resolve("api-gateway/src"),
          REPO_ROOT.resolve("analytics-service/src"),
          REPO_ROOT.resolve("ml-signal-service/src")
        };
    for (Path root : scanRoots) {
      if (!Files.exists(root)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(root)) {
        walk.filter(Files::isRegularFile)
            .filter(
                p -> {
                  String n = p.getFileName().toString();
                  return n.endsWith(".java") || n.endsWith(".py");
                })
            .forEach(p -> harvest(p, out, dotted, underscored));
      }
    }
    return out;
  }

  private static void harvest(Path file, Set<String> out, Pattern dotted, Pattern underscored) {
    try {
      String content = Files.readString(file);
      Matcher d = dotted.matcher(content);
      while (d.find()) {
        out.add(d.group(1).replace('.', '_'));
      }
      Matcher u = underscored.matcher(content);
      while (u.find()) {
        out.add(u.group(1));
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to read " + file, e);
    }
  }

  private static Path repoRoot() {
    // Tests run from each module's directory (e.g. post-trade/). Walk up until we find the marker.
    Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    while (p != null && !Files.exists(p.resolve("settings.gradle.kts"))) {
      p = p.getParent();
    }
    if (p == null) {
      throw new IllegalStateException(
          "Could not find repo root from " + System.getProperty("user.dir"));
    }
    return p;
  }
}
