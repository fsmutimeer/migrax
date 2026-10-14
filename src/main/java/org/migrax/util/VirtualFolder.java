package org.migrax.util;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The files under a classpath folder that is neither a directory nor a jar: application
 * servers such as WildFly serve their deployments from a virtual file system (JBoss VFS,
 * {@code vfs:} URLs). It is read through reflection, so Migrax needs no dependency on it.
 *
 * @since 0.6.0
 */
public final class VirtualFolder {
  private VirtualFolder() {}

  /** Opens a file's content. */
  @FunctionalInterface
  public interface Opener {
    InputStream open() throws IOException;
  }

  /**
   * A file under the folder.
   *
   * @param path the path below the folder, with {@code /} separators
   * @param opener opens the file's content
   */
  public record Entry(String path, Opener opener) {}

  /** The files under the folder at {@code url}, recursively; empty when it can't be read. */
  public static List<Entry> list(URL url) {
    List<Entry> entries = new ArrayList<>();
    try {
      Object folder = url.openConnection().getContent();
      Method children = folder.getClass().getMethod("getChildrenRecursively");
      Method relative = folder.getClass().getMethod("getPathNameRelativeTo", folder.getClass());
      for (Object child : (Collection<?>) children.invoke(folder)) {
        if (Boolean.TRUE.equals(child.getClass().getMethod("isFile").invoke(child))) {
          String path = String.valueOf(relative.invoke(child, folder)).replace('\\', '/');
          Method openStream = child.getClass().getMethod("openStream");
          entries.add(new Entry(path, () -> {
            try {
              return (InputStream) openStream.invoke(child);
            } catch (ReflectiveOperationException e) {
              throw new IOException("Could not read " + path + " in " + url, e);
            }
          }));
        }
      }
    } catch (IOException | ReflectiveOperationException | RuntimeException e) {
      Log.debug("Could not list {}: {}", url, e.toString());
    }
    return entries;
  }
}
