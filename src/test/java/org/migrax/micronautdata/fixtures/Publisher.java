package org.migrax.micronautdata.fixtures;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

@MappedEntity("Publisher")
public record Publisher(@Id @GeneratedValue Long id, @MappedProperty("Name") String name) {}
