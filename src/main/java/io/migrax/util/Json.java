package io.migrax.util;

import io.migrax.model.SchemaModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small dependency-free JSON codec for the neutral schema snapshot. */
public final class Json {
  private static final String NL = "\n";

  private Json() {}

  public static String write(SchemaModel m) {
    return write(m, null);
  }

  /**
   * Writes the snapshot with one table, column, index and key per line, so git can merge
   * changes to different tables or columns from two branches.
   *
   * @param dialect dialect the snapshot was generated for, or null
   */
  public static String write(SchemaModel m, String dialect) {
    return write(m, dialect, null);
  }

  /**
   * @param dialect dialect the snapshot was generated for, or null
   * @param naming naming strategy id the snapshot was generated with, or null
   */
  public static String write(SchemaModel m, String dialect, String naming) {
    StringBuilder b = new StringBuilder("{").append(NL);
    if (dialect != null) {
      b.append("  \"dialect\": ").append(q(dialect)).append(",").append(NL);
    }
    if (naming != null) {
      b.append("  \"naming\": ").append(q(naming)).append(",").append(NL);
    }
    b.append("  \"tables\": [").append(NL);
    for (int i = 0; i < m.tables().size(); i++) {
      var t = m.tables().get(i);
      b.append("    {\"name\": ").append(q(t.name())).append(",").append(NL);
      b.append("      \"columns\": [").append(NL);
      for (int j = 0; j < t.columns().size(); j++) {
        var c = t.columns().get(j);
        b.append("        {\"name\":").append(q(c.name()))
            .append(",\"sqlType\":").append(q(c.sqlType()))
            .append(",\"nullable\":").append(c.nullable())
            .append(",\"length\":").append(n(c.length()))
            .append(",\"precision\":").append(n(c.precision()))
            .append(",\"scale\":").append(n(c.scale()))
            .append(",\"defaultValue\":").append(qn(c.defaultValue()))
            .append(",\"unique\":").append(c.unique())
            .append(",\"identity\":").append(c.identity())
            .append(",\"sequenceName\":").append(qn(c.sequenceName()))
            .append(",\"logicalType\":").append(q(c.logicalType()))
            .append("}").append(j + 1 < t.columns().size() ? "," : "").append(NL);
      }
      b.append("      ],").append(NL);
      b.append("      \"primaryKey\": ").append(pk(t.primaryKey())).append(",").append(NL);
      b.append("      \"indexes\": [");
      for (int j = 0; j < t.indexes().size(); j++) {
        var x = t.indexes().get(j);
        b.append(NL).append("        {\"name\":").append(q(x.name()))
            .append(",\"columns\":").append(arr(x.columns()))
            .append(",\"unique\":").append(x.unique())
            .append('}').append(j + 1 < t.indexes().size() ? "," : "");
      }
      b.append(t.indexes().isEmpty() ? "" : NL + "      ").append("],").append(NL);
      b.append("      \"foreignKeys\": [");
      for (int j = 0; j < t.foreignKeys().size(); j++) {
        var x = t.foreignKeys().get(j);
        b.append(NL).append("        {\"name\":").append(q(x.name()))
            .append(",\"columns\":").append(arr(x.columns()))
            .append(",\"referencedTable\":").append(q(x.referencedTable()))
            .append(",\"referencedColumns\":").append(arr(x.referencedColumns()))
            .append('}').append(j + 1 < t.foreignKeys().size() ? "," : "");
      }
      b.append(t.foreignKeys().isEmpty() ? "" : NL + "      ").append("]").append(NL);
      b.append("    }").append(i + 1 < m.tables().size() ? "," : "").append(NL);
    }
    b.append("  ],").append(NL).append("  \"sequences\": [");
    for (int i = 0; i < m.sequences().size(); i++) {
      var s = m.sequences().get(i);
      b.append(NL).append("    {\"name\":").append(q(s.name()))
          .append(",\"initialValue\":").append(s.initialValue())
          .append(",\"allocationSize\":").append(s.allocationSize())
          .append('}').append(i + 1 < m.sequences().size() ? "," : "");
    }
    b.append(m.sequences().isEmpty() ? "" : NL + "  ").append("]").append(NL);
    return b.append("}").append(NL).toString();
  }

  /** The dialect recorded in a snapshot, or null. */
  public static String dialect(String json) {
    return rootString(json, "dialect");
  }

  /** A top-level string field of a snapshot, such as "dialect" or "naming", or null. */
  public static String rootString(String json, String key) {
    Object root = new Parser(json).parse();
    return root instanceof Map<?, ?> map && map.get(key) instanceof String d ? d : null;
  }

