package org.migrax.jakartaee;

import org.migrax.runner.StartupMigrations;
import org.migrax.util.Log;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

/**
 * Applies Migrax migrations from {@code db/migration} when an application starts on a Jakarta
 * EE server, with the data source the server manages: the one {@code migrax.datasource} names
 * (a JNDI name), or else the one the application's persistence.xml names.
 *
 * <p>Settings: {@code migrax.enabled} (default true), {@code migrax.datasource},
 * {@code migrax.locations}, {@code migrax.java-package}, {@code migrax.env},
 * {@code migrax.resume}, {@code migrax.lock-timeout}, {@code migrax.schemas} and
 * {@code migrax.placeholders.<name>}. They are read from {@code migrax.properties} in the
 * application, MicroProfile Config where the server has it, {@code MIGRAX_*} environment
 * variables and system properties; each overrides the one before.
 */
@ApplicationScoped
public class MigraxStartup {
  private static final Logger LOG = Logger.getLogger("org.migrax");
  private static final List<String> KEYS = List.of("enabled", "datasource", "locations",
      "java-package", "env", "resume", "lock-timeout", "schemas", "metrics.enabled",
      "tracing.enabled", "migrate-at-startup");

  void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE)
               @Initialized(ApplicationScoped.class) Object event) throws Exception {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    Map<String, String> settings = settings(loader, System.getenv(), System.getProperties());
    if ("false".equalsIgnoreCase(settings.get("migrax.enabled"))) {
      return;
    }
    String name = settings.get("migrax.datasource");
    if (name == null || name.isBlank()) {
      name = StartupMigrations.persistenceUnitDataSource(loader);
    }
    if (name == null) {
      LOG.warning("Migrax: no data source to migrate. Set migrax.datasource to its JNDI name, "
          + "or name one in persistence.xml. Migrations were not applied.");
      return;
    }
    DataSource dataSource = lookup(name);
    org.migrax.observe.CdiListeners.pendingGauge(settings, loader,
        () -> StartupMigrations.status(dataSource, settings, loader).pending());
    if (!StartupMigrations.migratesAtStartup(settings)) {
      LOG.info("Migrax: " + StartupMigrations.notMigrating(dataSource, settings, loader));
      return;
    }
    Log.Sink previous = Log.setSink(new JulSink());
    try {
      int applied = StartupMigrations.migrate(dataSource, settings, loader,
          org.migrax.observe.CdiListeners.find(settings, loader));
      LOG.info("Migrax: " + (applied == 0 ? "schema is up to date"
          : "applied " + applied + " migration(s) to data source '" + name + "'"));
    } finally {
      Log.setSink(previous);
    }
  }

  /** The data source with this JNDI name; a plain name is also tried under java:comp/env/. */
  static DataSource lookup(String name) throws NamingException {
    InitialContext context = new InitialContext();
    try {
      return (DataSource) context.lookup(name);
    } catch (NamingException e) {
      if (name.startsWith("java:")) {
        throw e;
      }
      return (DataSource) context.lookup("java:comp/env/" + name);
    }
  }

  /**
   * The {@code migrax.*} settings: {@code migrax.properties} on the class path, MicroProfile
   * Config if the server has it, {@code MIGRAX_*} environment variables, then system
   * properties, each overriding the one before.
   */
  static Map<String, String> settings(ClassLoader loader, Map<String, String> environment,
                                      Properties system) {
    Map<String, String> settings = new LinkedHashMap<>();
    try (InputStream in = loader.getResourceAsStream("migrax.properties")) {
      if (in != null) {
        Properties file = new Properties();
        file.load(in);
        file.stringPropertyNames().stream().filter(key -> key.startsWith("migrax."))
            .forEach(key -> settings.put(key, file.getProperty(key)));
      }
    } catch (IOException e) {
      LOG.warning("Migrax: could not read migrax.properties: " + e.getMessage());
    }
    settings.putAll(microProfileConfig(loader));
    for (String key : KEYS) {
      String value = environment.get("MIGRAX_" + key.toUpperCase(Locale.ROOT).replace('-', '_')
          .replace('.', '_'));
      if (value != null) {
        settings.put("migrax." + key, value);
      }
    }
    environment.forEach((name, value) -> {
      if (name.startsWith("MIGRAX_PLACEHOLDERS_")) {
        settings.put("migrax.placeholders." + name.substring("MIGRAX_PLACEHOLDERS_".length())
            .toLowerCase(Locale.ROOT), value);
      }
    });
    system.stringPropertyNames().stream().filter(key -> key.startsWith("migrax."))
        .forEach(key -> settings.put(key, system.getProperty(key)));
    return settings;
  }

  /** The migrax.* settings from MicroProfile Config, read by reflection; none without it. */
  private static Map<String, String> microProfileConfig(ClassLoader loader) {
    Map<String, String> settings = new LinkedHashMap<>();
    try {
      Class<?> provider = Class.forName("org.eclipse.microprofile.config.ConfigProvider", true,
          loader);
      Object config = provider.getMethod("getConfig").invoke(null);
      Iterable<?> names = (Iterable<?>) config.getClass().getMethod("getPropertyNames")
          .invoke(config);
      java.lang.reflect.Method value = config.getClass().getMethod("getOptionalValue",
          String.class, Class.class);
      for (Object name : names) {
        String key = String.valueOf(name);
        if (key.startsWith("migrax.")) {
          ((java.util.Optional<?>) value.invoke(config, key, String.class))
              .ifPresent(found -> settings.put(key, String.valueOf(found)));
        }
      }
    } catch (ClassNotFoundException absent) {
      // A server without MicroProfile Config.
    } catch (ReflectiveOperationException | RuntimeException e) {
      LOG.fine("Migrax: MicroProfile Config not read: " + e);
    }
    return settings;
  }

  /** Sends Migrax core messages to java.util.logging, which every server picks up. */
  private static final class JulSink implements Log.Sink {
    @Override
    public void debug(String message) {
      LOG.log(Level.FINE, "Migrax: {0}", message);
    }

    @Override
    public void info(String message) {
      LOG.info("Migrax: " + message);
    }

    @Override
    public void warn(String message) {
      LOG.warning("Migrax: " + message);
    }

    @Override
    public void error(String message) {
      LOG.severe("Migrax: " + message);
    }
  }
}
