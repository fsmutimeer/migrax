package io.migrax.model.fixtures;

@Entity
@Table(name = "column_definition_test")
public class ColumnDefinitionEntity {
  @Id
  private Long id;

  @Column(name = "description", columnDefinition = "TEXT", nullable = false)
  private String description;
}
