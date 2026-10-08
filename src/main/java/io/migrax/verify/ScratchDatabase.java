package io.migrax.verify;

import io.migrax.plugin.RuntimeJdbc;
import io.migrax.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A throwaway database for {@code migrax verify}: in-memory H2, or a Docker container of the
 * project's database engine, removed again on {@link #close()}.
 *
 * @since 0.1.0
 */
public final class ScratchDatabase implements AutoCloseable {
  private final String url;
  private final String user;
  private final String password;
  private final String containerId;

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
   * Starts a scratch database for the dialect.
   *
   * @param image Docker image to use instead of the default, or null
   * @param loader class loader with the JDBC driver, used to wait until the database accepts
   *     connections
   */
  public static ScratchDatabase start(String dialect, String image, ClassLoader loader)
      throws Exception {
    if ("h2".equals(dialect)) {
      return new ScratchDatabase("jdbc:h2:mem:migrax_verify_" + UUID.randomUUID()
          .toString().replace("-", "") + ";DB_CLOSE_DELAY=-1", "sa", "", null);
    }
    Engine engine = Engine.of(dialect);
    String chosenImage = image == null || image.isBlank() ? engine.image : image;
    if (!dockerAvailable()) {
      throw new IllegalStateException("Docker is not available, so Migrax cannot start a "
          + "throwaway " + dialect + " database. Start Docker, or pass --url with an empty "
          + "scratch database.");
    }
    Log.info("Starting a throwaway {} database ({})...", dialect, chosenImage);
    List<String> command = new ArrayList<>(List.of("docker", "run", "-d", "--rm", "-P"));
    for (String variable : engine.environment) {
      command.add("-e");
      command.add(variable);
    }
    command.add(chosenImage);
    String id = run(command, 600).trim();
    id = id.lines().reduce((first, second) -> second).orElse(id).trim();
    try {
      String mapping = run(List.of("docker", "port", id, engine.port + "/tcp"), 30);
      String hostPort = mapping.lines().filter(line -> line.contains(":"))
          .filter(line -> !line.startsWith("[")).findFirst()
          .orElseThrow(() -> new IllegalStateException("Docker did not publish port "
              + engine.port + ": " + mapping));
      String port = hostPort.substring(hostPort.lastIndexOf(':') + 1).trim();
      String url = engine.url.replace("{port}", port);
      ScratchDatabase database = new ScratchDatabase(url, engine.user, engine.password, id);
      database.awaitReady(loader, engine.startupSeconds);
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
    ORACLE("gvenzl/oracle-free:slim-faststart", 1521,
        "jdbc:oracle:thin:@localhost:{port}/FREEPDB1", "system", "migrax", 300,
        "ORACLE_PASSWORD=migrax");

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

    static Engine of(String dialect) {
      return switch (dialect) {
        case "postgresql" -> POSTGRESQL;
        case "mysql" -> MYSQL;
        case "mariadb" -> MARIADB;
        case "sqlserver" -> SQLSERVER;
        case "oracle" -> ORACLE;
        default -> throw new IllegalArgumentException("No scratch database for " + dialect);
      };
    }
  }
}
