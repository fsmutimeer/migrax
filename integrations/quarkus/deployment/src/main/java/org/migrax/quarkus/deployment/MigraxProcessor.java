package org.migrax.quarkus.deployment;

import io.quarkus.agroal.spi.JdbcDataSourceBuildItem;
import io.quarkus.agroal.spi.JdbcDataSourceSchemaReadyBuildItem;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.arc.deployment.ExcludedTypeBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.ApplicationArchive;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.migrax.quarkus.MigraxRecorder;
import org.migrax.runner.MigrationIndex;

/** Build steps of the Migrax extension. */
class MigraxProcessor {
  private static final String FEATURE = "migrax";
  /** By name: loading the class needs the health API, which not every application has. */
  private static final String READINESS_CHECK = "org.migrax.quarkus.MigraxReadinessCheck";
  private static final DotName JAVA_MIGRATION = DotName.createSimple("org.migrax.api.JavaMigration");

  @BuildStep
  FeatureBuildItem feature() {
    return new FeatureBuildItem(FEATURE);
  }

  /**
   * Keeps the beans Migrax looks up when the migrations run, which nothing injects: the
   * application's MigrationListeners, its MeterRegistry and its OpenTelemetry.
   */
  @BuildStep
  io.quarkus.arc.deployment.UnremovableBeanBuildItem listeners() {
    return io.quarkus.arc.deployment.UnremovableBeanBuildItem.beanTypes(
        DotName.createSimple("org.migrax.api.MigrationListener"),
        DotName.createSimple("io.micrometer.core.instrument.MeterRegistry"),
        DotName.createSimple("io.opentelemetry.api.OpenTelemetry"));
  }

  /**
   * Metrics (replayed once Quarkus attaches its registries) and spans of the migrations, only
   * in applications with Micrometer or OpenTelemetry. Elsewhere their classes are kept out of
   * the application, or a native build would fail on the missing types.
   */
  @BuildStep
  void observability(BuildProducer<AdditionalBeanBuildItem> beans,
                     BuildProducer<ExcludedTypeBuildItem> excluded) {
    optionalBean("org.migrax.quarkus.MigraxMetricsReplay",
        "io.micrometer.core.instrument.MeterRegistry", beans, excluded);
    optionalBean("org.migrax.quarkus.MigraxTracing", "io.opentelemetry.api.OpenTelemetry",
        beans, excluded);
  }

  private static void optionalBean(String bean, String needs,
                                   BuildProducer<AdditionalBeanBuildItem> beans,
                                   BuildProducer<ExcludedTypeBuildItem> excluded) {
    if (io.quarkus.bootstrap.classloading.QuarkusClassLoader.isClassPresentAtRuntime(needs)) {
      beans.produce(AdditionalBeanBuildItem.unremovableOf(bean));
    } else {
      excluded.produce(new ExcludedTypeBuildItem(bean));
    }
  }

  /**
   * The migrax readiness check, only in applications with the health extension. Elsewhere its
   * health API classes are missing, so it is kept out of bean discovery.
   */
  @BuildStep
  void readinessCheck(Capabilities capabilities, BuildProducer<AdditionalBeanBuildItem> beans,
                      BuildProducer<ExcludedTypeBuildItem> excluded) {
    if (capabilities.isPresent(Capability.SMALLRYE_HEALTH)) {
      beans.produce(AdditionalBeanBuildItem.unremovableOf(READINESS_CHECK));
    } else {
      excluded.produce(new ExcludedTypeBuildItem(READINESS_CHECK));
    }
  }

