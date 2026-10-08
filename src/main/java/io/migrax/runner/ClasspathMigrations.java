package io.migrax.runner;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Loads migrations packaged with an application ({@code classpath:db/migration}), from
 * directories and jars, for running migrations at application startup.
 *
 * @since 0.1.0
 */
public final class ClasspathMigrations {
  private ClasspathMigrations() {}

  /** SQL migrations in a classpath folder such as {@code db/migration}; sub-folders skipped. */
  public static List<Migration> load(ClassLoader loader, String location) throws IOException {
    String folder = location.replace("classpath:", "").replaceAll("^/+", "")
        .replaceAll("/+$", "");
    Map<String, Migration> migrations = new LinkedHashMap<>();
    Enumeration<URL> urls = loader.getResources(folder);
    while (urls.hasMoreElements()) {
      URL url = urls.nextElement();
      if ("file".equals(url.getProtocol())) {
        try {
          Path directory = Paths.get(url.toURI());
          try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().endsWith(".sql")).toList()) {
              String name = file.getFileName().toString();
              migrations.putIfAbsent(name, Migration.ofSql(name,
                  Files.readString(file, StandardCharsets.UTF_8)));
            }
          }
        } catch (URISyntaxException e) {
          throw new IOException("Invalid migration folder URL " + url, e);
        }
      } else if (url.openConnection() instanceof JarURLConnection connection) {
        connection.setUseCaches(false);
        try (JarFile jar = connection.getJarFile()) {
          String prefix = connection.getEntryName() == null ? folder + "/"
              : connection.getEntryName().replaceAll("/?$", "/");
          Enumeration<JarEntry> entries = jar.entries();
          while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String path = entry.getName();
            if (!entry.isDirectory() && path.startsWith(prefix) && path.endsWith(".sql")
                && path.indexOf('/', prefix.length()) < 0) {
              String name = path.substring(prefix.length());
              try (InputStream in = jar.getInputStream(entry)) {
                migrations.putIfAbsent(name, Migration.ofSql(name,
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)));
              }
            }
          }
        }
      }
    }
    return new ArrayList<>(migrations.values());
  }
}
