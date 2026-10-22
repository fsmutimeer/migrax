package com.matrix;

import io.micronaut.data.annotation.Embeddable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.annotation.Relation;
import java.util.UUID;

/** The other entities of the fixture. */
final class Values {
  private Values() {}
}

/** An id from a sequence (a plain identity on H2, where Micronaut Data refuses sequences). */
@MappedEntity
record Author(@Id @GeneratedValue(GeneratedValue.Type.SEQUENCE) Long id, String fullName) {}

@MappedEntity
record Page(@Id @GeneratedValue Long id, int number,
            @Relation(Relation.Kind.MANY_TO_ONE) Book book) {}

/** An id the database generates. */
@MappedEntity
record Tag(@Id @GeneratedValue UUID id, String name) {}

@Embeddable
record Dimensions(int width, int height) {}

/** Names with capitals: quoted by Micronaut Data, so they keep their case on PostgreSQL. */
@MappedEntity("Publisher")
record Publisher(@Id @GeneratedValue Long id, @MappedProperty("Name") String name) {}