  /** Quotes a string as a JSON string literal. */
  public static String q(String s) {
    if (s == null) return "null";
    StringBuilder b = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> b.append("\\\\");
        case '"' -> b.append("\\\"");
        case '\r' -> b.append("\\r");
        case '\n' -> b.append("\\n");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.append('"').toString();
  }

  private static String qn(String s) {
    return s == null ? "null" : q(s);
  }

  private static String n(Number x) {
    return x == null ? "null" : x.toString();
  }

  private static String arr(List<String> x) {
    StringBuilder b = new StringBuilder("[");
    for (int i = 0; i < x.size(); i++) {
      if (i > 0) b.append(',');
      b.append(q(x.get(i)));
    }
    return b.append(']').toString();
  }

  private static String pk(SchemaModel.PrimaryKey p) {
    return p == null
        ? "null"
        : "{\"columns\":" + arr(p.columns()) + ",\"constraintName\":" + qn(p.constraintName()) + "}";
  }

  public static void write(Path p, SchemaModel m) throws Exception {
    write(p, m, null);
  }

  public static void write(Path p, SchemaModel m, String dialect) throws Exception {
    write(p, m, dialect, null);
  }

  public static void write(Path p, SchemaModel m, String dialect, String naming)
      throws Exception {
    Files.createDirectories(p.getParent());
    Files.writeString(p, write(m, dialect, naming));
  }

  public static SchemaModel read(Path p) throws Exception {
    return parse(Files.readString(p));
  }

  public static SchemaModel parse(String s) {
    Object v = new Parser(s).parse();
    if (!(v instanceof Map<?, ?> root)) {
      throw new IllegalArgumentException("Expected root JSON object in schema snapshot");
    }
    List<SchemaModel.Table> ts = new ArrayList<>();
    for (Object to : list(root, "tables")) {
      Map<?, ?> t = (Map<?, ?>) to;
      List<SchemaModel.Column> cs = new ArrayList<>();
      for (Object co : list(t, "columns")) {
        Map<?, ?> c = (Map<?, ?>) co;
        cs.add(new SchemaModel.Column(
            str(c, "name"),
            str(c, "sqlType"),
            bool(c, "nullable"),
            integer(c, "length"),
            integer(c, "precision"),
            integer(c, "scale"),
            nullableStr(c, "defaultValue"),
            bool(c, "unique"),
            bool(c, "identity"),
            nullableStr(c, "sequenceName"),
            strOr(c, "logicalType", str(c, "sqlType"))));
      }
      SchemaModel.PrimaryKey pk = null;
      if (t.get("primaryKey") instanceof Map<?, ?> pkm) {
        pk = new SchemaModel.PrimaryKey(
            strings((List<?>) pkm.get("columns")),
            nullableStr(pkm, "constraintName"));
      }
      List<SchemaModel.Index> ix = new ArrayList<>();
      for (Object io : list(t, "indexes")) {
        Map<?, ?> x = (Map<?, ?>) io;
        ix.add(new SchemaModel.Index(
            str(x, "name"),
            strings((List<?>) x.get("columns")),
            bool(x, "unique")));
      }
      List<SchemaModel.ForeignKey> fk = new ArrayList<>();
      for (Object fo : list(t, "foreignKeys")) {
        Map<?, ?> x = (Map<?, ?>) fo;
        fk.add(new SchemaModel.ForeignKey(
            str(x, "name"),
            strings((List<?>) x.get("columns")),
            str(x, "referencedTable"),
            strings((List<?>) x.get("referencedColumns"))));
      }
      ts.add(new SchemaModel.Table(str(t, "name"), cs, pk, ix, fk));
    }
    List<SchemaModel.Sequence> ss = new ArrayList<>();
    for (Object so : list(root, "sequences")) {
      Map<?, ?> x = (Map<?, ?>) so;
      Object iv = x.get("initialValue");
      Object av = x.get("allocationSize");
      ss.add(new SchemaModel.Sequence(
          str(x, "name"),
          iv instanceof Number ? ((Number) iv).longValue() : 1,
          av instanceof Number ? ((Number) av).longValue() : 50));
    }
    return new SchemaModel(ts, ss);
  }

  static List<?> list(Map<?, ?> m, String k) {
    Object x = m.get(k);
    return x instanceof List ? (List<?>) x : List.of();
  }

  static String str(Map<?, ?> m, String k) {
    Object x = m.get(k);
    return x == null ? "" : String.valueOf(x);
  }

  static String strOr(Map<?, ?> m, String k, String d) {
    Object x = m.get(k);
    return x == null ? d : String.valueOf(x);
  }

