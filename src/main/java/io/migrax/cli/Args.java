package io.migrax.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Parsed command line: command, positionals, options and flags. */
final class Args {
  final String[] raw;
  final String command;
  final List<String> positionals;
  private final Map<String, String> options;
  private final Set<String> flags;

  private Args(String[] raw, List<String> positionals, Map<String, String> options,
               Set<String> flags) {
    this.raw = raw;
    this.positionals = positionals;
    this.command = positionals.isEmpty() ? null : positionals.get(0);
    this.options = options;
    this.flags = flags;
  }

  static Args parse(String[] raw) {
    List<String> positionals = new ArrayList<>();
    Map<String, String> options = new LinkedHashMap<>();
    Set<String> flags = new LinkedHashSet<>();
    for (int i = 0; i < raw.length; i++) {
      String token = raw[i];
      if (!token.startsWith("-") || token.equals("-")) {
        positionals.add(token);
        continue;
      }
      String key = token;
      String value = null;
      int equals = token.indexOf('=');
      if (token.startsWith("--") && equals > 0) {
        key = token.substring(0, equals);
        value = token.substring(equals + 1);
      }
      if (Options.VALUES.contains(key)) {
        if (value == null) {
          if (i + 1 >= raw.length || raw[i + 1].startsWith("--")) {
            String help = Objects.requireNonNullElse(Options.help(key), key + " <value>");
            throw new UsageException("Option " + key + " needs a value.",
                "Example: " + help.split("\\s{2,}")[0]);
          }
          value = raw[++i];
        }
        String optionKey = key;
        // Repeated rename options accumulate.
        options.merge(key, value,
            (a, b) -> optionKey.startsWith("--rename") ? a + "," + b : b);
      } else if (Options.FLAGS.contains(key) && value == null) {
        flags.add(key);
      } else {
        List<String> known = new ArrayList<>(Options.VALUES);
        known.addAll(Options.FLAGS);
        String suggestion = Suggestions.closest(key, known);
        throw new UsageException("Unknown option '" + token + "'.",
            suggestion == null ? "Run 'migrax help' to see the options."
                : "Did you mean '" + suggestion + "'?");
      }
    }
    return new Args(raw, List.copyOf(positionals), options, flags);
  }

  String option(String key) {
    return options.get(key);
  }

  String option(String key, String defaultValue) {
    return options.getOrDefault(key, defaultValue);
  }

  boolean flag(String... names) {
    for (String name : names) {
      if (flags.contains(name)) {
        return true;
      }
    }
    return false;
  }
}
