package org.migrax.cli;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DoctorUrlKindTest {

  private static final String NOT_A_URL = "it does not look like a URL";

  @Test
  void showsOnlyTheSchemeNeverWhatComesAfterIt() {
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("postgres://shop:secret@db:5432/shop?sslmode=require",
        "it starts with postgres://");
    expected.put("postgresql+psycopg2://u:p@db/shop", "it starts with postgresql+...://");
    expected.put("postgresql+Tiger9secret://u:p@db/shop", "it starts with postgresql+...://");
    expected.put("sqlite:///shop.db", "it starts with sqlite://");
    // Forms that password masking misses: none of their text may appear.
    expected.put("postgres://db/shop?passwd=secret", "it starts with postgres://");
    expected.put("mysql://db/shop?pw=secret", "it starts with mysql://");
    expected.put("postgres://\u001b[31msecret", "it starts with postgres://");
    expected.put("shop:secret@db/shop", NOT_A_URL);
    expected.put("secret", NOT_A_URL);
    expected.put(" secret ", NOT_A_URL);
    expected.put("", NOT_A_URL);
    // Longer than any real scheme: could be a token, so it is not shown either.
    expected.put("a".repeat(32) + "://db/shop", NOT_A_URL);
    // A scheme Migrax doesn't know could be a token too: only that it is a URL is shown.
    expected.put("Tiger9secret://db/shop", "it starts with a URL scheme");
    expected.put("postgres+" + "b".repeat(21) + "://db", NOT_A_URL);
    expected.forEach((value, kind) -> assertEquals(kind, DoctorCommand.urlKind(value), value));
    assertEquals(NOT_A_URL, DoctorCommand.urlKind(null));
  }
}