  static String nullableStr(Map<?, ?> m, String k) {
    Object x = m.get(k);
    return x == null ? null : String.valueOf(x);
  }

  static boolean bool(Map<?, ?> m, String k) {
    Object x = m.get(k);
    return Boolean.TRUE.equals(x);
  }

  static Integer integer(Map<?, ?> m, String k) {
    Object x = m.get(k);
    return x instanceof Number ? ((Number) x).intValue() : null;
  }

  static List<String> strings(List<?> x) {
    if (x == null) return List.of();
    List<String> r = new ArrayList<>();
    for (Object o : x) r.add(String.valueOf(o));
    return r;
  }

  public static final class Parser {
    private final String s;
    private int i;

    public Parser(String s) {
      this.s = s == null ? "" : s;
    }

    public Object parse() {
      skip();
      if (i >= s.length()) {
        throw new IllegalArgumentException("Empty or blank JSON input");
      }
      Object result = value();
      skip();
      if (i < s.length()) {
        throw new IllegalArgumentException(
            "Unexpected trailing characters at position " + i + ": " + s.substring(i));
      }
      return result;
    }

    private char peek() {
      if (i >= s.length()) {
        throw new IllegalArgumentException("Unexpected end of JSON input at position " + i);
      }
      return s.charAt(i);
    }

    private Object value() {
      skip();
      char c = peek();
      if (c == '{') return obj();
      if (c == '[') return arr();
      if (c == '"') return str();
      if (s.startsWith("true", i)) {
        i += 4;
        return true;
      }
      if (s.startsWith("false", i)) {
        i += 5;
        return false;
      }
      if (s.startsWith("null", i)) {
        i += 4;
        return null;
      }
      int st = i;
      while (i < s.length() && "-0123456789.eE+".indexOf(s.charAt(i)) >= 0) {
        i++;
      }
      if (st == i) {
        throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + i);
      }
      String n = s.substring(st, i);
      if (n.contains(".") || n.contains("e") || n.contains("E")) {
        return Double.parseDouble(n);
      }
      return Long.parseLong(n);
    }

    private Map<String, Object> obj() {
      Map<String, Object> m = new LinkedHashMap<>();
      i++; // skip '{'
      skip();
      if (peek() == '}') {
        i++;
        return m;
      }
      while (true) {
        String k = str();
        skip();
        if (peek() != ':') {
          throw new IllegalArgumentException("Expected ':' after key at position " + i);
        }
        i++; // skip ':'
        m.put(k, value());
        skip();
        char next = peek();
        if (next == '}') {
          i++;
          return m;
        }
        if (next != ',') {
          throw new IllegalArgumentException("Expected ',' or '}' at position " + i);
        }
        i++; // skip ','
        skip();
      }
    }

    private List<Object> arr() {
      List<Object> l = new ArrayList<>();
      i++; // skip '['
      skip();
      if (peek() == ']') {
        i++;
        return l;
      }
      while (true) {
        l.add(value());
        skip();
        char next = peek();
        if (next == ']') {
          i++;
          return l;
        }
        if (next != ',') {
          throw new IllegalArgumentException("Expected ',' or ']' at position " + i);
        }
        i++; // skip ','
        skip();
      }
    }

    private String str() {
      if (peek() != '"') {
        throw new IllegalArgumentException("Expected '\"' at position " + i);
      }
      i++;
      StringBuilder b = new StringBuilder();
      while (i < s.length()) {
        char c = s.charAt(i++);
        if (c == '"') {
          return b.toString();
        }
        if (c == '\\') {
          if (i >= s.length()) {
            throw new IllegalArgumentException(
                "Unterminated escape sequence in string at position " + i);
          }
          char n = s.charAt(i++);
          switch (n) {
            case 'n' -> b.append('\n');
            case 'r' -> b.append('\r');
            case 't' -> b.append('\t');
            case '\\' -> b.append('\\');
            case '"' -> b.append('"');
            case '/' -> b.append('/');
            case 'b' -> b.append('\b');
            case 'f' -> b.append('\f');
            case 'u' -> {
              if (i + 4 > s.length()) {
                throw new IllegalArgumentException("Incomplete unicode escape at position " + i);
              }
              String hex = s.substring(i, i + 4);
              b.append((char) Integer.parseInt(hex, 16));
              i += 4;
            }
            default -> b.append(n);
          }
        } else {
          b.append(c);
        }
      }
      throw new IllegalArgumentException("Unterminated string literal at end of input");
    }

    private void skip() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
    }
  }
}
