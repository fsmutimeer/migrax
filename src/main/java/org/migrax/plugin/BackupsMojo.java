package org.migrax.plugin;

import java.util.List;

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * {@code mvn migrax:backups}: lists, or drops, the backup tables of migrations generated with
 * --keep-data ({@code migrax backups}).
 */
@Mojo(name = "backups", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class BackupsMojo extends AbstractMigraxMojo {
  /** Drops the backup tables instead of listing them. */
  @Parameter(property = "migrax.drop", defaultValue = "false")
  private boolean drop;

  /** With drop: only the backups of this migration, e.g. 0005. */
  @Parameter(property = "migrax.migration")
  private String migration;

  /** Confirms dropping; without it the goal fails. */
  @Parameter(property = "migrax.confirm", defaultValue = "false")
  private boolean confirm;

  @Override
  protected String command() {
    return "backups";
  }

  @Override
  protected void addArguments(List<String> args) {
    flag(args, "--drop", drop);
    if (drop && migration != null && !migration.isBlank()) {
      args.add(migration.trim());
    }
    flag(args, "--yes", confirm);
  }
}
