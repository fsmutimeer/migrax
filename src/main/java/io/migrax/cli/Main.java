package io.migrax.cli;

import io.migrax.dialect.Dialect;
import io.migrax.dialect.Dialects;
import io.migrax.diff.DiffEngine;
import io.migrax.diff.SnapshotStore;
import io.migrax.model.JpaExtractor;
import io.migrax.model.SchemaModel;
import io.migrax.ops.Operation;
import io.migrax.plugin.DatabaseSchemaReader;
import io.migrax.plugin.ProjectDatabaseConfig;
import io.migrax.plugin.RuntimeJdbc;
import io.migrax.runner.MigrationRunner;
import io.migrax.util.Json;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class Main {
  private static final Pattern NUMBERED_MIGRATION = Pattern.compile("^(\\d+)_.*");

  private Main() {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0 || has(args, "--help") || has(args, "-h")) {
      help();
      return;
    }

    String command = args[0];
    Path root = ProjectContext.findRoot(Path.of(option(args, "--dir", ".")));
    Path snapshot = SnapshotStore.projectSnapshot(root);
    switch (command) {
      case "inspect" -> {
        try (URLClassLoader loader = ProjectContext.runtimeLoader(root, args)) {
          withContextLoader(loader, () ->
              System.out.println(Json.write(new JpaExtractor().extract(requirePackage(root, args)))));
        }
      }
      case "plan" -> {
        try (URLClassLoader loader = ProjectContext.runtimeLoader(root, args)) {
          withContextLoader(loader, () -> plan(root, requirePackage(root, args), snapshot, args));
        }
      }
      case "generate" -> generate(root, snapshot, args);
      case "check" -> {
        try (URLClassLoader loader = ProjectContext.runtimeLoader(root, args)) {
          withContextLoader(loader, () -> {
            SchemaModel current = new JpaExtractor().extract(requirePackage(root, args));
            SchemaModel old = Files.exists(snapshot) ? SnapshotStore.load(snapshot) : SchemaModel.empty();
            List<Operation> operations = new DiffEngine().diff(old, current);
            if (!operations.isEmpty()) {
              System.err.println("Schema changes detected: " + operations.size());
              System.exit(2);
            }
            System.out.println("OK");
          });
        }
      }
      case "sql" -> {
        if (args.length < 2) {
          throw new IllegalArgumentException("sql <migration.sql>");
        }
        System.out.println(Files.readString(root.resolve(args[1])));
      }
      case "migrate" -> migrate(root, args);
      case "doctor" -> doctor(root, args);
      default -> throw new IllegalArgumentException("Unknown command: " + command);
    }
  }

  private static void plan(Path root, String packageName, Path snapshot, String[] args)
      throws Exception {
    SchemaModel current = new JpaExtractor().extract(packageName);
    SchemaModel old = Files.exists(snapshot) ? SnapshotStore.load(snapshot) : SchemaModel.empty();
    List<Operation> operations = new DiffEngine().diff(old, current);
    if (operations.isEmpty()) {
      System.out.println("No changes.");
      return;
    }
    Dialect dialect = dialectByName(option(args, "--dialect", "postgresql"));
    for (int i = 0; i < operations.size(); i++) {
      Operation operation = operations.get(i);
      System.out.printf("%02d %-18s %s%s%n", i + 1, operation.kind(), dialect.render(operation),
          operation.destructive() ? "  [DESTRUCTIVE]" : "");
    }
  }

  private static void generate(Path root, Path snapshot, String[] args) throws Exception {
    ProjectDatabaseConfig.Credentials credentials = credentials(root, args);
    String jdbcUrl = credentials.url();
    if (jdbcUrl == null || jdbcUrl.isBlank()) {
      throw new IllegalArgumentException(
          "No JDBC URL found. Configure application.properties/application.yml, "
              + "MIGRAX_DATABASE_URL, or --url.");
    }

    try (URLClassLoader loader = ProjectContext.runtimeLoader(root, args)) {
      withContextLoader(loader, () -> {
        String packageName = requirePackage(root, args);
        SchemaModel current = new JpaExtractor().extract(packageName);
        if (current.tables().isEmpty()) {
          throw new IllegalArgumentException(
              "No JPA entities found under package '" + packageName
                  + "'. Set MIGRAX_PACKAGE or pass --package.");
        }

        boolean hasSnapshot = Files.exists(snapshot);
        SchemaModel databaseBaseline = null;
        if (!hasSnapshot) {
          try (Connection connection =
                   RuntimeJdbc.connect(loader, jdbcUrl, credentials.user(), credentials.password())) {
            databaseBaseline = DatabaseSchemaReader.read(connection);
          }
          System.out.println("No snapshot found; using the connected database schema as the baseline.");
        }
        SchemaModel previous = hasSnapshot
            ? SnapshotStore.load(snapshot)
            : databaseBaseline == null ? SchemaModel.empty() : databaseBaseline;
        current = current.withPrimaryKeyNamesFrom(previous);
        List<Operation> operations = new DiffEngine().diff(previous, current);
        if (operations.isEmpty()) {
          System.out.println("No schema changes detected.");
          if (!hasSnapshot) {
            SnapshotStore.save(snapshot, current);
            System.out.println("Saved the current entity schema as the baseline snapshot.");
          }
          return;
        }
        if (!has(args, "--allow-destructive")
            && operations.stream().anyMatch(Operation::destructive)) {
          throw new IllegalStateException(
              "Destructive schema changes detected. Review the change, then rerun with "
                  + "--allow-destructive.");
        }

        Dialect dialect = option(args, "--dialect", null) == null
            ? Dialects.fromJdbcUrl(jdbcUrl)
            : dialectByName(option(args, "--dialect", "postgresql"));
        Path migrations = migrationDirectory(root, credentials.locations());
        Files.createDirectories(migrations);
        String name = option(args, "--name", null);
        if (name == null || name.isBlank()) {
          name = nextMigrationName(migrations, previous.tables().isEmpty());
        } else if (name.endsWith(".sql")) {
          name = name.substring(0, name.length() - ".sql".length());
        }

        StringBuilder sql = new StringBuilder("-- Generated by Migrax\n\n");
        for (Operation operation : operations) {
          sql.append(dialect.render(operation)).append(";\n");
        }
        Path migration = migrations.resolve(name + ".sql");
        if (Files.exists(migration)) {
          throw new IllegalArgumentException("Migration already exists: " + migration);
        }
        Files.writeString(migration, sql);
        SnapshotStore.save(snapshot, current);
        System.out.println("Generated " + migration.toAbsolutePath()
            + " with " + operations.size() + " operation(s). Review it before applying.");
      });
    }
  }

  private static void migrate(Path root, String[] args) throws Exception {
    ProjectDatabaseConfig.Credentials credentials = credentials(root, args);
    if (credentials.url() == null || credentials.url().isBlank()) {
      throw new IllegalArgumentException(
          "No JDBC URL found. Configure application.properties/application.yml, "
              + "MIGRAX_DATABASE_URL, or --url.");
    }

    Path migrations = migrationDirectory(root, credentials.locations());
    List<Path> files;
    if (Files.isDirectory(migrations)) {
      try (Stream<Path> paths = Files.list(migrations)) {
        files = paths.filter(path -> path.getFileName().toString().endsWith(".sql"))
            .sorted()
            .toList();
      }
    } else {
      files = List.of();
    }

    try (URLClassLoader loader = ProjectContext.runtimeLoader(root, args);
         Connection connection = RuntimeJdbc.connect(
             loader, credentials.url(), credentials.user(), credentials.password())) {
      int applied = new MigrationRunner().migrate(connection, files, has(args, "--resume"));
      System.out.println("Found " + files.size() + " migration file(s); applied " + applied
          + " new migration(s) (" + (files.size() - applied) + " already applied).");
    }
  }

  private static ProjectDatabaseConfig.Credentials credentials(Path root, String[] args)
      throws Exception {
    return ProjectDatabaseConfig.load(
        root.resolve("src/main/resources"),
        Map.of(
            "url", option(args, "--url", ""),
            "user", option(args, "--user", ""),
            "password", option(args, "--password", ""),
            "locations", option(args, "--locations", "")));
  }

  private static Path migrationDirectory(Path root, String location) {
    return ProjectDatabaseConfig.resolveMigrationDirectory(
        location, root, root.resolve("src/main/resources"));
  }

  private static String requirePackage(Path root, String[] args) throws Exception {
    String packageName = ProjectContext.packageName(root, option(args, "--package", null));
    if (packageName == null || packageName.isBlank()) {
      throw new IllegalArgumentException(
          "Entity package is required. Pass --package or set MIGRAX_PACKAGE.");
    }
    return packageName;
  }

  private static String nextMigrationName(Path migrations, boolean baselineIsEmpty)
      throws Exception {
    int latest = 0;
    try (Stream<Path> paths = Files.list(migrations)) {
      for (Path path : paths.filter(file -> file.getFileName().toString().endsWith(".sql")).toList()) {
        String filename = path.getFileName().toString();
        Matcher matcher = NUMBERED_MIGRATION.matcher(filename.substring(0, filename.length() - 4));
        if (matcher.matches()) {
          latest = Math.max(latest, Integer.parseInt(matcher.group(1)));
        }
      }
    }
    if (latest == 0 && baselineIsEmpty) {
      return "0001_initial";
    }
    return String.format(Locale.ROOT, "%04d_auto_%s", latest + 1,
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
  }

  private static void doctor(Path root, String[] args) throws Exception {
    ProjectDatabaseConfig.Credentials credentials = credentials(root, args);
    System.out.println("Migrax project: " + root.toAbsolutePath());
    System.out.println("Snapshot: " + SnapshotStore.projectSnapshot(root));
    System.out.println("Migrations: " + migrationDirectory(root, credentials.locations()));
    System.out.println("Supported dialects: postgresql, mysql, mariadb, sqlserver, oracle, h2");
  }

  private static Dialect dialectByName(String name) {
    return switch (name.toLowerCase(Locale.ROOT)) {
      case "postgres", "postgresql" -> Dialects.fromJdbcUrl("jdbc:postgresql://localhost/");
      case "mysql" -> Dialects.fromJdbcUrl("jdbc:mysql://localhost/");
      case "mariadb" -> Dialects.fromJdbcUrl("jdbc:mariadb://localhost/");
      case "sqlserver", "mssql" -> Dialects.fromJdbcUrl("jdbc:sqlserver://localhost/");
      case "oracle" -> Dialects.fromJdbcUrl("jdbc:oracle:thin:@localhost:1521:xe");
      case "h2" -> Dialects.fromJdbcUrl("jdbc:h2:mem:migrax");
      default -> throw new IllegalArgumentException("Unknown dialect: " + name);
    };
  }

  private static String option(String[] args, String key, String defaultValue) {
    for (int i = 0; i < args.length; i++) {
      if (args[i].startsWith(key + "=")) {
        return args[i].substring(key.length() + 1);
      }
      if (args[i].equals(key) && i + 1 < args.length) {
        return args[i + 1];
      }
    }
    return defaultValue;
  }

  private static boolean has(String[] args, String key) {
    return Arrays.stream(args).anyMatch(arg -> arg.equals(key));
  }

  private static void withContextLoader(URLClassLoader loader, ThrowingRunnable action)
      throws Exception {
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    thread.setContextClassLoader(loader);
    try {
      action.run();
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  private static void help() {
    System.out.println("""
        Migrax 0.1.0

        Commands: inspect, plan, generate, migrate, check, sql, doctor
        Options: --package <package> --dir <project-dir> --locations <migration-path>
                 --dialect <dialect> --url <jdbc-url> --user <user> --password <password>
                 --classpath <path-separator-separated-runtime-classpath>
        Generate: --name <name> --allow-destructive
        Migrate:  --resume

        The application must already be compiled. Set MIGRAX_CLASSPATH to its
        compiled classes and JPA/JDBC runtime dependencies when they are not in
        a standard build output directory.
        """);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
