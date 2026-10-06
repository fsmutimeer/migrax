package io.migrax.model.fixtures;

@Entity
@Table(name = "mapping_target")
public class MappingTarget {
  @Id
  private Long id;

  @ManyToMany(mappedBy = "targets")
  private java.util.List<MappingOwner> owners;
}
