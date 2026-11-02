package org.migrax.dialect;

import org.migrax.ops.Operation;

/**
 * Strategy interface for rendering schema operations into database-specific DDL.
 *
 * <p>Implementations are found with {@link java.util.ServiceLoader}: list the class in
 * {@code META-INF/services/org.migrax.dialect.Dialect} and give it a public no-argument
 * constructor. {@link Dialects} then picks it by name or JDBC URL.
 *
 * @since 0.1.0
 */
public interface Dialect {

  /**
   * Returns the unique identifier for this dialect (e.g., {@code "postgresql"}, {@code "mysql"}).
   *
   * @return the dialect identifier
   * @since 0.1.0
   */
  String id();

  /**
   * Other names accepted for this dialect by {@code --dialect}, for example {@code pg}.
   *
   * @return alternative names, lower case
   * @since 0.1.0
   */
  default java.util.List<String> aliases() {
    return java.util.List.of();
  }

  /**
   * Whether this dialect is the one for a JDBC URL. The default accepts URLs that start with
   * {@code jdbc:<id>:}.
   *
   * @param jdbcUrl JDBC URL, in lower case
   * @return {@code true} if this dialect writes SQL for that database
   * @since 0.1.0
   */
  default boolean acceptsUrl(String jdbcUrl) {
    return jdbcUrl.startsWith("jdbc:" + id() + ":");
  }

  /**
   * Whether this dialect is the one for a connection, by the database product name its driver
   * reports. The default accepts names that contain {@link #id()}.
   *
   * @param productName {@link java.sql.DatabaseMetaData#getDatabaseProductName()}, in lower case
   * @return {@code true} if this dialect handles that database
   * @since 0.1.0
   */
  default boolean acceptsProduct(String productName) {
    return productName.contains(id());
  }

  /**
   * Whether this dialect is the one for an open connection. The default checks the product
   * name ({@link #acceptsProduct}); a database that reports another product's name, such as
   * CockroachDB with the PostgreSQL driver, can look at the server itself.
   *
   * @param connection an open connection to the database
   * @return {@code true} if this dialect handles that database
   * @throws java.sql.SQLException when the connection's metadata can't be read
   * @since 0.2.0
   */
  default boolean acceptsConnection(java.sql.Connection connection) throws java.sql.SQLException {
    return acceptsProduct(connection.getMetaData().getDatabaseProductName()
        .toLowerCase(java.util.Locale.ROOT));
  }

  /**
   * Takes the database-wide lock that keeps two Migrax processes from migrating the same
   * database at once. It must not wait: when another session holds it, fail at once.
   * Databases without such a lock can't be migrated safely, so the default refuses.
   *
   * @param connection the connection that migrates; the lock belongs to its session
   * @param resource a name for the lock that is unique to the database, such as
   *     {@code org.migrax:3f2a...}
   * @return releases the lock when closed
   * @throws java.sql.SQLException when another process holds the lock, or locking failed
   * @since 0.1.0
   */
  default AutoCloseable acquireMigrationLock(java.sql.Connection connection, String resource)
      throws java.sql.SQLException {
    throw new java.sql.SQLException("Concurrent migration locking is not implemented for "
        + "database " + connection.getMetaData().getDatabaseProductName()
        + "; refusing to proceed.");
  }

  /**
   * Quotes a SQL identifier according to the database rules.
   *
   * @param identifier the identifier to quote
   * @return quoted identifier
   * @since 0.1.0
   */
  String quote(String identifier);

  /**
   * An identifier as this dialect writes it in generated SQL: quoted only when it has to be,
   * so it names the same object as the dialect's other statements.
   *
   * @param identifier the identifier
   * @return the identifier as written in SQL
   * @since 0.6.0
   */
  default String identifier(String identifier) {
    return identifier;
  }

  /** What a database does with the letter case of an unquoted name. */
  enum NameCase {
    /** Makes it upper case: {@code customers} is stored as {@code CUSTOMERS}. */
    UPPER,
    /** Makes it lower case. */
    LOWER,
    /** Keeps it as written (or compares names without case). */
    KEPT
  }

