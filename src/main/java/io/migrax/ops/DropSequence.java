package io.migrax.ops;
public record DropSequence(String name) implements Operation { public String kind(){return "drop_sequence";} public boolean destructive(){return true;} }
