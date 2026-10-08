package io.migrax.plugin;

import java.util.List;

import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** {@code mvn migrax:check}: fails when entities changed without a migration ({@code migrax check}). */
@Mojo(name = "check", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
@Execute(phase = LifecyclePhase.COMPILE)
public final class CheckMojo extends AbstractMigraxMojo {

  @Override
  protected String command() {
    return "check";
  }

  @Override
  protected void addArguments(List<String> args) {
  }
}
