package io.migrax.cli;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ProjectContext {
  private static final List<String> PROJECT_MARKERS = List.of(
      "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");
  private static final List<String> CLASS_DIRECTORIES = List.of(
      "target/classes",
      "build/classes/java/main",
      "build/classes/kotlin/main",
      "out/production/classes");
  private static final List<String> DEPENDENCY_DIRECTORIES = List.of(
      "target/dependency", "build/dependencies", "build/libs", "lib");

  private ProjectContext() {}

  static Path findRoot(Path start) throws IOException {
    Path current = start.toAbsolutePath().normalize();
    if (Files.isRegularFile(current)) {
      current = current.getParent();
    }
    for (Path directory = current; directory != null; directory = directory.getParent()) {
      for (String marker : PROJECT_MARKERS) {
        if (Files.exists(directory.resolve(marker))) {
          return directory;
        }
      }
      if (Files.exists(directory.resolve(".migrax"))) {
        return directory;
      }
    }
    return current;
  }

  static String packageName(Path root, String requested) throws Exception {
    if (requested != null && !requested.isBlank()) {
      return requested.trim();
    }
    String configured = firstNonBlank(
        System.getProperty("migrax.package"), System.getenv("MIGRAX_PACKAGE"));
    if (configured != null) {
      return configured;
    }
    Path pom = root.resolve("pom.xml");
    return Files.isRegularFile(pom) ? mavenGroupId(pom) : null;
  }

  static URLClassLoader runtimeLoader(Path root, String[] args) throws IOException {
    Set<Path> entries = new LinkedHashSet<>();
    String configured = firstNonBlank(
        option(args, "--classpath"),
        System.getProperty("migrax.classpath"), System.getenv("MIGRAX_CLASSPATH"));
    if (configured != null) {
      for (String item : configured.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
        if (!item.isBlank()) {
          Path path = Path.of(item.trim());
          entries.add((path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize());
        }
      }
    }

    for (String directory : CLASS_DIRECTORIES) {
      Path path = root.resolve(directory);
      if (Files.isDirectory(path)) {
        entries.add(path.toAbsolutePath().normalize());
      }
    }
    for (String directory : DEPENDENCY_DIRECTORIES) {
      Path path = root.resolve(directory);
      if (Files.isDirectory(path)) {
        try (var children = Files.list(path)) {
          children.filter(Files::isRegularFile)
              .filter(child -> child.getFileName().toString().endsWith(".jar"))
              .map(child -> child.toAbsolutePath().normalize())
              .forEach(entries::add);
        }
      }
    }

    List<URL> urls = new ArrayList<>();
    for (Path entry : entries) {
      if (!Files.exists(entry)) {
        throw new IOException("Configured runtime classpath entry does not exist: " + entry);
      }
      try {
        urls.add(entry.toUri().toURL());
      } catch (MalformedURLException e) {
        throw new IOException("Invalid runtime classpath entry: " + entry, e);
      }
    }
    return new URLClassLoader(urls.toArray(URL[]::new),
        Thread.currentThread().getContextClassLoader());
  }

  private static String mavenGroupId(Path pom) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    Element project = factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
    String groupId = directChildText(project, "groupId");
    if (groupId == null) {
      Element parent = directChild(project, "parent");
      groupId = parent == null ? null : directChildText(parent, "groupId");
    }
    return groupId;
  }

  private static Element directChild(Element parent, String name) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element element && name.equals(localName(element))) {
        return element;
      }
    }
    return null;
  }

  private static String directChildText(Element parent, String name) {
    Element child = directChild(parent, name);
    return child == null || child.getTextContent().isBlank() ? null : child.getTextContent().trim();
  }

  private static String localName(Element element) {
    String localName = element.getLocalName();
    if (localName != null) {
      return localName;
    }
    String tagName = element.getTagName();
    int separator = tagName.indexOf(':');
    return separator < 0 ? tagName : tagName.substring(separator + 1);
  }

  private static String option(String[] args, String key) {
    for (int i = 0; i < args.length; i++) {
      if (args[i].startsWith(key + "=")) {
        return args[i].substring(key.length() + 1);
      }
      if (args[i].equals(key) && i + 1 < args.length) {
        return args[i + 1];
      }
    }
    return null;
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