  /**
   * Lists the migrations while the application is built, because a native executable can't
   * list its classpath: the SQL files in the migrax.locations folders and the Java migration
   * classes. Both modes read the migrations from this list.
   */
  @BuildStep
  void listMigrations(ApplicationArchivesBuildItem archives, CombinedIndexBuildItem combined,
                      BuildProducer<GeneratedResourceBuildItem> generated,
                      BuildProducer<NativeImageResourceBuildItem> resources,
                      BuildProducer<ReflectiveClassBuildItem> reflective,
                      BuildProducer<ServiceProviderBuildItem> services) {
    // The folders of the default database and of each further one (migrax.databases.<name>).
    org.eclipse.microprofile.config.Config config = ConfigProvider.getConfig();
    java.util.Map<String, String> settings = new java.util.LinkedHashMap<>();
    for (String name : config.getPropertyNames()) {
      if (name.startsWith("migrax.")) {
        config.getOptionalValue(name, String.class).ifPresent(value -> settings.put(name, value));
      }
    }
    List<String> databases = new ArrayList<>(List.of(org.migrax.runner.Databases.DEFAULT));
    databases.addAll(org.migrax.runner.Databases.names(settings));
    List<String> folders = new ArrayList<>();
    for (String database : databases) {
      String locations = org.migrax.runner.Databases.settings(database, settings)
          .getOrDefault("migrax.locations", "db/migration");
      for (String location : locations.split(",")) {
        String folder = location.trim().replace("classpath:", "").replaceAll("^/+", "")
            .replaceAll("/+$", "");
        if (!folder.isEmpty() && !folders.contains(folder)) {
          folders.add(folder);
        }
      }
    }
    Set<String> sql = new TreeSet<>();
    for (ApplicationArchive archive : archives.getAllApplicationArchives()) {
      archive.accept(tree -> tree.walk(visit -> {
        String path = visit.getRelativePath("/");
        for (String folder : folders) {
          if (path.startsWith(folder + "/") && path.endsWith(".sql")
              && path.indexOf('/', folder.length() + 1) < 0) {
            sql.add(path);
          }
        }
      }));
    }
    Set<String> javaMigrations = new TreeSet<>();
    for (ClassInfo type : combined.getIndex().getAllKnownImplementors(JAVA_MIGRATION)) {
      if (!type.isInterface() && !java.lang.reflect.Modifier.isAbstract(type.flags())) {
        javaMigrations.add(type.name().toString());
      }
    }
    MigrationIndex index = new MigrationIndex(folders, new ArrayList<>(sql),
        new ArrayList<>(javaMigrations));
    generated.produce(new GeneratedResourceBuildItem(MigrationIndex.RESOURCE,
        index.format().getBytes(StandardCharsets.UTF_8)));
    List<String> files = new ArrayList<>(sql);
    files.add(MigrationIndex.RESOURCE);
    resources.produce(new NativeImageResourceBuildItem(files));
    if (!javaMigrations.isEmpty()) {
      reflective.produce(ReflectiveClassBuildItem.builder(javaMigrations.toArray(String[]::new))
          .constructors().build());
    }
    services.produce(ServiceProviderBuildItem.allProvidersFromClassPath(
        "org.migrax.dialect.Dialect"));
  }

  /**
   * Puts the project's .migrax/snapshot.json (and each further database's) into the
   * application as META-INF/migrax/snapshot.json, so it can check for drift when it starts
   * (migrax.drift.check), also as a native executable. A snapshot the application already
   * has, for example from the Maven goal, is left as it is.
   */
  @BuildStep
  void packageSnapshot(OutputTargetBuildItem target, ApplicationArchivesBuildItem archives,
                       BuildProducer<GeneratedResourceBuildItem> generated,
                       BuildProducer<NativeImageResourceBuildItem> resources)
      throws java.io.IOException {
    java.nio.file.Path project = target.getOutputDirectory().toAbsolutePath().getParent();
    if (project == null) {
      return;
    }
    Set<String> present = new TreeSet<>();
    archives.getRootArchive().accept(tree -> tree.walk(visit -> {
      String path = visit.getRelativePath("/");
      if (path.startsWith("META-INF/migrax/") && path.endsWith("/snapshot.json")) {
        present.add(path);
      }
    }));
    List<String> packaged = new ArrayList<>();
    for (java.util.Map.Entry<String, java.nio.file.Path> snapshot
        : org.migrax.diff.SnapshotStore.packaged(project).entrySet()) {
      if (present.contains(snapshot.getKey())) {
        packaged.add(snapshot.getKey());
        continue;
      }
      generated.produce(new GeneratedResourceBuildItem(snapshot.getKey(),
          java.nio.file.Files.readAllBytes(snapshot.getValue())));
      packaged.add(snapshot.getKey());
    }
    if (!packaged.isEmpty()) {
      resources.produce(new NativeImageResourceBuildItem(packaged));
    }
  }

  /**
   * Runs the migrations while the application starts, and tells Hibernate ORM that the schema
   * is ready: Hibernate starts its persistence units only after this step.
   */
  @BuildStep
  @Record(ExecutionTime.RUNTIME_INIT)
  void migrate(MigraxRecorder recorder, BeanContainerBuildItem beans,
               List<JdbcDataSourceBuildItem> dataSources,
               BuildProducer<JdbcDataSourceSchemaReadyBuildItem> schemaReady) {
    recorder.migrate();
    schemaReady.produce(new JdbcDataSourceSchemaReadyBuildItem(
        dataSources.stream().map(JdbcDataSourceBuildItem::getName).toList()));
  }
}
