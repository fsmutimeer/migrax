package io.migrax.dialect;

import io.migrax.ops.Operation;

public interface Dialect {
    String id();
    String quote(String identifier);
    String render(Operation operation);
    default boolean transactionalDdl() { return true; }
}
