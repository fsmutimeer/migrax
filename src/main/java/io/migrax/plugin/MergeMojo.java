package io.migrax.plugin;

import java.util.List;

import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** {@code mvn migrax:merge}: renumbers migrations two branches numbered the same ({@code migrax merge}). */
@Mojo(name = "merge", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
@Execute(phase = LifecyclePhase.COMPILE)
public final class MergeMojo extends AbstractMigraxMojo {

  @Override
  protected String command() {
    return "merge";
  }

  @Override
  protected void addArguments(List<String> args) {
  }
}
