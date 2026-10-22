package org.migrax.micronautdata.fixtures;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

@MappedEntity
public record Author(@Id @GeneratedValue(GeneratedValue.Type.SEQUENCE) Long id,
                     String fullName) {}
