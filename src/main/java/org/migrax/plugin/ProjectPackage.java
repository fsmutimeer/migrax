package org.migrax.plugin;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Finds a project's entity package.
 *
 * @since 0.10.0
 */
public final class ProjectPackage {
  private ProjectPackage() {}

  /**
   * The package that holds the entities: {@code -Dmigrax.package}, {@code MIGRAX_PACKAGE},
   * {@code migrax.package} in the application config, then the Maven groupId or Gradle group.
   *
   * @param root the project folder (with pom.xml or build.gradle)
   * @return the package, or null when nothing names one
   */
  public static String entityPackage(Path root) throws Exception {
    String configured = firstNonBlank(
        System.getProperty("migrax.package"), System.getenv("MIGRAX_PACKAGE"));
    if (configured != null) {
      return configured;
    }
    try {
      configured = ProjectDatabaseConfig.settings(
          root.resolve("src/main/resources")).get("migrax.package");
    } catch (IOException ignored) {
      configured = null;
    }
    if (configured != null && !configured.isBlank()) {
      return configured.trim();
    }
    Path pom = root.resolve("pom.xml");
    if (Files.isRegularFile(pom)) {
      return mavenGroupId(pom);
    }
    for (String file : List.of("build.gradle.kts", "build.gradle")) {
      Path build = root.resolve(file);
      if (Files.isRegularFile(build)) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?m)^\\s*group\\s*=\\s*[\"']([^\"']+)[\"']")
            .matcher(Files.readString(build));
        if (matcher.find()) {
          return matcher.group(1);
        }
      }
    }
    return null;
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

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
