package org.migrax.dialect;

import java.util.Arrays;
import java.util.List;

/**
 * How {@code migrax verify} starts a throwaway database of one engine: in memory, in a
 * temporary file, or in a Docker container. A dialect returns one from
 * {@link Dialect#scratch()}. Container engines that need more than the settings override the
 * hooks ({@link #url(String)}, {@link #command()}, {@link #prepareCommand},
 * {@link #versionImage}).
 *
 * @since 0.19.0
 */
public class ScratchSetup {

  /** Where the throwaway database lives. */
  public enum Kind {
    /** In memory: {@code {name}} in the URL becomes a new unique name. */
    MEMORY,
    /** In a temporary file: {@code {file}} in the URL becomes its path. */
    FILE,
    /** In a Docker container: {@code {port}} in the URL becomes the published port. */
    CONTAINER
  }

  private final Kind kind;
  private final String image;
  private final int port;
  private final String url;
  private final String user;
  private final String password;
  private final int startupSeconds;
  private final List<String> environment;

  /**
   * A Docker container. The URL uses 127.0.0.1, not localhost: the port is published on IPv4
   * only.
   *
   * @param image the default image
   * @param port the port the database listens on inside the container
   * @param url the JDBC URL, with {@code {port}} for the published port
   * @param user the user
   * @param password the password
   * @param startupSeconds how long to wait until the database accepts connections
   * @param environment the container's environment variables, as {@code NAME=value}
   */
  public ScratchSetup(String image, int port, String url, String user, String password,
                      int startupSeconds, String... environment) {
    this(Kind.CONTAINER, image, port, url, user, password, startupSeconds, environment);
  }

  private ScratchSetup(Kind kind, String image, int port, String url, String user,
                       String password, int startupSeconds, String... environment) {
    this.kind = kind;
    this.image = image;
    this.port = port;
    this.url = url;
    this.user = user;
    this.password = password;
    this.startupSeconds = startupSeconds;
    this.environment = List.copyOf(Arrays.asList(environment));
  }

  /**
   * An in-memory database.
   *
   * @param url the JDBC URL, with {@code {name}} for a new unique name
   * @param user the user
   * @param password the password
   * @return the setup
   */
  public static ScratchSetup inMemory(String url, String user, String password) {
    return new ScratchSetup(Kind.MEMORY, null, 0, url, user, password, 0);
  }

  /**
   * A database in a temporary file, deleted with its side files on close.
   *
   * @param url the JDBC URL, with {@code {file}} for the file's path
   * @param user the user
   * @param password the password
   * @return the setup
   */
  public static ScratchSetup inFile(String url, String user, String password) {
    return new ScratchSetup(Kind.FILE, null, 0, url, user, password, 0);
  }

  /** Where the throwaway database lives. */
  public Kind kind() {
    return kind;
  }

  /** The default image of a container; null for the other kinds. */
  public String image() {
    return image;
  }

  /** The port the database listens on inside the container. */
  public int port() {
    return port;
  }

  /** The user Migrax connects as. */
  public String user() {
    return user;
  }

  /** The user's password; empty when there is none. */
  public String password() {
    return password;
  }

  /** How long to wait until the started database accepts connections. */
  public int startupSeconds() {
    return startupSeconds;
  }

  /** The container's environment variables, as {@code NAME=value}. */
  public List<String> environment() {
    return environment;
  }

  /**
   * The JDBC URL for the database started from {@code image}, with its placeholder. The
   * default is the same URL for every image.
   *
   * @param image the image that runs, or null for an in-memory or file database
   * @return the URL
   */
  public String url(String image) {
    return url;
  }

  /** Arguments after the image name; the default is none. */
  public List<String> command() {
    return List.of();
  }

  /**
   * A command that readies the started container before Migrax uses it, such as a grant;
   * the default is none.
   *
   * @param containerId the container
   * @param jdbcUrl the database's JDBC URL
   * @return the command and its arguments, or an empty list
   */
  public List<String> prepareCommand(String containerId, String jdbcUrl) {
    return List.of();
  }

  /**
   * Whether {@link #versionImage} has images per server version, so asking the server for
   * its version is worth a connection. The default is {@code false}.
   */
  public boolean hasVersionImages() {
    return false;
  }

  /**
   * The image of a server's own version, for SQL that only that version (or newer) runs, such
   * as the settings pg_dump writes; null when there is no such image (the default).
   *
   * @param major the server's major version
   * @param minor the server's minor version
   * @return an image name, or null
   */
  public String versionImage(int major, int minor) {
    return null;
  }
}
