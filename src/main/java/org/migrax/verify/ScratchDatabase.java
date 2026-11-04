package org.migrax.verify;

import org.migrax.dialect.Dialect;
import org.migrax.dialect.Dialects;
import org.migrax.dialect.ScratchSetup;
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
   * Whether {@link #imageFor} has images per server version for the dialect, so asking the
   * server for its version is worth a connection.
   *
   * @since 0.17.2
   */
  public static boolean hasVersionImages(String dialect) {
    ScratchSetup setup = setup(dialect);
    return setup != null && setup.hasVersionImages();
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
    ScratchSetup setup = setup(dialect);
    return setup == null ? null : setup.versionImage(major, minor);
  }

  /** The dialect's setup, by exact id; null for an unknown id or a dialect without one. */
  private static ScratchSetup setup(String dialect) {
    Dialect found = dialect != null && Dialects.NAMES.contains(dialect)
        ? Dialects.byName(dialect) : null;
    return found == null ? null : found.scratch();
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
    ScratchSetup setup = setup(dialect);
    if (setup == null) {
      throw new IllegalArgumentException("No scratch database for " + dialect);
    }
    if (setup.kind() == ScratchSetup.Kind.FILE) {
      Path file = Files.createTempFile("migrax-verify-", ".db");
      ScratchDatabase database = new ScratchDatabase(setup.url(null)
          .replace("{file}", file.toString()), setup.user(), setup.password(), null);
      database.file = file;
      return database;
    }
    if (setup.kind() == ScratchSetup.Kind.MEMORY) {
      return new ScratchDatabase(setup.url(null).replace("{name}", "migrax_verify_"
          + UUID.randomUUID().toString().replace("-", "")), setup.user(), setup.password(),
          null);
    }
    String chosenImage = image == null || image.isBlank() ? setup.image() : image;
    if (!dockerAvailable()) {
      throw new IllegalStateException("Docker is not available, so Migrax cannot start a "
          + "throwaway " + dialect + " database. Start Docker, or pass --url with an empty "
          + "scratch database.");
    }
    Log.info("Starting a throwaway {} database ({})...", dialect, chosenImage);
    // The port by number: the Oracle images don't declare it, so -P would publish nothing.
    // Only on 127.0.0.1: the database has a fixed password, other machines must not reach it.
    List<String> command = new ArrayList<>(List.of("docker", "run", "-d", "--rm", "-p",
        "127.0.0.1::" + setup.port()));
    for (String variable : setup.environment()) {
      command.add("-e");
      command.add(variable);
    }
    command.add(chosenImage);
    command.addAll(setup.command());
    String id = run(command, 600).trim();
    id = id.lines().reduce((first, second) -> second).orElse(id).trim();
    try {
      String mapping = run(List.of("docker", "port", id, setup.port() + "/tcp"), 30);
      String hostPort = mapping.lines().filter(line -> line.contains(":"))
          .filter(line -> !line.startsWith("[")).findFirst()
          .orElseThrow(() -> new IllegalStateException("Docker did not publish port "
              + setup.port() + ": " + mapping));
      String port = hostPort.substring(hostPort.lastIndexOf(':') + 1).trim();
      String url = setup.url(chosenImage).replace("{port}", port);
      ScratchDatabase database = new ScratchDatabase(url, setup.user(), setup.password(), id);
      database.awaitReady(loader, setup.startupSeconds());
      List<String> prepare = setup.prepareCommand(id, url);
      if (!prepare.isEmpty()) {
        run(prepare, 120);
      }
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
}
