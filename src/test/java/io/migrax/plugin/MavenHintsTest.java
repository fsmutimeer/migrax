package io.migrax.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavenHintsTest {

  @Test
  void rewritesCliHintsForMaven() {
    assertEquals("Review them, then run 'mvn migrax:generate -Dmigrax.allowDestructive=true'. "
            + "If a column or table was renamed, pass -Dmigrax.renames=t.old=new or "
            + "-Dmigrax.renameTables=old=new instead.",
        AbstractMigraxMojo.mavenHints("Review them, then run 'migrax generate "
            + "--allow-destructive'. If a column or table was renamed, pass --rename t.old=new "
            + "or --rename-table old=new instead."));
    // Commands without a Maven goal are left alone.
    assertEquals("Run 'migrax doctor'.", AbstractMigraxMojo.mavenHints("Run 'migrax doctor'."));
  }
}
