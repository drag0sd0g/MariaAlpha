package com.mariaalpha.e2e;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

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

  String apiKey() {
    return API_KEY;
  }

  String gatewayBaseUrl() {
    return "http://localhost:8080";
  }

  synchronized void start() {
    if (started) {
      return;
    }
    var dockerComposeFile = new File("../docker-compose.yml");
    var ciOverride = new File("../docker-compose.ci.yml");
    boolean usePrebuilt =
        Boolean.parseBoolean(System.getenv().getOrDefault("MARIAALPHA_E2E_USE_PREBUILT_JARS", "false"))
            && ciOverride.exists();
    File[] composeFiles =
        usePrebuilt ? new File[] {dockerComposeFile, ciOverride} : new File[] {dockerComposeFile};
    if (usePrebuilt) {
      LOG.info("Shared compose stack: using docker-compose.ci.yml override (pre-built jars)");
    }
    forceDownExistingStack(dockerComposeFile);
    composeContainer =
        new ComposeContainer(composeFiles)
            .withLocalCompose(true)
            .withEnv("MARIAALPHA_API_KEY", API_KEY)
            .withEnv("POSTGRES_USER", "mariaalpha")
            .withEnv("POSTGRES_PASSWORD", "mariaalpha")
            .withEnv("POSTGRES_DB", "mariaalpha")
            .withEnv("ALPACA_API_KEY_ID", "unused")
            .withEnv("ALPACA_API_SECRET_KEY", "unused")
            .withBuild(true)
            .withRemoveVolumes(true)
            .waitingFor(
                "api-gateway",
                Wait.forLogMessage(".*Started Application in.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(4)))
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
