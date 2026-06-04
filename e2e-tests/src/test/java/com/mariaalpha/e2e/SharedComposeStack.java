package com.mariaalpha.e2e;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * JVM-singleton wrapper around the project's {@code docker-compose.yml} so multiple e2e test
 * classes can share one running stack.
 *
 * <p>Why this exists: the original e2e setup spun up a full stack in every test class's
 * {@code @BeforeAll} via {@link ComposeContainer}. Five classes × ~2 minutes of {@code docker
 * compose up} (build + healthcheck cascade) = ~10 minutes of CI time before a single test ran.
 * By sharing one stack across compatible classes and only tearing it down at JVM exit, the e2e job
 * pays the startup cost once.
 *
 * <p>Compatibility rule: a class can attach to this shared stack <i>only</i> if its required
 * compose configuration matches the default — i.e. no per-test environment overrides. Tests that
 * need a different stack config (e.g. {@code Phase2RiskChecksE2ETest} tightening
 * {@code EXECUTION_ENGINE_RISK_MAX_ADV_PARTICIPATION}) must run their own private stack.
 *
 * <p>Concurrency: {@link #start()} is synchronized and idempotent so that whichever
 * {@code @BeforeAll} fires first wins; later callers no-op. A JVM shutdown hook owns the teardown
 * so the stack is released even if a test exits abnormally.
 */
final class SharedComposeStack {

  private static final Logger LOG = LoggerFactory.getLogger(SharedComposeStack.class);
  private static final String API_KEY = "e2e-shared-key";
  private static final SharedComposeStack INSTANCE = new SharedComposeStack();

  private ComposeContainer composeContainer;
  private boolean started;

  private SharedComposeStack() {}

  static SharedComposeStack get() {
    return INSTANCE;
  }

  /** Stable API key used by every shared-stack test class. */
  String apiKey() {
    return API_KEY;
  }

  /** Gateway base URL — {@code http://localhost:8080}, mapped by docker-compose port binding. */
  String gatewayBaseUrl() {
    return "http://localhost:8080";
  }

  synchronized void start() {
    if (started) {
      return;
    }
    var dockerComposeFile = new File("../docker-compose.yml");
    // Force-down any stale stack left over by a previous crashed/killed run before starting fresh.
    // (This only fires on the very first call across the whole JVM.)
    forceDownExistingStack(dockerComposeFile);
    composeContainer =
        new ComposeContainer(dockerComposeFile)
            .withLocalCompose(true)
            .withEnv("MARIAALPHA_API_KEY", API_KEY)
            .withEnv("POSTGRES_USER", "mariaalpha")
            .withEnv("POSTGRES_PASSWORD", "mariaalpha")
            .withEnv("POSTGRES_DB", "mariaalpha")
            // Alpaca creds aren't used by the simulated profile but compose interpolates them.
            .withEnv("ALPACA_API_KEY_ID", "unused")
            .withEnv("ALPACA_API_SECRET_KEY", "unused")
            .withBuild(true)
            .withRemoveVolumes(true)
            // The api-gateway "Started Application in …" log marker fires last in the dependency
            // chain (it depends_on every downstream's service_healthy), so waiting on it confirms
            // the whole stack is up. Direct port probes via withExposedService route through a
            // socat ambassador that can't cross compose v2 user-defined networks — log waits go
            // through the Docker log API instead, which works regardless of network.
            .waitingFor(
                "api-gateway",
                Wait.forLogMessage(".*Started Application in.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(4)))
            // Stream a handful of services' logs through to System.out so CI logs carry the
            // diagnostic detail tests rely on when assertions fail.
            .withLogConsumer("api-gateway", f -> System.out.print("[api-gw] " + f.getUtf8String()))
            .withLogConsumer(
                "strategy-engine", f -> System.out.print("[strategy] " + f.getUtf8String()))
            .withLogConsumer(
                "execution-engine", f -> System.out.print("[exec] " + f.getUtf8String()))
            .withLogConsumer("order-manager", f -> System.out.print("[om] " + f.getUtf8String()))
            .withLogConsumer(
                "analytics-service", f -> System.out.print("[analytics] " + f.getUtf8String()))
            .withLogConsumer(
                "ml-signal-service", f -> System.out.print("[ml] " + f.getUtf8String()));
    composeContainer.start();
    started = true;
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  synchronized (SharedComposeStack.this) {
                    if (composeContainer != null) {
                      LOG.info("Stopping shared compose stack at JVM shutdown");
                      try {
                        composeContainer.stop();
                      } catch (RuntimeException e) {
                        LOG.warn("Shared compose stack stop failed: {}", e.getMessage());
                      }
                    }
                  }
                },
                "shared-compose-stack-shutdown"));
  }

  /**
   * Tear down the shared stack early, before JVM shutdown. Used by {@link Phase2RiskChecksE2ETest}
   * — that class needs to swap in its own ComposeContainer with a tightened ADV-participation
   * env override, and the two ComposeContainer instances would otherwise collide on the same
   * compose project name ("mariaalpha") and fail with an opaque {@code compose up} exit code 1.
   */
  synchronized void stopIfRunning() {
    if (!started || composeContainer == null) {
      return;
    }
    LOG.info("Stopping shared compose stack early (requested by another test class)");
    try {
      composeContainer.stop();
    } catch (RuntimeException e) {
      LOG.warn("Early stop of shared compose stack failed: {}", e.getMessage());
    }
    composeContainer = null;
    started = false;
  }

  private static void forceDownExistingStack(File dockerComposeFile) {
    try {
      new ProcessBuilder(
              "docker",
              "compose",
              "-f",
              dockerComposeFile.getCanonicalPath(),
              "down",
              "-v",
              "--remove-orphans")
          .directory(dockerComposeFile.getCanonicalFile().getParentFile())
          .redirectErrorStream(true)
          .start()
          .waitFor();
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      LOG.warn("Pre-startup `docker compose down` failed (continuing): {}", e.getMessage());
    }
  }
}
