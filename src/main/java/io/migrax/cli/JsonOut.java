package io.migrax.cli;

import io.migrax.util.Json;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON writer for {@code --json} output: maps, lists, strings, numbers, booleans. */
final class JsonOut {
  private JsonOut() {}

  /** An insertion-ordered map built from key/value pairs. */
  static Map<String, Object> object(Object... keyValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
    }
    return map;
  }

  static String write(Object value) {
    StringBuilder out = new StringBuilder();
    write(out, value);
    return out.toString();
  }

  private static void write(StringBuilder out, Object value) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String s) {
      out.append(Json.q(s));
    } else if (value instanceof Number || value instanceof Boolean) {
      out.append(value);
    } else if (value instanceof Map<?, ?> map) {
      out.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first) {
          out.append(',');
        }
        first = false;
        out.append(Json.q(String.valueOf(entry.getKey()))).append(':');
        write(out, entry.getValue());
      }
      out.append('}');
    } else if (value instanceof Collection<?> list) {
      out.append('[');
      boolean first = true;
      for (Object item : list) {
        if (!first) {
          out.append(',');
        }
        first = false;
        write(out, item);
      }
      out.append(']');
    } else if (value instanceof Enum<?> e) {
      out.append(Json.q(e.name().toLowerCase(java.util.Locale.ROOT)));
    } else {
      out.append(Json.q(String.valueOf(value)));
    }
  }

  static List<Object> list(Collection<?> items) {
    return List.copyOf(items);
  }
}
