package io.migrax.cli;

import java.util.List;

/** "Did you mean" suggestions for mistyped commands and options. */
final class Suggestions {
  private Suggestions() {}

  /** The candidate closest to the input, or null when none is close enough. */
  static String closest(String input, List<String> candidates) {
    String best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (String candidate : candidates) {
      int distance = distance(input, candidate);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = candidate;
      }
    }
    return best != null && (bestDistance <= 2 || best.startsWith(input) && input.length() >= 3)
        ? best : null;
  }

  private static int distance(String a, String b) {
    int[] previous = new int[b.length() + 1];
    int[] current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
            previous[j - 1] + cost);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[b.length()];
  }
}
