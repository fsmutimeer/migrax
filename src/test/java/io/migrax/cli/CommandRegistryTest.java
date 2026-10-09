package io.migrax.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Checks every registered command, so a new command can't ship with broken help. */
class CommandRegistryTest {
  private final CommandRegistry commands = CommandRegistry.standard();

  @Test
  void namesAndAliasesAreUniqueAndLowerCase() {
    Set<String> seen = new HashSet<>();
    for (Command command : commands.all()) {
      assertTrue(seen.add(command.name()), "duplicate name " + command.name());
      assertEquals(command.name().toLowerCase(), command.name());
      for (String alias : command.aliases()) {
        assertTrue(seen.add(alias), "duplicate alias " + alias);
      }
    }
  }

  @Test
  void everyCommandHasHelp() {
    for (Command command : commands.all()) {
      assertNotNull(command.group(), command.name());
      assertFalse(command.summary().isBlank(), command.name());
      assertTrue(command.usage().startsWith("migrax " + command.name()), command.name());
      assertFalse(command.description().isBlank(), command.name());
    }
  }

  @Test
  void listedOptionsAreKnownAndDocumented() {
    for (Command command : commands.all()) {
      for (String option : command.options()) {
        assertTrue(Options.VALUES.contains(option) || Options.FLAGS.contains(option),
            command.name() + " lists unknown option " + option);
        assertNotNull(Options.help(option), "no help text for " + option);
      }
    }
  }

  @Test
  void resolvesAliasesAndSuggestsTypos() {
    assertSame(commands.resolve("generate"), commands.resolve("MakeMigrations"));
    UsageException error = assertThrows(UsageException.class, () -> commands.resolve("generat"));
    assertTrue(error.hint.contains("Did you mean 'migrax generate'?"), error.hint);
  }
}
