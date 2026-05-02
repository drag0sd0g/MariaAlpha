package com.mariaalpha.apigateway.websocket;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SymbolKeyExtractor {

  private static final Logger LOG = LoggerFactory.getLogger(SymbolKeyExtractor.class);
  private static final JsonFactory FACTORY = new JsonFactory();

  private SymbolKeyExtractor() {}

  public static String extract(String json, String fieldName) {
    if (json == null || json.isEmpty() || fieldName == null) {
      return null;
    }
    try (var parser = FACTORY.createParser(json)) {
      while (parser.nextToken() != null) {
        if (parser.currentToken() == JsonToken.FIELD_NAME
            && fieldName.equals(parser.currentName())) {
          parser.nextToken();
          return parser.getValueAsString();
        }
      }
    } catch (IOException ex) {
      LOG.debug("malformed json on topic event, dropping filter: {}", ex.toString());
    }
    return null;
  }
}
