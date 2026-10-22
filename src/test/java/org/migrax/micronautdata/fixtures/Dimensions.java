package org.migrax.micronautdata.fixtures;

import io.micronaut.data.annotation.Embeddable;

@Embeddable
public record Dimensions(int width, int height) {}
