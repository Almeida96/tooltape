package io.github.almeida96.tooltape;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Json {
  private Json() {}

  public static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  static {
    MAPPER
        .getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(128)
                .maxStringLength(262144)
                .maxNumberLength(1000)
                .build());
  }

  public static JsonNode read(String text) throws IOException {
    try (var parser = MAPPER.createParser(text)) {
      JsonNode node = MAPPER.readTree(parser);
      if (node == null || parser.nextToken() != null)
        throw new IOException("Expected one JSON document");
      validateUnicode(node);
      return node;
    }
  }

  private static void validateUnicode(JsonNode node) throws IOException {
    if (node.isTextual()) validString(node.textValue());
    if (node.isObject()) {
      var keys = node.fieldNames();
      while (keys.hasNext()) validString(keys.next());
    }
    if (node.isContainerNode()) for (JsonNode child : node) validateUnicode(child);
  }

  static void validString(String text) throws IOException {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
          throw new IOException("Unpaired Unicode surrogate");
      } else if (Character.isLowSurrogate(c)) throw new IOException("Unpaired Unicode surrogate");
    }
  }

  public static String compact(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot encode JSON", e);
    }
  }

  public static String pretty(Object value) {
    try {
      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot encode JSON", e);
    }
  }

  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String hash(String value) {
    return hash(value.getBytes(StandardCharsets.UTF_8));
  }

  public static String escape(String token) {
    return token.replace("~", "~0").replace("/", "~1");
  }
}
