package org.migrax.verify;

import org.migrax.plugin.RuntimeJdbc;
import org.migrax.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A throwaway database for {@code migrax verify}: in-memory H2, a temporary SQLite file, or a
 * Docker container of the project's database engine, removed again on {@link #close()}.
 *
 * @since 0.1.0
 */
public final class ScratchDatabase implements AutoCloseable {
  private final String url;
  private final String user;
  private final String password;
  private final String containerId;
  /** SQLite's database file, deleted on close; null for other databases. */
  private Path file;

  private ScratchDatabase(String url, String user, String password, String containerId) {
    this.url = url;
    this.user = user;
    this.password = password;
    this.containerId = containerId;
  }

  public String url() {
    return url;
  }

  public String user() {
    return user;
  }

  public String password() {
    return password;
  }

  /** An existing database the user provided; it is not removed on close. */
  public static ScratchDatabase existing(String url, String user, String password) {
    return new ScratchDatabase(url, user, password, null);
  }

  /**
   * The image of a server's own version, for SQL that only that version (or newer) runs, such
   * as the settings pg_dump writes; null when there is no such image.
   *
   * @param dialect the dialect's id
   * @param major the server's major version
   * @param minor the server's minor version
   * @return an image name, or null
   * @since 0.17.0
   */
  public static String imageFor(String dialect, int major, int minor) {
    return switch (dialect) {
      case "postgresql" -> major >= 12 ? "postgres:" + major + "-alpine" : null;
      case "mysql" -> major >= 8 ? "mysql:" + major + "." + minor : null;
      case "mariadb" -> major >= 10 ? "mariadb:" + major + "." + minor : null;
      default -> null;
    };
  }

  /**
   * Starts a scratch database for the dialect.
   *
   * @param image Docker image to use instead of the default, or null
   * @param loader class loader with the JDBC driver, used to wait until the database accepts
   *     connections
   */
  public static ScratchDatabase start(String dialect, String image, ClassLoader loader)
      throws Exception {
    if ("sqlite".equals(dialect)) {
      Path file = Files.createTempFile("migrax-verify-", ".db");
      ScratchDatabase database = new ScratchDatabase("jdbc:sqlite:" + file, "", "", null);
      database.file = file;
      return database;
    }
    if ("h2".equals(dialect)) {
      return new ScratchDatabase("jdbc:h2:mem:migrax_verify_" + UUID.randomUUID()
          .toString().replace("-", "") + ";DB_CLOSE_DELAY=-1", "sa", "", null);
    }
    if ("derby".equals(dialect)) {
      return new ScratchDatabase("jdbc:derby:memory:migrax_verify_" + UUID.randomUUID()
          .toString().replace("-", "") + ";create=true", "", "", null);
    }
    if ("hsqldb".equals(dialect)) {
      return new ScratchDatabase("jdbc:hsqldb:mem:migrax_verify_" + UUID.randomUUID()
          .toString().replace("-", ""), "SA", "", null);
    }
    Engine engine = Engine.of(dialect);
    String chosenImage = image == null || image.isBlank() ? engine.image : image;
    if (!dockerAvailable()) {
      throw new IllegalStateException("Docker is not available, so Migrax cannot start a "
          + "throwaway " + dialect + " database. Start Docker, or pass --url with an empty "
          + "scratch database.");
    }
    Log.info("Starting a throwaway {} database ({})...", dialect, chosenImage);
    // The port by number: the Oracle images don't declare it, so -P would publish nothing.
    List<String> command = new ArrayList<>(List.of("docker", "run", "-d", "--rm", "-p",
        String.valueOf(engine.port)));
    for (String variable : engine.environment) {
      command.add("-e");
      command.add(variable);
    }
    command.add(chosenImage);
    command.addAll(engine.command());
    String id = run(command, 600).trim();
    id = id.lines().reduce((first, second) -> second).orElse(id).trim();
    try {
      String mapping = run(List.of("docker", "port", id, engine.port + "/tcp"), 30);
      String hostPort = mapping.lines().filter(line -> line.contains(":"))
          .filter(line -> !line.startsWith("[")).findFirst()
          .orElseThrow(() -> new IllegalStateException("Docker did not publish port "
              + engine.port + ": " + mapping));
      String port = hostPort.substring(hostPort.lastIndexOf(':') + 1).trim();
      String url = engine.url(chosenImage).replace("{port}", port);
      ScratchDatabase database = new ScratchDatabase(url, engine.user, engine.password, id);
      database.awaitReady(loader, engine.startupSeconds);
      engine.prepare(id, url);
      return database;
    } catch (Exception e) {
      remove(id);
      throw e;
    }
  }