  /**
   * What this database does with the letter case of unquoted names. A reader whose library
   * quotes every name (Spring Data JDBC) needs it to know which names keep their case.
   *
   * @since 0.17.0
   */
  default NameCase unquotedNames() {
    return NameCase.KEPT;
  }

  /**
   * A table's or sequence's name as this dialect writes it in generated SQL: the identifier,
   * with the schema in front when the dialect is bound to one.
   *
   * @param table the table or sequence name
   * @return the name as written in SQL
   * @since 0.7.0
   */
  default String tableName(String table) {
    return identifier(table);
  }

  /**
   * The statement that copies columns of a table into a new table, rows included.
   *
   * @param target the new table
   * @param source the table to copy from
   * @param columns the columns to copy
   * @return SQL statement
   * @since 0.6.0
   */
  default String copyTable(String target, String source, java.util.List<String> columns) {
    return "CREATE TABLE " + tableName(target) + " AS SELECT "
        + String.join(", ", columns.stream().map(this::identifier).toList()) + " FROM "
        + tableName(source);
  }

  /**
   * A query for one value from the first matching row, for use as a scalar subquery.
   *
   * @param value the value to select, such as {@code b.note}
   * @param from the table to read, with its alias
   * @param condition the WHERE condition
   * @return the SELECT statement, without parentheses
   * @since 0.7.0
   */
  default String firstValue(String value, String from, String condition) {
    return "SELECT " + value + " FROM " + from + " WHERE " + condition + " LIMIT 1";
  }

  /**
   * Renders the given schema operation into a database-specific DDL statement.
   *
   * @param operation the operation to render
   * @return SQL DDL string
   * @throws IllegalArgumentException if the operation is unsupported by this dialect
   * @since 0.1.0
   */
  String render(Operation operation);

  /**
   * Rewrites a list of operations into what this database can run, before they are written to
   * a migration file. The default returns them unchanged; SQLite replaces changes it can't make
   * in place with table rebuilds.
   *
   * @param operations the operations, in order
   * @param before the schema before them (renames in {@code operations} not yet applied)
   * @param after the schema after them
   * @return the operations to render
   * @since 0.2.0
   */
  default java.util.List<Operation> prepare(java.util.List<Operation> operations,
                                            org.migrax.model.SchemaModel before,
                                            org.migrax.model.SchemaModel after) {
    return operations;
  }

  /**
   * The statement that renames a foreign key in place, or null when this database can't (the
   * key is then dropped and added again under its new name).
   *
   * @param table the table that has the key
   * @param from the key's current name
   * @param to its new name
   * @return the statement without a trailing semicolon, or null
   * @since 0.17.1
   */
  default String renameForeignKey(String table, String from, String to) {
    return null;
  }

  /**
   * The SQL type this dialect writes for a column, without identity or constraints.
   *
   * @param column the column
   * @return SQL type such as {@code varchar(255)}
   * @since 0.1.0
   */
  default String columnType(org.migrax.model.SchemaModel.Column column) {
    throw new UnsupportedOperationException("columnType");
  }

  /**
   * Indicates whether this database engine executes DDL statements inside transactions.
   *
   * @return {@code true} if transactional DDL is supported; {@code false} otherwise
   * @since 0.1.0
   */
  default boolean transactionalDdl() {
    return true;
  }

  /**
   * Whether the SQL this dialect writes depends on the server's version, so that Migrax asks
   * the server when it can reach it. The default is {@code false}.
   *
   * @return {@code true} to have {@link #forServer} called with the server's version
   * @since 0.9.0
   */
  default boolean dependsOnServerVersion() {
    return false;
  }

  /**
   * This dialect for a server of the given version. The default returns this dialect.
   *
   * @param major the server's major version
   * @param minor the server's minor version
   * @return the dialect to use for that server
   * @since 0.9.0
   */
  default Dialect forServer(int major, int minor) {
    return this;
  }

  /**
   * Hibernate settings that tell the project's Hibernate the server version, so that its mapping
   * has the column types it uses on that server. Empty unless the version is known.
   *
   * @return the settings, possibly empty
   * @since 0.9.0
   */
  default java.util.Map<String, String> serverSettings() {
    return java.util.Map.of();
  }

