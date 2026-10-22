package org.migrax.micronautdata.fixtures;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import java.util.UUID;

@MappedEntity
public record Tag(@Id @GeneratedValue UUID id, String name) {}
