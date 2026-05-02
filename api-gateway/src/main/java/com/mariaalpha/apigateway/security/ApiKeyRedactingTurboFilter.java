package com.mariaalpha.apigateway.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import java.util.regex.Pattern;
import org.slf4j.Marker;

public class ApiKeyRedactingTurboFilter extends TurboFilter {

  private static final Pattern API_KEY_QUERY = Pattern.compile("apiKey=[^&\\s\"']+");
  private static final Pattern API_KEY_HEADER = Pattern.compile("(?i)X-API-Key:\\s*[^\\s\"']+");

  @Override
  public FilterReply decide(
      Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
    if (format != null && (format.contains("apiKey=") || format.contains("X-API-Key"))) {
      var redacted =
          API_KEY_HEADER
              .matcher(API_KEY_QUERY.matcher(format).replaceAll("apiKey=***"))
              .replaceAll("X-API-Key: ***");
      logger.callAppenders(new LoggingEvent(logger.getName(), logger, level, redacted, t, params));
      return FilterReply.DENY;
    }
    return FilterReply.NEUTRAL;
  }
}