  /**
   * The server version this dialect writes SQL for, as {@code major.minor}, recorded in the
   * snapshot so that commands without a database connection write the same SQL. Null unless
   * the SQL depends on it and the version is known.
   *
   * @return the version, or null
   * @since 0.9.0
   */
  default String serverVersion() {
    return null;
  }

  /**
   * The Docker image for {@code verify}'s throwaway database of this server, or null for the
   * default one.
   *
   * @return an image name, or null
   * @since 0.9.0
   */
  default String scratchImage() {
    return null;
  }

  /**
   * Statements that keep two columns of a table equal while both exist, for a column renamed
   * with {@code generate --zero-downtime}: the old application version writes the old column,
   * the new version the new one, and the other column gets the value.
   *
   * @param table the table
   * @param oldColumn the column the old version uses
   * @param newColumn the column the new version uses
   * @return the statements, or an empty list when this database can't keep them equal
   * @since 0.11.0
   */
  default java.util.List<String> syncColumns(String table, String oldColumn, String newColumn) {
    return java.util.List.of();
  }

  /**
   * Statements that remove what {@link #syncColumns} created.
   *
   * @since 0.11.0
   */
  default java.util.List<String> dropSyncColumns(String table, String oldColumn,
                                                 String newColumn) {
    return java.util.List.of();
  }

  /**
   * Whether a view that selects some columns of one table accepts inserts, updates and
   * deletes. {@code generate --zero-downtime} keeps a renamed table's old name as such a view
   * while the old application version still runs.
   *
   * @since 0.11.0
   */
  default boolean updatableViews() {
    return false;
  }

  /**
   * Whether a backslash escapes the next character inside string literals, so that
   * {@code 'it\'s'} is one string. Splitting a SQL script into statements needs to know. The
   * default is {@code false}, as in standard SQL.
   *
   * @since 0.19.0
   */
  default boolean backslashEscapes() {
    return false;
  }

  /**
   * Whether this database's dump files are psql scripts, with psql's own backslash commands
   * between the SQL statements. The default is {@code false}.
   *
   * @since 0.19.0
   */
  default boolean dumpsHavePsqlCommands() {
    return false;
  }

  /**
   * Whether the database keeps table and column comments, so that they can be read back. The
   * default is {@code true}.
   *
   * @since 0.19.0
   */
  default boolean keepsComments() {
    return true;
  }

  /**
   * Whether the database has schemas a connection can be pointed at. The default is
   * {@code true}.
   *
   * @since 0.19.0
   */
  default boolean hasSchemas() {
    return true;
  }

  /**
   * Whether a connection can switch its default schema. When it can't, SQL that runs once per
   * schema names the schema in every table: see {@link #inSchema(String)}. The default is
   * {@code true}.
   *
   * @since 0.19.0
   */
  default boolean switchesSchemas() {
    return true;
  }

  /**
   * This dialect, writing the schema in front of every table and sequence name, for databases
   * whose connections can't switch schemas ({@link #switchesSchemas()}). The default returns
   * this dialect, since the connection switches the schema instead.
   *
   * @param schema a schema name, or the placeholder {@code ${migrax.schema}}
   * @since 0.19.0
   */
  default Dialect inSchema(String schema) {
    return this;
  }

  /**
   * Whether the JDBC driver for this dialect's URLs also reaches other databases (for example
   * ones that speak the same protocol), so that the server has to confirm which one it is. The
   * default is {@code false}.
   *
   * @since 0.19.0
   */
  default boolean urlReachesOtherDatabases() {
    return false;
  }

  /**
   * Whether a project's {@code hibernate.dialect} setting names this database, which tells it
   * apart from others that share its JDBC driver. The default is {@code false}: the URL or the
   * server decides.
   *
   * @param hibernateDialect the setting's value, a Hibernate dialect class name
   * @since 0.19.0
   */
  default boolean acceptsHibernateDialect(String hibernateDialect) {
    return false;
  }

  /**
   * The Hibernate dialect classes Migrax sets when it reads the entities with Hibernate, best
   * first: the first one Hibernate can load is used, else the last one (Hibernate 4: else
   * the project has to set {@code hibernate.dialect}). Empty leaves the project's own
   * {@code hibernate.dialect} setting in charge. The default is empty.
   *
   * @param hibernateMajor Hibernate's major version: 4 and 5 select the names for those
   *     versions, any other version the ones for Hibernate 6 and newer
   * @since 0.19.0
   */
  default java.util.List<String> hibernateDialects(int hibernateMajor) {
    return java.util.List.of();
  }

