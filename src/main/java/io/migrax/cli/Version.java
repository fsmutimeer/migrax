package io.migrax.cli;

/** The Migrax version. */
final class Version {
  private Version() {}

  /** The version from the jar manifest, or {@code dev} when running from classes. */
  static String current() {
    String version = Version.class.getPackage().getImplementationVersion();
    return version == null ? "dev" : version;
  }
}
