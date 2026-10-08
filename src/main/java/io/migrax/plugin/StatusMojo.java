package io.migrax.plugin;

import java.util.List;

import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** {@code mvn migrax:status}: shows applied and pending migrations ({@code migrax status}). */
@Mojo(name = "status", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class StatusMojo extends AbstractMigraxMojo {

  @Override
  protected String command() {
    return "status";
  }

  @Override
  protected void addArguments(List<String> args) {
  }
}
