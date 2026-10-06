package io.migrax.model.compositefixtures;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@interface Entity {}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@interface Id {}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@interface ManyToMany {}

@Entity
class CompositeTarget {
  @Id
  private String tenantId;

  @Id
  private Long recordId;
}

@Entity
public class CompositeEntity {
  @Id
  private Long id;

  @ManyToMany
  private java.util.List<CompositeTarget> targets;
}
