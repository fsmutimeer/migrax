package org.migrax.micronautdata.fixtures;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;

@MappedEntity
public record Page(@Id @GeneratedValue Long id, int number,
                   @Relation(Relation.Kind.MANY_TO_ONE) Book book) {}
