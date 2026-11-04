package org.migrax.dialect;

import java.util.regex.Pattern;

/** Schema names in the statements of a migration script. */
final class ScriptNames {
  /** A name most databases read as written. */
  static final Pattern PLAIN = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_$]*");

  /** A name PostgreSQL reads as written: it folds anything else to lower case. */
  static final Pattern PLAIN_LOWER = Pattern.compile("[\\p{Ll}_][\\p{Ll}\\p{N}_$]*");

  private ScriptNames() {}

  /** The name as it is when it is plain, or else quoted, so it stays one name. */
  static String schema(String name, Pattern plain, char quote) {
    if (plain.matcher(name).matches()) {
      return name;
    }
    String doubled = name.replace(String.valueOf(quote), String.valueOf(quote) + quote);
    return quote + doubled + quote;
  }
}
