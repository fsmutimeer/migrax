package io.migrax.util;

import java.io.File;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Finds classes in a package (including sub-packages) on a class loader, from directories and
 * jars, without initializing them.
 *
 * @since 0.1.0
 */
public final class ClassScanner {
  private ClassScanner() {}

  /**
   * Returns the classes under {@code basePackage} that match {@code filter}, sorted by name.
   *
   * @throws IllegalStateException if a class cannot be loaded, naming the class and its source,
   *     so a missing dependency never silently drops an entity
   */
  public static List<Class<?>> scan(ClassLoader loader, String basePackage,
                                    Predicate<Class<?>> filter) throws Exception {
    String path = basePackage.replace('.', '/');
    Set<Class<?>> found = new LinkedHashSet<>();
    Enumeration<URL> urls = loader.getResources(path);
    while (urls.hasMoreElements()) {
      URL url = urls.nextElement();
      if ("file".equals(url.getProtocol())) {
        scanDirectory(basePackage, Paths.get(url.toURI()), loader, filter, found);
      } else if ("jar".equals(url.getProtocol())) {
        scanJar(url, path, loader, filter, found);
      }
    }
    List<Class<?>> sorted = new ArrayList<>(found);
    sorted.sort(Comparator.comparing(Class::getName));
    return sorted;
  }

  private static void scanDirectory(String pkg, Path root, ClassLoader loader,
                                    Predicate<Class<?>> filter, Set<Class<?>> found)
      throws Exception {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path classFile : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
        String relative = root.relativize(classFile).toString().replace(File.separatorChar, '.');
        String className = pkg + "." + relative.substring(0, relative.length() - 6);
        add(className, classFile.toString(), loader, filter, found);
      }
    }
  }

  private static void scanJar(URL url, String path, ClassLoader loader,
                              Predicate<Class<?>> filter, Set<Class<?>> found) throws Exception {
    JarURLConnection connection = (JarURLConnection) url.openConnection();
    connection.setUseCaches(false);
    try (var jar = connection.getJarFile()) {
      var entries = jar.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (!entry.isDirectory() && entry.getName().startsWith(path + "/")
            && entry.getName().endsWith(".class")) {
          String className = entry.getName().replace('/', '.');
          className = className.substring(0, className.length() - 6);
          add(className, url + "!/" + entry.getName(), loader, filter, found);
        }
      }
    }
  }

  private static void add(String className, String source, ClassLoader loader,
                          Predicate<Class<?>> filter, Set<Class<?>> found) {
    if (className.endsWith("package-info") || className.endsWith("module-info")) {
      return;
    }
    try {
      Class<?> candidate = Class.forName(className, false, loader);
      if (filter.test(candidate)) {
        found.add(candidate);
      }
    } catch (ClassNotFoundException | LinkageError failure) {
      throw new IllegalStateException(
          "Could not load class '" + className + "' while scanning " + source + ".", failure);
    }
  }
}
