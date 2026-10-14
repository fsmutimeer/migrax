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
    List<String> folders = new ArrayList<>();
    for (String location : ConfigProvider.getConfig()
        .getOptionalValue("migrax.locations", String.class).orElse("db/migration").split(",")) {
      String folder = location.trim().replace("classpath:", "").replaceAll("^/+", "")
          .replaceAll("/+$", "");
      if (!folder.isEmpty()) {
        folders.add(folder);
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
