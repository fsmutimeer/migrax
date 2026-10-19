package org.migrax.plugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.migrax.diff.SnapshotStore;

/**
 * {@code migrax:package-snapshot}: copies {@code .migrax/snapshot.json} (and each further
 * database's) into the application as {@code META-INF/migrax/snapshot.json}, so it can check
 * for drift when it starts ({@code migrax.drift.check}). Runs in process-resources once it is
 * listed under the plugin's executions.
 *
 * @since 0.16.0
 */
@Mojo(name = "package-snapshot", defaultPhase = LifecyclePhase.PROCESS_RESOURCES,
    threadSafe = true)
public final class PackageSnapshotMojo extends AbstractMojo {
  @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
  private File basedir;

  @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true,
      required = true)
  private File outputDirectory;

  @Override
  public void execute() throws MojoExecutionException {
    try {
      Map<String, Path> snapshots = SnapshotStore.packaged(basedir.toPath());
      if (snapshots.isEmpty()) {
        getLog().info("Migrax: no .migrax/snapshot.json to package yet");
        return;
      }
      for (Map.Entry<String, Path> snapshot : snapshots.entrySet()) {
        Path target = outputDirectory.toPath().resolve(snapshot.getKey());
        Path folder = target.getParent();
        if (folder != null) {
          Files.createDirectories(folder);
        }
        Files.copy(snapshot.getValue(), target, StandardCopyOption.REPLACE_EXISTING);
        getLog().info("Migrax: packaged " + basedir.toPath().relativize(snapshot.getValue())
            .toString().replace('\\', '/') + " as " + snapshot.getKey());
      }
    } catch (java.io.IOException e) {
      throw new MojoExecutionException("Could not package the Migrax snapshot: "
          + e.getMessage(), e);
    }
  }
}
