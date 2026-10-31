package org.migrax.cli;

import org.migrax.util.Json;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonOutTest {
  @Test
  void writesNonAsciiAsEscapesSoTheOutputIsPlainAscii() {
    // A class name, an accented column and a character outside the BMP (two UTF-16 units).
    String written = JsonOut.write(JsonOut.object("entity", "shop.Kündé",
        "column", "prénom", "note", "😀 ok", "plain", "a\"b\\c\n"));
    assertEquals("{\"entity\":\"shop.K\\u00fcnd\\u00e9\",\"column\":\"pr\\u00e9nom\","
        + "\"note\":\"\\ud83d\\ude00 ok\",\"plain\":\"a\\\"b\\\\c\\n\"}", written);
    assertTrue(written.chars().allMatch(c -> c < 0x7F), written);

    // Any JSON reader gets the original text back.
    Object parsed = new Json.Parser(written).parse();
    assertEquals(Map.of("entity", "shop.Kündé", "column", "prénom", "note", "😀 ok",
        "plain", "a\"b\\c\n"), parsed);
  }

  @Test
  void asciiTextIsWrittenAsBefore() {
    assertEquals("{\"keys\":[\"customers.email\",\"x~y\"]}",
        JsonOut.write(JsonOut.object("keys", List.of("customers.email", "x~y"))));
  }

  @Test
  void renameHintsQuoteNamesAShellWouldInterpret() {
    assertEquals("customers.name=full_name", EntityChanges.shellWord("customers.name=full_name"));
    assertEquals("'wide.old=new$(echo pwned)'",
        EntityChanges.shellWord("wide.old=new$(echo pwned)"));
    assertEquals("'t.it'\\''s=its'", EntityChanges.shellWord("t.it's=its"));
  }
}
