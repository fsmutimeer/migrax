package org.migrax.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Error texts for people. */
public final class Messages {
  private Messages() {}

  /** One-line error text: the message plus distinct cause messages. */
  public static String describe(Throwable error) {
    List<String> parts = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Throwable current = error; current != null && parts.size() < 3;
         current = current.getCause()) {
      String message = current.getMessage();
      if (message == null || message.isBlank()) {
        message = current.getClass().getSimpleName();
      }
      if (current instanceof LinkageError) {
        message = current.getClass().getSimpleName() + ": " + message;
      }
      message = message.strip();
      boolean repeated = false;
      for (String previous : seen) {
        if (previous.contains(message) || message.contains(previous) && previous.length() > 20) {
          repeated = true;
          break;
        }
      }
      if (!repeated) {
        seen.add(message);
        parts.add(message);
      }
      if (current.getCause() == current) {
        break;
      }
    }
    return String.join(System.lineSeparator() + "  caused by: ", parts);
  }
}