  private void awaitReady(ClassLoader loader, int seconds) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    Exception last = null;
    while (System.nanoTime() < deadline) {
      try (Connection ignored = RuntimeJdbc.connect(loader, url, user, password)) {
        return;
      } catch (Exception e) {
        last = e;
        Thread.sleep(2000);
      }
    }
    throw new IllegalStateException("The throwaway database did not accept connections within "
        + seconds + " seconds.", last);
  }

  @Override
  public void close() {
    if (containerId != null) {
      remove(containerId);
    }
    if (file != null) {
      for (String suffix : List.of("", "-journal", "-wal", "-shm", ".migrax-lock")) {
        try {
          Files.deleteIfExists(file.resolveSibling(file.getFileName() + suffix));
        } catch (IOException e) {
          Log.warn("Could not delete {}{}: {}", file, suffix, e.getMessage());
        }
      }
    }
  }

  private static void remove(String id) {
    try {
      run(List.of("docker", "rm", "-f", id), 60);
    } catch (Exception e) {
      Log.warn("Could not remove Docker container {}: {}", id, e.getMessage());
    }
  }

  static boolean dockerAvailable() {
    try {
      run(List.of("docker", "info", "--format", "{{.ServerVersion}}"), 30);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static String run(List<String> command, int timeoutSeconds) throws Exception {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    process.getOutputStream().close();
    byte[] output;
    try (InputStream in = process.getInputStream()) {
      output = in.readAllBytes();
    }
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException(String.join(" ", command) + " timed out.");
    }
    String text = new String(output, StandardCharsets.UTF_8);
    if (process.exitValue() != 0) {
      throw new IOException(String.join(" ", command) + " failed: " + text.strip());
    }
    return text;
  }

  /** Container settings per database engine. */
  private enum Engine {
    POSTGRESQL("postgres:16-alpine", 5432, "jdbc:postgresql://localhost:{port}/migrax",
        "migrax", "migrax", 120, "POSTGRES_USER=migrax", "POSTGRES_PASSWORD=migrax",
        "POSTGRES_DB=migrax"),
    MYSQL("mysql:8.4", 3306,
        "jdbc:mysql://localhost:{port}/migrax?allowPublicKeyRetrieval=true&useSSL=false",
        "root", "migrax", 180, "MYSQL_ROOT_PASSWORD=migrax", "MYSQL_DATABASE=migrax"),
    MARIADB("mariadb:11.4", 3306, "jdbc:mariadb://localhost:{port}/migrax", "root", "migrax",
        180, "MARIADB_ROOT_PASSWORD=migrax", "MARIADB_DATABASE=migrax"),
    SQLSERVER("mcr.microsoft.com/mssql/server:2022-latest", 1433,
        "jdbc:sqlserver://localhost:{port};encrypt=false;trustServerCertificate=true", "sa",
        "Migrax_Passw0rd!", 300, "ACCEPT_EULA=Y", "MSSQL_SA_PASSWORD=Migrax_Passw0rd!"),
    // An ordinary user: system's schema holds Oracle's own tables.
    ORACLE("gvenzl/oracle-free:slim-faststart", 1521,
        "jdbc:oracle:thin:@localhost:{port}/FREEPDB1", "migrax", "migrax", 300,
        "ORACLE_PASSWORD=migrax", "APP_USER=migrax", "APP_USER_PASSWORD=migrax"),
    COCKROACHDB("cockroachdb/cockroach:v24.3.11", 26257,
        "jdbc:postgresql://localhost:{port}/defaultdb?sslmode=disable", "root", "", 180),
    YUGABYTEDB("yugabytedb/yugabyte:2026.1.2.0-b137", 5433,
        "jdbc:postgresql://localhost:{port}/yugabyte", "yugabyte", "yugabyte", 240),
    TIDB("pingcap/tidb:v8.5.3", 4000, "jdbc:mysql://localhost:{port}/test?useSSL=false", "root",
        "", 120);

    final String image;
    final int port;
    final String url;
    final String user;
    final String password;
    final int startupSeconds;
    final String[] environment;

    Engine(String image, int port, String url, String user, String password, int startupSeconds,
           String... environment) {
      this.image = image;
      this.port = port;
      this.url = url;
      this.user = user;
      this.password = password;
      this.startupSeconds = startupSeconds;
      this.environment = environment;
    }

    /** The JDBC URL for the image: Oracle XE names its database XEPDB1, Oracle Free FREEPDB1. */
    String url(String image) {
      return this == ORACLE && image.contains("oracle-xe") ? url.replace("FREEPDB1", "XEPDB1")
          : url;
    }

    /** Readies the started database: Migrax's lock on Oracle needs EXECUTE on DBMS_LOCK. */
    void prepare(String containerId, String jdbcUrl) throws Exception {
      if (this == ORACLE) {
        String pdb = jdbcUrl.substring(jdbcUrl.lastIndexOf('/') + 1);
        run(List.of("docker", "exec", containerId, "bash", "-c", "printf 'ALTER SESSION SET "
            + "CONTAINER=" + pdb + ";\nGRANT EXECUTE ON SYS.DBMS_LOCK TO " + user
            + ";\n' | sqlplus -s / as sysdba"), 120);
      }
    }

    /** Arguments after the image name: CockroachDB's and YugabyteDB's images need a command. */
    List<String> command() {
      return this == COCKROACHDB ? List.of("start-single-node", "--insecure")
          : this == YUGABYTEDB ? List.of("bin/yugabyted", "start", "--background=false")
          : List.of();
    }

    static Engine of(String dialect) {
      return switch (dialect) {
        case "postgresql" -> POSTGRESQL;
        case "mysql" -> MYSQL;
        case "mariadb" -> MARIADB;
        case "sqlserver" -> SQLSERVER;
        case "oracle" -> ORACLE;
        case "cockroachdb" -> COCKROACHDB;
        case "yugabytedb" -> YUGABYTEDB;
        case "tidb" -> TIDB;
        default -> throw new IllegalArgumentException("No scratch database for " + dialect);
      };
    }
  }
}
