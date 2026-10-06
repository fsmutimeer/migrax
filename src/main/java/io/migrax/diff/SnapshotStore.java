package io.migrax.diff;

import io.migrax.model.SchemaModel;
import io.migrax.util.Json;

import java.nio.file.Path;

public final class SnapshotStore {
  private SnapshotStore() {}

  public static Path projectSnapshot(Path root) {
    return root.resolve(".migrax/snapshot.json");
  }

  public static void save(Path path, SchemaModel model) throws Exception {
    Json.write(path, model);
  }

  public static SchemaModel load(Path path) throws Exception {
    return Json.read(path);
  }
}
