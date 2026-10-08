package io.migrax.plugin;

import java.util.List;

import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** {@code mvn migrax:verify}: applies all migrations to a throwaway database and validates the schema ({@code migrax verify}). */
@Mojo(name = "verify", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
@Execute(phase = LifecyclePhase.COMPILE)
public final class VerifyMojo extends AbstractMigraxMojo {
  /** Empty scratch database to use instead of a throwaway one. */
  @Parameter(property = "migrax.verifyUrl")
  private String verifyUrl;

  /** Docker image for the throwaway database. */
  @Parameter(property = "migrax.image")
  private String image;

  /** Do not test rollback scripts. */
  @Parameter(property = "migrax.skipRollbacks", defaultValue = "false")
  private boolean skipRollbacks;

  @Override
  protected String command() {
    return "verify";
  }

  @Override
  protected void addArguments(List<String> args) {
    flag(args, "--skip-rollbacks", skipRollbacks);
    option(args, "--image", image);
    if (verifyUrl != null && !verifyUrl.isBlank()) {
      // The scratch database replaces the application database for this goal.
      url = verifyUrl;
    }
  }
}
