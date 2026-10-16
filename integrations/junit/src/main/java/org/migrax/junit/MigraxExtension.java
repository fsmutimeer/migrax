package org.migrax.junit;

import org.migrax.api.Migrax;
import org.migrax.plugin.RuntimeJdbc;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.HierarchyTraversalMode;
import org.junit.platform.commons.support.ReflectionSupport;

/**
 * The JUnit 5 extension behind {@link MigraxTest}: migrates the test database once per test
 * class (nested classes share it), checks the entities and, when asked, the rollback scripts.
 *
 * @since 0.10.0
 */
public final class MigraxExtension implements BeforeAllCallback, ParameterResolver {
  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(MigraxExtension.class);
  private static final AtomicInteger DATABASES = new AtomicInteger();

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    ExtensionContext.Store store = context.getStore(NAMESPACE);
    if (store.get(Migrax.class) != null) {
      return; // An enclosing class migrated already.
    }
    Class<?> testClass = context.getRequiredTestClass();
    MigraxTest settings = settings(testClass).orElseThrow(() ->
        new ExtensionConfigurationException(testClass.getName() + " uses MigraxExtension "
            + "without @MigraxTest."));
    DataSource dataSource = dataSource(testClass, settings, store);
    Migrax migrax = Migrax.configure()
        .dataSource(dataSource)
        .locations(List.of(settings.locations()))
        .javaPackage(settings.javaPackage())
        .entities(settings.entities())
        .schemas(List.of(settings.schemas()))
        .environment(settings.env())
        .load();
    store.put(Migrax.class, migrax);
    store.put(DataSource.class, dataSource);
    store.put(Migrax.MigrateResult.class, migrax.migrate());

    if (settings.validate()) {
      Migrax.Validation validation = migrax.validate();
      if (!validation.valid()) {
        throw new AssertionError("The migrated schema does not match the entities ("
            + validation.entities() + "):" + lines(validation.problems())
            + System.lineSeparator() + "Generate a migration for the entity changes "
            + "(migrax generate), or fix the migration that differs.");
      }
    }
    if (settings.rollbacks()) {
      Migrax.RollbackTest rollbacks = migrax.testRollbacks();
      if (!rollbacks.ok()) {
        throw new AssertionError("Rollback scripts: " + rollbacks.summary());
      }
    }
  }

  @Override
  public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
    Class<?> type = parameter.getParameter().getType();
    return type == DataSource.class || type == Migrax.class
        || type == Migrax.MigrateResult.class;
  }

  @Override
  public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
    Class<?> type = parameter.getParameter().getType();
    return context.getStore(NAMESPACE).get(type, type);
  }

  /** The annotation on the class or, for a nested class, on an enclosing one. */
  private static Optional<MigraxTest> settings(Class<?> testClass) {
    for (Class<?> current = testClass; current != null; current = current.getEnclosingClass()) {
      Optional<MigraxTest> found = AnnotationSupport.findAnnotation(current, MigraxTest.class);
      if (found.isPresent()) {
        return found;
      }
    }
    return Optional.empty();
  }

  private static DataSource dataSource(Class<?> testClass, MigraxTest settings,
                                       ExtensionContext.Store store) throws Exception {
    for (Class<?> current = testClass; current != null; current = current.getEnclosingClass()) {
      List<Field> fields = ReflectionSupport.findFields(current,
          field -> field.isAnnotationPresent(MigraxDatabase.class),
          HierarchyTraversalMode.TOP_DOWN);
      if (!fields.isEmpty()) {
        Field field = fields.get(0);
        if (!Modifier.isStatic(field.getModifiers())) {
          throw new ExtensionConfigurationException("@MigraxDatabase field " + field.getName()
              + " must be static: the database is migrated once, before all tests.");
        }
        field.setAccessible(true);
        return fromField(field.getName(), field.get(null));
      }
    }
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    if (!settings.url().isBlank()) {
      return new UrlDataSource(loader, settings.url(), settings.user(), settings.password());
    }
    String url = "jdbc:h2:mem:migrax_" + testClass.getSimpleName() + "_"
        + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
    UrlDataSource h2 = new UrlDataSource(loader, url, "sa", "");
    // Dropped when the class is done, so the memory is freed.
    store.put("h2", (ExtensionContext.Store.CloseableResource) () -> {
      try (Connection connection = h2.getConnection()) {
        connection.createStatement().execute("SHUTDOWN");
      }
    });
    return h2;
  }

  /** A DataSource, or a JDBC container (started when needed). */
  private static DataSource fromField(String name, Object value) throws Exception {
    if (value == null) {
      throw new ExtensionConfigurationException("@MigraxDatabase field " + name + " is null.");
    }
    if (value instanceof DataSource dataSource) {
      return dataSource;
    }
    Method jdbcUrl;
    try {
      jdbcUrl = value.getClass().getMethod("getJdbcUrl");
    } catch (NoSuchMethodException e) {
      throw new ExtensionConfigurationException("@MigraxDatabase field " + name + " must be a "
          + "DataSource or a JDBC container, not " + value.getClass().getName() + ".");
    }
    try {
      if (!(Boolean) value.getClass().getMethod("isRunning").invoke(value)) {
        value.getClass().getMethod("start").invoke(value);
      }
    } catch (NoSuchMethodException e) {
      // Not a container with a life cycle: use it as it is.
    }
    return new UrlDataSource(value.getClass().getClassLoader(),
        (String) jdbcUrl.invoke(value), call(value, "getUsername"), call(value, "getPassword"));
  }

  private static String call(Object target, String method) throws Exception {
    try {
      return (String) target.getClass().getMethod(method).invoke(target);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  private static String lines(List<String> problems) {
    StringBuilder text = new StringBuilder();
    problems.forEach(problem -> text.append(System.lineSeparator()).append("  - ")
        .append(problem));
    return text.toString();
  }

  /** Connections from a JDBC URL, with the driver found on the class loader. */
  private static final class UrlDataSource implements DataSource {
    private final ClassLoader loader;
    private final String url;
    private final String user;
    private final String password;

    UrlDataSource(ClassLoader loader, String url, String user, String password) {
      this.loader = loader;
      this.url = url;
      this.user = user;
      this.password = password;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return RuntimeJdbc.connect(loader, url, user, password);
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
      return RuntimeJdbc.connect(loader, url, user, password);
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
      if (type.isInstance(this)) {
        return type.cast(this);
      }
      throw new SQLException("Not a wrapper for " + type.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
      return type.isInstance(this);
    }

    @Override
    public String toString() {
      return RuntimeJdbc.sanitizeUrl(url);
    }
  }
}