  /**
   * Whether the Hibernate dialect from {@link #hibernateDialects(int)} keeps sequences as
   * one-column {@code next_val} tables, because the database has no sequences. The default is
   * {@code false}.
   *
   * @since 0.19.0
   */
  default boolean hibernateSequencesAreTables() {
    return false;
  }

  /**
   * Whether Hibernate's own schema validation works on this database, so that
   * {@code migrax verify} can use it. The default is {@code true}.
   *
   * @since 0.19.0
   */
  default boolean hibernateValidationWorks() {
    return true;
  }

  /**
   * The EclipseLink platform class Migrax sets when it reads the entities with EclipseLink,
   * for the database's default column types. The default is EclipseLink's generic
   * {@code DatabasePlatform}.
   *
   * @since 0.19.0
   */
  default String eclipseLinkPlatform() {
    return "org.eclipse.persistence.platform.database.DatabasePlatform";
  }

  /**
   * The OpenJPA dictionary (a short name or a class name) Migrax sets when it reads the
   * entities with OpenJPA. The default is OpenJPA's generic {@code DBDictionary}.
   *
   * @since 0.19.0
   */
  default String openJpaDictionary() {
    return "org.apache.openjpa.jdbc.sql.DBDictionary";
  }

  /**
   * Whether table names are case-sensitive (as on MySQL on Linux), so that the entity readers
   * keep a table name as the ORM spells it instead of writing it in lower case. The default is
   * {@code false}.
   *
   * @since 0.19.0
   */
  default boolean caseSensitiveTableNames() {
    return false;
  }

  /**
   * Whether large objects (serialized values, byte and char arrays) read with EclipseLink or
   * OpenJPA get the ORM's column type, because Migrax's own large-object type doesn't fit what
   * the ORM writes (the PostgreSQL family's oid, the MySQL family's 64 KB blob). The default
   * is {@code false}.
   *
   * @since 0.19.0
   */
  default boolean largeObjectTypesFromOrm() {
    return false;
  }

  /**
   * The column type for a byte array that a data mapper (Spring Data JDBC, Micronaut Data)
   * writes as plain bytes, where it differs from Migrax's large-object type; empty when that
   * type fits. The default is empty.
   *
   * @since 0.19.0
   */
  default java.util.Optional<String> plainBytesType() {
    return java.util.Optional.empty();
  }

  /**
   * Whether Micronaut Data draws ids with {@code GeneratedValue.Type.AUTO} from a sequence on
   * this database instead of an identity column. The default is {@code false}.
   *
   * @since 0.19.0
   */
  default boolean micronautDataSequenceIds() {
    return false;
  }

  /**
   * Whether Micronaut Data stores UUIDs as text ({@code VARCHAR(36)}) on this database. Where
   * it does and no {@link #micronautDataUuidDefault()} exists, it puts a new UUID into the
   * insert itself. The default is {@code false}.
   *
   * @since 0.19.0
   */
  default boolean micronautDataTextUuids() {
    return false;
  }

  /**
   * The database function Micronaut Data relies on for a generated UUID column's default;
   * empty when there is none. The default is empty.
   *
   * @since 0.19.0
   */
  default java.util.Optional<String> micronautDataUuidDefault() {
    return java.util.Optional.empty();
  }

  /**
   * How {@code migrax doctor} names the server it connected to. The default is the product
   * name and version the driver reports.
   *
   * @param product the driver's database product name
   * @param version the driver's database product version
   * @since 0.19.0
   */
  default String describeServer(String product, String version) {
    return product + " " + version;
  }

  /**
   * The warning {@code migrax doctor} shows when the server accepts the entities' check
   * constraints but doesn't enforce them; empty when it enforces them. The default is empty.
   *
   * @param connection an open connection to the server
   * @throws java.sql.SQLException when the connection's metadata can't be read
   * @since 0.19.0
   */
  default java.util.Optional<String> unenforcedChecks(java.sql.Connection connection)
      throws java.sql.SQLException {
    return java.util.Optional.empty();
  }

