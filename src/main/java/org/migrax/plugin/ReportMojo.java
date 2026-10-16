package org.migrax.plugin;

import java.util.List;

import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * {@code mvn migrax:report}: writes an HTML page with the schema, the migrations, lint findings
 * and drift ({@code migrax report}).
 */
@Mojo(name = "report", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
@Execute(phase = LifecyclePhase.COMPILE)
public final class ReportMojo extends AbstractMigraxMojo {
  /** Where to write the page; defaults to migrax-report.html. */
  @Parameter(property = "migrax.html")
  private String html;

  @Override
  protected String command() {
    return "report";
  }

  @Override
  protected void addArguments(List<String> args) {
    option(args, "--html", html);
  }
}
