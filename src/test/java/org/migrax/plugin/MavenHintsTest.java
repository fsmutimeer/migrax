package org.migrax.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavenHintsTest {

  @Test
  void rewritesCliHintsForMaven() {
    assertEquals("Review them, then run 'mvn migrax:generate -Dmigrax.allowDestructive=true'. "
            + "If a column or table was renamed, pass -Dmigrax.renames=t.old=new or "
            + "-Dmigrax.renameTables=old=new instead. Add -Dmigrax.keepData=true to keep "
            + "dropped data for a rollback.",
        AbstractMigraxMojo.mavenHints("Review them, then run 'migrax generate "
            + "--allow-destructive'. If a column or table was renamed, pass --rename t.old=new "
            + "or --rename-table old=new instead. Add --keep-data to keep dropped data for a "
            + "rollback."));
    // --keep-data is rewritten where a hint says to pass it, not in other messages.
    assertEquals("Use -Dmigrax.keepData=true with -Dmigrax.allowDestructive=true to keep "
            + "dropped data.",
        AbstractMigraxMojo.mavenHints("Use --keep-data with --allow-destructive to keep "
            + "dropped data."));
    assertEquals("--keep-data can't keep orders.note: no key.",
        AbstractMigraxMojo.mavenHints("--keep-data can't keep orders.note: no key."));
    // Commands without a Maven goal are left alone.
    assertEquals("Run 'migrax doctor'.", AbstractMigraxMojo.mavenHints("Run 'migrax doctor'."));
  }
}
