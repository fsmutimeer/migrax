package org.migrax.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LogTest {
  private static final String ESC = String.valueOf((char) 27);

  @Test
  void terminalTextKeepsLineBreaksButNotEscapeSequences() {
    String plain = "Applied 1 migration(s).\r\n\tdone\n";
    assertSame(plain, Log.terminalText(plain));
    assertEquals("x\\u001b]0;pwned\\u0007\ny",
        Log.terminalText("x" + ESC + "]0;pwned\u0007\ny"));
    assertEquals("a\\u009bb", Log.terminalText("a\u009bb"));
    // A lone carriage return would let the rest overwrite the line.
    assertEquals("ok\\u000dfake", Log.terminalText("ok\rfake"));
    assertEquals("end\\u000d", Log.terminalText("end\r"));
  }

  @Test
  void theConsoleShowsControlCharactersAsText() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Log.Sink previous = Log.setSink(Log.console(
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8), true));
    try {
      Log.info("table {}", "x" + ESC + "[2Jy");
      Log.warn("table {}", "x" + ESC + "[2Jy");
      Log.debug("table {}", "x" + ESC + "[2Jy");
    } finally {
      Log.setSink(previous);
    }
    String nl = System.lineSeparator();
    assertEquals("table x\\u001b[2Jy" + nl + "debug: table x\\u001b[2Jy" + nl,
        out.toString(StandardCharsets.UTF_8));
    assertEquals("warning: table x\\u001b[2Jy" + nl, err.toString(StandardCharsets.UTF_8));
  }
}
