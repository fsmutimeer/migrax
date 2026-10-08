package io.migrax.diff;

import io.migrax.model.SchemaModel;
import io.migrax.util.Json;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The entity-model snapshot in {@code .migrax/snapshot.json}, plus the model after each
 * generated migration in {@code .migrax/history/<migration>.json}.
 *
 * @since 0.1.0
 */
public final class SnapshotStore {
  private SnapshotStore() {}

  public static Path projectSnapshot(Path root) {
    return root.resolve(".migrax/snapshot.json");
  }

  /** Model recorded after a generated migration, e.g. for squashing. */
  public static Path historySnapshot(Path root, String migrationName) {
    String name = migrationName.endsWith(".sql")
        ? migrationName.substring(0, migrationName.length() - 4) : migrationName;
    return root.resolve(".migrax/history").resolve(name + ".json");
  }

  public static void save(Path path, SchemaModel model) throws Exception {
    Json.write(path, model);
  }

  public static void save(Path path, SchemaModel model, String dialect) throws Exception {
    Json.write(path, model, dialect);
  }

  /** Saves the snapshot with the dialect and naming strategy it was generated with. */
  public static void save(Path path, SchemaModel model, String dialect, String naming)
      throws Exception {
    Json.write(path, model, dialect, naming);
  }

  public static SchemaModel load(Path path) throws Exception {
    String content = Files.readString(path);
    if (content.contains("<<<<<<<") || content.contains(">>>>>>>")) {
      throw new IllegalStateException("Snapshot file '" + path + "' has git merge conflicts. "
          + "Run 'migrax merge' to rebuild it.");
    }
    try {
      return Json.parse(content);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Corrupt snapshot file '" + path + "': " + e.getMessage()
              + ". Restore the file from version control or re-generate.", e);
    }
  }

  /**
   * Naming strategy id recorded in the snapshot, or null. Keeps names stable when detection
   * would change, e.g. after the database URL moves from application config to env vars.
   */
  public static String naming(Path path) {
    try {
      return Files.exists(path) ? Json.rootString(Files.readString(path), "naming") : null;
    } catch (Exception e) {
      return null;
    }
  }

  /** Dialect recorded in the snapshot, or null. */
  public static String dialect(Path path) {
    try {
      return Files.exists(path) ? Json.dialect(Files.readString(path)) : null;
    } catch (Exception e) {
      return null;
    }
  }
}
