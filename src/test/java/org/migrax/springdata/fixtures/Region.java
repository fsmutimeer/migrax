package org.migrax.springdata.fixtures;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Sequence;
import org.springframework.data.relational.core.mapping.Table;

/** An aggregate with a sequence id; other aggregates refer to it by id. */
@Table
public class Region {
  @Id @Sequence(sequence = "region_seq") Long id;
  String code;
}
