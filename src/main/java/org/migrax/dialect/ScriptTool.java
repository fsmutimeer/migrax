package org.migrax.dialect;

/**
 * How a migration script is written for a database's own command line tool, so a DBA can run it
 * instead of {@code migrax migrate} ({@code migrate --sql}). Each method returns the text for
 * one part of the script; the defaults write plain SQL that most tools accept.
 *
 * @see Dialect#scriptTool()
 * @since 0.19.0
 */
public interface ScriptTool {

  /** A tool that runs plain SQL statements ended with a semicolon. */
  ScriptTool GENERIC = new ScriptTool() {};

  /**
   * The command that runs the script and stops at the first error, for the script's header.
   * The default names no tool.
   */
  default String runCommand() {
    return "your database's SQL tool, stopping at the first error";
  }

  /**
   * Whether each migration runs in a transaction that the script opens and commits. Databases
   * that commit DDL statements at once return {@code false}; the script then warns that a
   * failed migration stays half applied. The default is {@code true}.
   */
  default boolean transactions() {
    return true;
  }

  /** The text that opens a migration's transaction. The default is {@code BEGIN;}. */
  default String begin() {
    return "BEGIN;\n";
  }

  /** The text that commits a migration's transaction. The default is {@code COMMIT;}. */
  default String commit() {
    return "COMMIT;\n";
  }

  /** The text before the first statement, such as the tool's error setting. Default empty. */
  default String start() {
    return "";
  }

  /** The text after the last statement, such as the command that ends the tool. Default empty. */
  default String end() {
    return "";
  }

  /** The text after each recorded migration, such as a commit. The default is empty. */
  default String afterEachMigration() {
    return "";
  }

  /**
   * The statement that makes {@code schema} the default for the statements after it. The
   * default sets PostgreSQL's {@code search_path}.
   */
  default String useSchema(String schema) {
    return "SET search_path TO " + schema;
  }

  /**
   * A statement with the end marker the tool expects, followed by a line break. The default
   * ends it with a semicolon unless it has one.
   *
   * @param statement one SQL statement
   * @param procedural whether the statement is a procedural block (a procedure, function,
   *     package, trigger or type, or a block starting with {@code DECLARE} or {@code BEGIN})
   */
  default String terminate(String statement, boolean procedural) {
    return statement.endsWith(";") ? statement + "\n" : statement + ";\n";
  }
}
