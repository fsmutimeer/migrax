package org.migrax.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * After {@code migrax import}, finds what still ties the project to the old tool: its
 * dependencies and build plugins, and its settings in the application config. A forgotten
 * dependency keeps the old tool migrating at startup next to Migrax.
 *
 * <p>Only names are reported (artifact ids, coordinates, setting keys), never a value, so a
 * password in a build file or the config is not printed.
 */
final class ImportLeftovers {
  /** One line to change: where, what is there, and what to do with it. */
  record Leftover(String file, int line, String what, String action) {
    @Override
    public String toString() {
      return printable(file) + ":" + line + ": " + printable(what) + ": " + action;
    }

    /** Control and format characters from the scanned files would steer the terminal: ?. */
    private static String printable(String text) {
      return UNPRINTABLE.matcher(text).replaceAll("?");
    }
  }

  private enum Kind { POM, GRADLE, TOML, PROPERTIES, YAML }

  private record Found(Path file, Kind kind) {}

  private record Level(int indent, String key) {}

  /** Build output folders; skipped unless they hold a build file of their own (a module). */
  private static final Set<String> OUTPUT_FOLDERS = Set.of("target", "build", "out", "bin",
      "dist", "node_modules");
  private static final List<String> BUILD_FILES = List.of("pom.xml", "build.gradle",
      "build.gradle.kts");
  private static final int MAX_DEPTH = 8;
  private static final Pattern UNPRINTABLE = Pattern.compile("[\\p{Cc}\\p{Cf}]");
  private static final Pattern COORDINATE_PART = Pattern.compile("[a-z0-9][a-z0-9_.\\-]*");
  /** The tools' Gradle plugin ids. */
  private static final Set<String> PLUGIN_IDS = Set.of("org.flywaydb.flyway",
      "org.liquibase.gradle");
  /** Longer build script lines are generated, not written by hand: skipped. */
  private static final int MAX_LINE = 10_000;
  /** Larger files are not build files or settings anyone writes by hand: skipped. */
  private static final long MAX_SIZE = 2_000_000;
  private static final Pattern GROUP_ID = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>");
  private static final Pattern ARTIFACT_ID =
      Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
  private static final Pattern YAML_KEY =
      Pattern.compile("^(\\s*)([A-Za-z0-9_.%\\-\"']+)\\s*:(?:\\s+(.*))?$");
  private static final Pattern NAME_TOKEN = Pattern.compile("[A-Za-z0-9_.:\\-]+");

  private ImportLeftovers() {}

  /**
   * Scans the build files ({@code pom.xml}, {@code build.gradle[.kts]}, {@code
   * *.versions.toml}) and application settings ({@code application*.properties}, {@code .yml},
   * {@code .yaml}) under {@code root} for {@code tool} ("flyway" or "liquibase").
   */
  static List<Leftover> find(Path root, String tool) throws IOException {
    List<Found> files = new ArrayList<>();
    Path realRoot = root.toRealPath();
    Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
            String name = name(dir);
            boolean skip = !dir.equals(root) && (name.startsWith(".")
                || OUTPUT_FOLDERS.contains(name)
                && BUILD_FILES.stream().noneMatch(file -> Files.isRegularFile(dir.resolve(file)))
                || !insideProject(dir));
            return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
          }

