package io.migrax.ops;

import java.util.List;
import java.util.stream.Stream;

/**
 * Factory and utility methods for building and inspecting {@link Operation} lists.
 *
 * @since 0.1.0
 */
public final class Operations {

    private Operations() {}

    /**
     * Returns an empty, immutable list of operations.
     *
     * @return an empty operation list
     * @since 0.1.0
     */
    public static List<Operation> none() {
        return List.of();
    }

    /**
     * Merges two operation lists into a single immutable list, preserving order.
     *
     * @param first  the leading operations
     * @param second the trailing operations
     * @return merged, immutable list
     * @since 0.1.0
     */
    public static List<Operation> concat(
            List<? extends Operation> first, List<? extends Operation> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }

    /**
     * Returns {@code true} if any operation in the list is destructive (e.g., drops a table or
     * column).
     *
     * @param operations the list to inspect
     * @return {@code true} if any operation is destructive
     * @since 0.1.0
     */
    public static boolean hasDestructive(List<? extends Operation> operations) {
        return operations.stream().anyMatch(Operation::destructive);
    }
}