  /**
   * What the migration lock from {@link #acquireMigrationLock} can't do, for
   * {@code migrax doctor}; empty when it protects every process. The default is empty.
   *
   * @since 0.19.0
   */
  default java.util.Optional<String> migrationLockLimit() {
    return java.util.Optional.empty();
  }

  /**
   * The query that estimates a table's size from the database catalog without scanning it.
   * Its one parameter is {@link #tableSizeName(String)}; its two columns are the estimated rows
   * and the bytes of the table and its indexes, each NULL when unknown. Empty when the catalog
   * has no estimate. The default is empty.
   *
   * @since 0.19.0
   */
  default java.util.Optional<String> tableSizeQuery() {
    return java.util.Optional.empty();
  }

  /**
   * A table's name as the parameter of {@link #tableSizeQuery()}. The default returns it
   * unchanged.
   *
   * @since 0.19.0
   */
  default String tableSizeName(String table) {
    return table;
  }

  /**
   * Whether counting a table's rows is the way to learn its size, for small embedded databases
   * without a {@link #tableSizeQuery()}. The default is {@code false}.
   *
   * @since 0.19.0
   */
  default boolean countsRowsForTableSize() {
    return false;
  }

  /**
   * Whether the database can drop a foreign key on its own. A dialect that returns
   * {@code false} rewrites foreign key drops in {@link #prepare} (SQLite rebuilds the table
   * from the model), and callers that drop tables skip the foreign keys of those tables. The
   * default is {@code true}.
   *
   * @since 0.19.0
   */
  default boolean dropsForeignKeys() {
    return true;
  }

  /**
   * Whether a schema is a database of its own on this server, so that a connection points at
   * one by switching its catalog. The default is {@code false}.
   *
   * @since 0.19.0
   */
  default boolean schemasAreDatabases() {
    return false;
  }

  /**
   * Whether {@link #renderOnline} gives operations on existing tables a form that doesn't
   * block writes, for {@code migrax generate --safe}. The default is {@code false}.
   *
   * @since 0.19.0
   */
  default boolean rendersOnline() {
    return false;
  }

  /**
   * An operation on an existing table in a form that doesn't block writes, for a migration
   * marked {@code -- migrax:no-transaction}; see {@link #rendersOnline()}. The default is
   * {@link #render}.
   *
   * @since 0.19.0
   */
  default String renderOnline(Operation operation) {
    return render(operation);
  }

  /**
   * Whether the database builds indexes without blocking writes anyway, so that
   * {@code migrax generate --safe} has nothing to do. The default is {@code false}.
   *
   * @since 0.19.0
   */
  default boolean buildsIndexesWithoutBlocking() {
    return false;
  }

  /**
   * Why a {@code --keep-data} rollback that restores values has to commit each statement on
   * its own on this database, as SQL comment lines that end with a line break; empty when the
   * rollback runs in one transaction. The caller adds the {@code -- migrax:no-transaction}
   * line after the note. The default is empty.
   *
   * @since 0.19.0
   */
  default java.util.Optional<String> keepDataRestoreNote() {
    return java.util.Optional.empty();
  }

  /**
   * The name of the trigger that {@link #syncColumns} creates: {@code migrax_sync_<table>_<new
   * column>}, or, when that is longer than {@code maxLength}, the start of the table name and a
   * hash.
   *
   * @param maxLength the database's limit for the name (30 on Oracle before 12.2)
   * @since 0.11.0
   */
  static String syncTriggerName(String table, String newColumn, int maxLength) {
    String name = "migrax_sync_" + table + "_" + newColumn;
    if (name.length() <= maxLength) {
      return name.toLowerCase(java.util.Locale.ROOT);
    }
    String hash = String.format(java.util.Locale.ROOT, "%08x",
        (table + "." + newColumn).toLowerCase(java.util.Locale.ROOT).hashCode());
    int room = maxLength - "migrax_sync_".length() - hash.length() - 1;
    String start = table.length() > room ? table.substring(0, room) : table;
    return ("migrax_sync_" + start + "_" + hash).toLowerCase(java.util.Locale.ROOT);
  }
}