          /** False for a Windows junction (not a symbolic link to Java) that leads elsewhere. */
          private boolean insideProject(Path dir) {
            try {
              Path real = dir.toRealPath();
              return real.startsWith(realRoot) && !real.equals(realRoot);
            } catch (IOException e) {
              return false;
            }
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            Kind kind = kind(name(file));
            if (attributes.isRegularFile() && kind != null && attributes.size() <= MAX_SIZE) {
              files.add(new Found(file, kind));
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException e) {
            return FileVisitResult.CONTINUE;
          }
        });
    files.sort(Comparator.comparing(Found::file));
    List<Leftover> found = new ArrayList<>();
    for (Found each : files) {
      String shown = root.relativize(each.file()).toString().replace('\\', '/');
      String text;
      try {
        text = Files.readString(each.file(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        continue; // Not UTF-8 or unreadable: nothing we can point to.
      }
      switch (each.kind()) {
        case POM -> scanPom(shown, text, tool, found);
        case GRADLE -> scanBuildScript(shown, text.lines().toList(), tool, "//", found);
        case TOML -> scanBuildScript(shown, text.lines().toList(), tool, "#", found);
        case PROPERTIES -> scanProperties(shown, text.lines().toList(), tool, found);
        case YAML -> scanYaml(shown, text.lines().toList(), tool, found);
      }
    }
    return found;
  }

  private static String name(Path path) {
    Path name = path.getFileName();
    return name == null ? "" : name.toString();
  }

  private static Kind kind(String name) {
    if (name.equals("pom.xml")) {
      return Kind.POM;
    }
    if (name.equals("build.gradle") || name.equals("build.gradle.kts")) {
      return Kind.GRADLE;
    }
    if (name.endsWith(".versions.toml")) {
      return Kind.TOML;
    }
    if (name.startsWith("application")) {
      if (name.endsWith(".properties")) {
        return Kind.PROPERTIES;
      }
      if (name.endsWith(".yml") || name.endsWith(".yaml")) {
        return Kind.YAML;
      }
    }
    return null;
  }

  /**
   * Dependencies and plugins whose artifact id names the tool. Exclusions are not reported, and
   * neither are the project's own artifacts (same group id as the project or its parent), so a
   * module called {@code flyway-demo-api} is left alone. Tags are found with indexOf, never with
   * a pattern that searches to the end again for each opening tag, so a broken POM stays fast.
   */
  private static void scanPom(String file, String text, String tool, List<Leftover> found) {
    // Comments become spaces, so offsets still give the right line numbers.
    String pom = blank(text, "<!--", "-->", true);
    int[] lineStarts = lineStarts(pom);
    Set<String> ownGroups = new HashSet<>(Set.of("${project.groupId}", "${groupId}"));
    String outside = pom;
    int parentStart = pom.indexOf("<parent>");
    int parentEnd = parentStart < 0 ? -1 : pom.indexOf("</parent>", parentStart);
    if (parentEnd >= 0) {
      Matcher group = GROUP_ID.matcher(pom.substring(parentStart, parentEnd));
      if (group.find()) {
        ownGroups.add(group.group(1));
      }
      outside = blank(pom, "<parent>", "</parent>", false);
    }
    int firstBlock = firstIndex(outside, "<dependencies>", "<dependencyManagement>", "<build>",
        "<profiles>");
    Matcher projectGroup = GROUP_ID.matcher(outside);
    if (projectGroup.find() && projectGroup.start() < firstBlock) {
      ownGroups.add(projectGroup.group(1));
    }
    List<Leftover> inPom = new ArrayList<>();
    scanBlocks(file, pom, lineStarts, "dependency", List.of("exclusions"), tool, ownGroups,
        inPom);
    scanBlocks(file, pom, lineStarts, "plugin",
        List.of("dependencies", "configuration", "executions"), tool, ownGroups, inPom);
    inPom.sort(Comparator.comparingInt(Leftover::line));
    found.addAll(inPom);
  }

  private static void scanBlocks(String file, String pom, int[] lineStarts, String element,
                                 List<String> inner, String tool, Set<String> ownGroups,
                                 List<Leftover> found) {
    String open = "<" + element + ">";
    String close = "</" + element + ">";
    int from = 0;
    while (true) {
      int start = pom.indexOf(open, from);
      int end = start < 0 ? -1 : pom.indexOf(close, start + open.length());
      if (end < 0) {
        return;
      }
      from = end + close.length();
      String body = pom.substring(start + open.length(), end);
      for (String name : inner) {
        body = blank(body, "<" + name + ">", "</" + name + ">", false);
      }
      Matcher artifact = ARTIFACT_ID.matcher(body);
      if (!artifact.find() || !lower(artifact.group(1)).contains(tool)) {
        continue;
      }
      Matcher group = GROUP_ID.matcher(body);
      if (group.find() && ownGroups.contains(group.group(1))) {
        continue;
      }
      int line = lineOf(lineStarts, start + open.length() + artifact.start());
      found.add(new Leftover(file, line, element + " " + artifact.group(1), "remove it"));
    }
  }

  /**
   * {@code text} with each part from {@code open} to {@code close} turned into spaces (line
   * breaks kept). An {@code open} without its {@code close} blanks the rest when {@code toEnd}
   * (an unclosed comment), else ends the search.
   */
  private static String blank(String text, String open, String close, boolean toEnd) {
    StringBuilder result = null;
    int from = 0;
    while (true) {
      int start = text.indexOf(open, from);
      if (start < 0) {
        break;
      }
      int end = text.indexOf(close, start + open.length());
      if (end < 0 && !toEnd) {
        break;
      }
      int stop = end < 0 ? text.length() : end + close.length();
      if (result == null) {
        result = new StringBuilder(text);
      }
      for (int i = start; i < stop; i++) {
        if (text.charAt(i) != '\n') {
          result.setCharAt(i, ' ');
        }
      }
      if (end < 0) {
        break;
      }
      from = stop;
    }
    return result == null ? text : result.toString();
  }

  private static int firstIndex(String text, String... needles) {
    int first = text.length();
    for (String needle : needles) {
      int at = text.indexOf(needle);
      if (at >= 0 && at < first) {
        first = at;
      }
    }
    return first;
  }

  private static int[] lineStarts(String text) {
    List<Integer> starts = new ArrayList<>(List.of(0));
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        starts.add(i + 1);
      }
    }
    return starts.stream().mapToInt(Integer::intValue).toArray();
  }

  /** The 1-based line of an offset, from {@link #lineStarts}. */
  private static int lineOf(int[] lineStarts, int offset) {
    int index = Arrays.binarySearch(lineStarts, offset);
    return index >= 0 ? index + 1 : -index - 1;
  }

  /**
   * Gradle scripts and version catalogs: a coordinate or plugin id of the tool in quotes
   * ({@code "org.flywaydb:flyway-core"}, {@code 'io.quarkus:quarkus-flyway'}), or outside quotes
   * one of its configurations ({@code liquibaseRuntime}), its settings block ({@code flyway {}),
   * a catalog entry ({@code flyway-core = ...}) or accessor ({@code libs.flyway.core}). Other
   * quoted text is a value and is not shown, even when the tool's name is in it; of a
   * coordinate only the group and artifact are shown, never the version.
   */
  private static void scanBuildScript(String file, List<String> lines, String tool,
                                      String comment, List<Leftover> found) {
    boolean inBlockComment = false;
    boolean groovy = file.endsWith(".gradle");
    StringBuilder openString = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (inBlockComment) {
        int end = line.indexOf("*/");
        if (end < 0) {
          continue;
        }
        line = line.substring(end + 2);
        inBlockComment = false;
      }
      if (!comment.equals("#")) {
        int start = line.indexOf("/*");
        if (start >= 0) {
          int end = line.indexOf("*/", start + 2);
          inBlockComment = end < 0;
          line = line.substring(0, start) + (end < 0 ? "" : line.substring(end + 2));
        }
      }
      if (line.length() > MAX_LINE) {
        continue;
      }
      line = blankLongStrings(line, groovy, comment, openString);
      if (line.contains("project(")) {
        continue; // A module of this build, even when its name has the tool in it.
      }
      String name = toolName(line, comment, tool);
      if (name != null) {
        found.add(new Leftover(file, i + 1, name, "remove it"));
      }
    }
  }

  /**
   * {@code line} with the text of triple-quoted strings ({@code """..."""}, {@code '''...'''})
   * and, in Groovy, slashy strings ({@code /.../}, {@code $/.../$}) replaced by spaces: their
   * text is a value, possibly over several lines. {@code open} holds the closing delimiter of
   * a string still open at the end of the line, empty when none is.
   */
  private static String blankLongStrings(String line, boolean groovy, String comment,
                                         StringBuilder open) {
    StringBuilder result = new StringBuilder(line.length());
    int i = 0;
    while (i < line.length()) {
      if (open.length() > 0) {
        int end = closing(line, i, open.toString());
        int stop = end < 0 ? line.length() : end + open.length();
        result.append(" ".repeat(stop - i));
        if (end >= 0) {
          open.setLength(0);
        }
        i = stop;
        continue;
      }
      if (line.startsWith(comment, i)) {
        result.append(line, i, line.length());
        break;
      }
      char ch = line.charAt(i);
      if (line.startsWith("\"\"\"", i) || line.startsWith("'''", i)) {
        open.append(line, i, i + 3);
        result.append("   ");
        i += 3;
      } else if (ch == '"' || ch == '\'') {
        int end = line.indexOf(ch, i + 1);
        int stop = end < 0 ? line.length() : end + 1;
        result.append(line, i, stop);
        i = stop;
      } else if (groovy && line.startsWith("$/", i)) {
        open.append("/$");
        result.append("  ");
        i += 2;
      } else if (groovy && ch == '/' && !line.startsWith("/*", i)
          && startsValue(result.toString())) {
        open.append('/');
        result.append(' ');
        i++;
      } else {
        result.append(ch);
        i++;
      }
    }
    return result.toString();
  }

  /** Where {@code delimiter} closes a string in {@code line} from {@code from}, or -1. */
  private static int closing(String line, int from, String delimiter) {
    int end = line.indexOf(delimiter, from);
    // A slashy string's / can be escaped as \/.
    while (end > 0 && delimiter.equals("/") && line.charAt(end - 1) == '\\') {
      end = line.indexOf(delimiter, end + 1);
    }
    return end;
  }

  /** Whether a / after {@code before} starts a Groovy slashy string rather than dividing. */
  private static boolean startsValue(String before) {
    String text = before.stripTrailing();
    return text.isEmpty() || "=(,:[?!{~".indexOf(text.charAt(text.length() - 1)) >= 0
        || endsWithWord(text, "return");
  }

  private static boolean endsWithWord(String text, String word) {
    return text.endsWith(word) && (text.length() == word.length()
        || !Character.isLetterOrDigit(text.charAt(text.length() - word.length() - 1)));
  }

  /** The first name of the tool on a build script line, reading quoted text as described. */
  private static String toolName(String line, String comment, String tool) {
    StringBuilder unquoted = new StringBuilder();
    int i = 0;
    while (i < line.length()) {
      char ch = line.charAt(i);
      if (line.startsWith(comment, i)) {
        break;
      }
      if (ch == '"' || ch == '\'') {
        int end = line.indexOf(ch, i + 1);
        String quoted = end < 0 ? line.substring(i + 1) : line.substring(i + 1, end);
        String flushed = unquotedName(unquoted.toString(), tool, line.substring(i));
        if (flushed != null) {
          return flushed;
        }
        String before = unquoted.toString();
        unquoted.setLength(0);
        String coordinate = coordinate(quoted, tool, before);
        if (coordinate != null) {
          return coordinate;
        }
        if (end < 0) {
          return null;
        }
        i = end + 1;
        continue;
      }
      unquoted.append(ch);
      i++;
    }
    return unquotedName(unquoted.toString(), tool, "");
  }

  private static String unquotedName(String text, String tool, String rest) {
    Matcher token = NAME_TOKEN.matcher(text);
    while (token.find()) {
      String name = token.group();
      String after = text.substring(token.end()) + rest;
      String low = lower(name);
      boolean block = low.equals(tool) && after.stripLeading().startsWith("{");
      boolean configuration = low.startsWith(tool) && name.length() > tool.length()
          && Character.isUpperCase(name.charAt(tool.length()));
      boolean catalogEntry = low.startsWith(tool + "-") && after.stripLeading().startsWith("=");
      boolean accessor = low.startsWith("libs.")
          && List.of(low.split("\\.")).contains(tool);
      if (block || configuration || catalogEntry || accessor) {
        return name;
      }
    }
    return null;
  }

  /**
   * The tool's plugin id ({@code org.flywaydb.flyway}), or the group and artifact when quoted
   * text is {@code group:artifact[:version]} with a dotted group and an artifact named after the
   * tool, both in lower case as published. The version may be anything
   * ({@code $flywayVersion}, {@code 10.+}) and is left out. Null for anything else, such as a
   * URL, text with spaces or capitals. Assigned or passed along as a value ({@code x = '...'},
   * {@code "key" to "..."}), text that isn't in the tool's own group may be anything, so it is
   * not shown: then just the tool is named ({@code flyway coordinate}).
   */
  private static String coordinate(String quoted, String tool, String before) {
    String[] parts = quoted.split(":", 3);
    if (parts.length == 1) {
      return PLUGIN_IDS.contains(quoted) && quoted.contains(tool) ? quoted : null;
    }
    String group = parts[0];
    String artifact = parts[1];
    if (!group.contains(".") || !COORDINATE_PART.matcher(group).matches()
        || !COORDINATE_PART.matcher(artifact).matches()) {
      return null;
    }
    boolean named = artifact.startsWith(tool + "-") || artifact.endsWith("-" + tool)
        || artifact.contains("-" + tool + "-") || artifact.equals(tool);
    if (!named) {
      return null;
    }
    boolean ownGroup = group.equals("org.flywaydb") || group.equals("org.liquibase");
    return ownGroup || isDependency(before) ? group + ":" + artifact : tool + " coordinate";
  }

  /**
   * Whether quoted text after {@code before} is a dependency or plugin
   * ({@code implementation '...'}, {@code classpath("...")}) rather than a value.
   */
  private static boolean isDependency(String before) {
    String text = before.stripTrailing();
    if (text.isEmpty()) {
      return false;
    }
    char last = text.charAt(text.length() - 1);
    // A version catalog's module = "group:artifact" is a dependency too.
    if (last == '=' && endsWithWord(text.substring(0, text.length() - 1).stripTrailing(),
        "module")) {
      return true;
    }
    // Kotlin's "key" to "value" pairs a value with its key.
    return last == '(' || Character.isLetter(last) && !endsWithWord(text, "to");
  }

  private static void scanProperties(String file, List<String> lines, String tool,
                                     List<Leftover> found) {
    boolean continued = false;
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).strip();
      boolean continuation = continued;
      // A line ending in an odd number of backslashes goes on in the next one: part of a value.
      int backslashes = 0;
      while (backslashes < line.length()
          && line.charAt(line.length() - 1 - backslashes) == '\\') {
        backslashes++;
      }
      boolean comment = line.startsWith("#") || line.startsWith("!");
      continued = backslashes % 2 != 0 && (continuation || !comment);
      if (continuation || line.isEmpty() || comment) {
        continue;
      }
      int end = 0;
      while (end < line.length() && line.charAt(end) != '=' && line.charAt(end) != ':'
          && !Character.isWhitespace(line.charAt(end))) {
        end++;
      }
      addSetting(file, i + 1, line.substring(0, end), tool, found);
    }
  }

  private static void scanYaml(String file, List<String> lines, String tool,
                               List<Leftover> found) {
    Deque<Level> levels = new ArrayDeque<>();
    int blockIndent = -1;
    String pendingKey = null;
    int pendingLine = 0;
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.startsWith("---")) {
        levels.clear();
        blockIndent = -1;
        pendingKey = null;
        continue;
      }
      String trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      int indent = line.length() - line.stripLeading().length();
      if (blockIndent >= 0 && indent > blockIndent) {
        continue; // Inside a multi-line value (| or >).
      }
      blockIndent = -1;
      if (pendingKey != null && trimmed.startsWith("-")) {
        addSetting(file, pendingLine, pendingKey, tool, found); // A list value.
      }
      pendingKey = null;
      Matcher matcher = YAML_KEY.matcher(line);
      if (trimmed.startsWith("-") || !matcher.matches()) {
        continue;
      }
      while (!levels.isEmpty() && levels.peekLast().indent() >= indent) {
        levels.removeLast();
      }
      String key = matcher.group(2).replace("\"", "").replace("'", "");
      List<String> path = new ArrayList<>(levels.stream().map(Level::key).toList());
      path.add(key);
      String full = String.join(".", path);
      String value = matcher.group(3) == null ? "" : matcher.group(3).strip();
      if (value.isEmpty() || value.startsWith("#")) {
        levels.addLast(new Level(indent, key));
        pendingKey = full;
        pendingLine = i + 1;
      } else {
        if (value.startsWith("|") || value.startsWith(">")) {
          blockIndent = indent;
        }
        addSetting(file, i + 1, full, tool, found);
      }
    }
  }

  private static void addSetting(String file, int line, String key, String tool,
                                 List<Leftover> found) {
    String name = key.startsWith("%") && key.indexOf('.') > 0
        ? key.substring(key.indexOf('.') + 1) : key; // Quarkus profile: %prod.quarkus...
    for (String prefix : List.of("spring." + tool, "quarkus." + tool, tool)) {
      boolean matches = name.regionMatches(true, 0, prefix, 0, prefix.length())
          && (name.length() == prefix.length() || name.charAt(prefix.length()) == '.');
      if (matches) {
        String rest = name.length() == prefix.length() ? ""
            : name.substring(prefix.length() + 1).replace("\"", "");
        found.add(new Leftover(file, line, key, action(prefix, rest, tool)));
        return;
      }
    }
  }

  /**
   * What replaces a setting of the old tool. Url, user, password, locations, schemas and
   * placeholders have a Migrax key; for a further datasource (Quarkus {@code
   * quarkus.flyway.<name>.*}, Micronaut {@code flyway.datasources.<name>.*}) it is the key of
   * {@code migrax.databases.<name>}.
   */
  private static String action(String prefix, String rest, String tool) {
    List<String> parts = new ArrayList<>(List.of(rest.split("\\.", -1)));
    String database = null;
    if (prefix.equals(tool) && parts.size() > 2 && lower(parts.get(0)).equals("datasources")) {
      database = parts.get(1);
      parts = parts.subList(2, parts.size());
    } else if (prefix.startsWith("quarkus.") && parts.size() > 1
        && !lower(parts.get(0)).equals("placeholders")) {
      database = parts.get(0);
      parts = parts.subList(1, parts.size());
    }
    if (database != null && lower(database).equals("default")) {
      database = null;
    }
    String base = database == null ? "migrax." : "migrax.databases." + database + ".";
    if (parts.size() == 2 && lower(parts.get(0)).equals("placeholders")) {
      return "use " + base + "placeholders." + parts.get(1) + " instead";
    }
    if (parts.size() != 1) {
      return "remove it";
    }
    String replacement = switch (lower(parts.get(0))) {
      case "url", "jdbc-url" -> "url";
      case "user", "username" -> "user";
      case "password" -> "password";
      case "locations" -> "locations";
      case "schemas", "default-schema", "default-schema-name" -> "schemas";
      default -> null;
    };
    return replacement == null ? "remove it" : "use " + base + replacement + " instead";
  }

  private static String lower(String text) {
    return text.toLowerCase(Locale.ROOT);
  }
}
