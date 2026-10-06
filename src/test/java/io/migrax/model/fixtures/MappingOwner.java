package io.migrax.model.fixtures;

import java.util.List;

@Entity
@Table(name = "mapping_owner")
public class MappingOwner {
  @Id
  private Long id;

  @ManyToMany
  @JoinTable(
      name = "owner_target_link",
      joinColumns = @JoinColumn(name = "owner_key", referencedColumnName = "id"),
      inverseJoinColumns = @JoinColumn(name = "target_key", referencedColumnName = "id"))
  private List<MappingTarget> targets;

  @OneToMany
  @JoinTable(
      name = "owner_child_link",
      joinColumns = @JoinColumn(name = "owner_key", referencedColumnName = "id"),
      inverseJoinColumns = @JoinColumn(name = "child_key", referencedColumnName = "id"))
  private List<MappingChild> children;

  @OneToMany
  @JoinColumn(name = "owner_ref", referencedColumnName = "id")
  private List<MappingChild> childrenByForeignKey;
}
