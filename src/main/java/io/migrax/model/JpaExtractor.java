package io.migrax.model;

import java.io.*;
import java.lang.reflect.*;
import java.lang.annotation.Annotation;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/**
 * Framework-neutral JPA entity scanner and schema extractor.
 *
 * <p>Deliberately uses reflection against annotation <em>simple names</em> so the core module has
 * no compile dependency on either {@code javax.persistence} or {@code jakarta.persistence}. The
 * application being inspected supplies its own JPA/Hibernate annotations at runtime.
 *
 * <p>This class is <strong>thread-safe</strong>: all mutable extraction state is local to
 * {@link #extract(String)} and not shared across invocations.
 *
 * @since 0.1.0
 */
public final class JpaExtractor {
  // All mutable state is kept inside extract() to ensure thread-safety.
  // Only immutable configuration may be stored in fields.
  private final NamingStrategy naming;

  /** Creates an extractor using Spring Boot's default naming (snake_case). */
  public JpaExtractor() {
    this(NamingStrategy.SPRING);
  }

  /**
   * Creates an extractor with the given naming strategy.
   *
   * @param naming how table and column names are derived; must match the application's
   *     Hibernate configuration
   * @since 0.1.0
   */
  public JpaExtractor(NamingStrategy naming) {
    this.naming = Objects.requireNonNull(naming, "naming");
  }

  /**
   * Extracts a {@link SchemaModel} from all JPA {@code @Entity} classes found under the given
   * base package on the current thread's context class loader.
   *
   * @param basePackage the root package to scan (e.g., {@code "com.example"})
   * @return a snapshot of the entity-derived schema
   * @throws Exception if class scanning or reflection fails
   * @since 0.1.0
   */
  public SchemaModel extract(String basePackage) throws Exception {
    // Per-invocation mutable state — this method is re-entrant and thread-safe.
    Map<Class<?>, String> tableNames = new HashMap<>();
    Map<Class<?>, SchemaModel.Table> built = new HashMap<>();
    List<SchemaModel.Sequence> sequences = new ArrayList<>();
    List<DeferredOneToMany> deferredOneToMany = new ArrayList<>();

    ExtractionContext ctx = new ExtractionContext(tableNames, built, sequences, deferredOneToMany);
    List<Class<?>> entities = scan(basePackage);
    entities.sort(Comparator.comparing(Class::getName));
    for (Class<?> e : entities) tableNames.put(e, entityTableName(e));
    // Each build method records its table in ctx.built().
    for (Class<?> e : entities) {
      InheritanceStrategy strategy = inheritanceStrategy(e);
      if (strategy == InheritanceStrategy.JOINED && hasEntityParent(e)) {
        buildJoinedTable(e, ctx);
      } else if (strategy == InheritanceStrategy.TABLE_PER_CLASS && hasEntityParent(e)) {
        buildTablePerClass(e, ctx);
      } else if (strategy == InheritanceStrategy.SINGLE_TABLE && hasEntityParent(e)) {
        // Root owns the physical table; subclasses contribute their fields below.
      } else {
        buildEntityTable(e, ctx);
      }
    }
    // SINGLE_TABLE: fold descendant fields into the root table.
    for (Class<?> e : entities) {
      if (inheritanceStrategy(e) == InheritanceStrategy.SINGLE_TABLE && hasEntityParent(e)) {
        Class<?> root = inheritanceRoot(e);
        if (root != e) mergeSubclassColumns(root, e, ctx);
      }
    }
    addDiscriminatorColumns(entities, ctx);
    // Rebuild after SINGLE_TABLE merges.
    List<SchemaModel.Table> tables = new ArrayList<>(built.values());
    tables.removeIf(
        t -> entities.stream().noneMatch(
            e -> tableNames.get(e).equals(t.name()) && shouldHavePhysicalTable(e)));
    addOneToManyJoinColumns(tables, ctx);
    addSyntheticJoinTables(tables, entities, ctx);
    addElementCollectionTables(tables, entities, ctx);
    tables.sort(Comparator.comparing(SchemaModel.Table::name));
    return new SchemaModel(tables, dedupeSequences(sequences));
  }

  /** Carries all per-extraction mutable state. */
  private record ExtractionContext(
      Map<Class<?>, String> tableNames,
      Map<Class<?>, SchemaModel.Table> built,
      List<SchemaModel.Sequence> sequences,
      List<DeferredOneToMany> deferredOneToMany) {}

  private List<Class<?>> scan(String basePackage) throws Exception {
    return scanEntities(Thread.currentThread().getContextClassLoader(), basePackage);
  }

  /**
   * Finds @Entity classes (javax or jakarta) under a package.
   *
   * @throws IllegalStateException if a class cannot be loaded, naming the class and source
   */
  public static List<Class<?>> scanEntities(ClassLoader loader, String basePackage)
      throws Exception {
    return io.migrax.util.ClassScanner.scan(loader, basePackage,
        c -> hasAnnotation(c, "Entity"));
  }

  /** Classes under a package with an annotation of the given simple name, e.g. Converter. */
  public static List<Class<?>> scanAnnotated(ClassLoader loader, String basePackage,
                                             String annotation) throws Exception {
    return io.migrax.util.ClassScanner.scan(loader, basePackage,
        c -> hasAnnotation(c, annotation));
  }

  private static boolean hasAnnotation(AnnotatedElement element, String simpleName) {
    for (Annotation a : element.getAnnotations()) {
      if (a.annotationType().getSimpleName().equals(simpleName)) {
        return true;
      }
    }
    return false;
  }

  private boolean isEntity(Class<?> c){ return ann(c,"Entity")!=null; }
  private boolean isMappedSuperclass(Class<?> c){ return ann(c,"MappedSuperclass")!=null; }
  private AnnotationView ann(AnnotatedElement e,String simple){
    for(Annotation a:e.getAnnotations()) if(a.annotationType().getSimpleName().equals(simple)) return new AnnotationView(a);
    return null;
  }
  private static final class AnnotationView {
    final Annotation a; AnnotationView(Annotation a){this.a=a;}
    Object get(String n){try{return a.annotationType().getMethod(n).invoke(a);}catch(Exception e){return null;}}
    String str(String n,String d){Object x=get(n); return x instanceof String && !((String)x).isBlank()?(String)x:d;}
    int integer(String n,int d){Object x=get(n);return x instanceof Number?((Number)x).intValue():d;}
    long lng(String n,long d){Object x=get(n);return x instanceof Number?((Number)x).longValue():d;}
  }
  /** Physical table name: @Table(name), else the JPA entity name, through the naming strategy. */
  private String entityTableName(Class<?> e) {
    AnnotationView table = ann(e, "Table");
    String name = table == null ? null : table.str("name", null);
    return naming.physical(name == null ? entityName(e) : name);
  }

  /** JPA entity name: @Entity(name) or the simple class name. */
  private String entityName(Class<?> e) {
    AnnotationView entity = ann(e, "Entity");
    return entity == null ? e.getSimpleName() : entity.str("name", e.getSimpleName());
  }

  /**
   * Adds Hibernate's discriminator column to SINGLE_TABLE roots that have entity subclasses,
   * and to JOINED roots that declare @DiscriminatorColumn. Hibernate's default is
   * {@code DTYPE varchar(31) not null}.
   */
  private void addDiscriminatorColumns(List<Class<?>> entities, ExtractionContext ctx) {
    for (Class<?> root : entities) {
      if (hasEntityParent(root)) {
        continue;
      }
      InheritanceStrategy strategy = inheritanceStrategy(root);
      AnnotationView column = ann(root, "DiscriminatorColumn");
      boolean hasSubclasses = entities.stream()
          .anyMatch(other -> other != root && inheritanceRoot(other) == root);
      boolean needed = hasSubclasses && (strategy == InheritanceStrategy.SINGLE_TABLE
          || strategy == InheritanceStrategy.JOINED && column != null);
      SchemaModel.Table table = ctx.built().get(root);
      if (!needed || table == null) {
        continue;
      }
      String name = naming.physical(column == null ? "DTYPE" : column.str("name", "DTYPE"));
      if (table.column(name) != null) {
        continue;
      }
      String type = column == null ? "STRING" : String.valueOf(column.get("discriminatorType"));
      String logical = "INTEGER".equals(type) ? "integer" : "varchar";
      Integer length = "INTEGER".equals(type) ? null
          : "CHAR".equals(type) ? Integer.valueOf(1)
          : Integer.valueOf(column == null ? 31 : column.integer("length", 31));
      List<SchemaModel.Column> columns = new ArrayList<>(table.columns());
      columns.add(new SchemaModel.Column(
          name, logical, false, length, null, null, null, false, false, null, logical));
      ctx.built().put(root, new SchemaModel.Table(table.name(), sorted(columns),
          table.primaryKey(), table.indexes(), table.foreignKeys()));
    }
  }
  private enum InheritanceStrategy { SINGLE_TABLE, JOINED, TABLE_PER_CLASS }
  private InheritanceStrategy inheritanceStrategy(Class<?> e){
    Class<?> r=inheritanceRoot(e); AnnotationView a=ann(r,"Inheritance"); if(a==null)return InheritanceStrategy.SINGLE_TABLE;
    Object x=a.get("strategy"); return x==null?InheritanceStrategy.SINGLE_TABLE:InheritanceStrategy.valueOf(x.toString());
  }
  private Class<?> inheritanceRoot(Class<?> e){Class<?> c=e; while(c.getSuperclass()!=null&&c.getSuperclass()!=Object.class&& (isEntity(c.getSuperclass())||isMappedSuperclass(c.getSuperclass()))) c=c.getSuperclass(); return c;}
  private boolean hasEntityParent(Class<?> e){Class<?> p=e.getSuperclass(); return p!=null&&p!=Object.class&&(isEntity(p)||isMappedSuperclass(p));}
  private boolean shouldHavePhysicalTable(Class<?> e){ return inheritanceStrategy(e)!=InheritanceStrategy.SINGLE_TABLE || inheritanceRoot(e)==e; }

  private SchemaModel.Table buildEntityTable(Class<?> e, ExtractionContext ctx) {
    if (ctx.built().containsKey(e)) return ctx.built().get(e);
    String name = ctx.tableNames().get(e);
    List<SchemaModel.Column> cols = new ArrayList<>();
    List<String> pk = new ArrayList<>();
    List<SchemaModel.Index> indexes = new ArrayList<>();
    List<SchemaModel.ForeignKey> fks = new ArrayList<>();
    collectBasic(e, "", cols, pk, indexes, ctx);
    collectRelationships(e, fks, cols, indexes, ctx);
    addTableIndexes(e, indexes, ctx);
    SchemaModel.Table t = new SchemaModel.Table(
        name, sorted(cols),
        pk.isEmpty() ? null : new SchemaModel.PrimaryKey(pk),
        dedupeIndexes(indexes), dedupeFks(fks));
    ctx.built().put(e, t);
    return t;
  }

  private SchemaModel.Table buildJoinedTable(Class<?> e, ExtractionContext ctx) {
    List<SchemaModel.Column> cols = new ArrayList<>();
    List<String> pk = new ArrayList<>();
    List<SchemaModel.Index> idx = new ArrayList<>();
    List<SchemaModel.ForeignKey> fks = new ArrayList<>();
    collectBasic(e, "", cols, pk, idx, ctx);
    collectRelationships(e, fks, cols, idx, ctx);
    addTableIndexes(e, idx, ctx);
    Class<?> parent = e.getSuperclass();
    String idName = pk.isEmpty() ? null : pk.get(0);
    if (idName == null) {
      SchemaModel.Table pt = buildEntityTable(parent, ctx);
      idName = pt.primaryKey() == null ? null : pt.primaryKey().columns().get(0);
      if (idName != null) {
        cols.add(pt.column(idName));
        pk.add(idName);
      }
    }
    if (idName != null) {
      fks.add(new SchemaModel.ForeignKey(
          "fk_" + ctx.tableNames().get(e) + "_" + ctx.tableNames().get(parent),
          List.of(idName), ctx.tableNames().get(parent), List.of(idName)));
    }
    SchemaModel.Table t = new SchemaModel.Table(
        ctx.tableNames().get(e), sorted(cols),
        pk.isEmpty() ? null : new SchemaModel.PrimaryKey(pk),
        dedupeIndexes(idx), dedupeFks(fks));
    ctx.built().put(e, t);
    return t;
  }

  private SchemaModel.Table buildTablePerClass(Class<?> e, ExtractionContext ctx) {
    List<SchemaModel.Column> cols = new ArrayList<>();
    List<String> pk = new ArrayList<>();
    List<SchemaModel.Index> idx = new ArrayList<>();
    List<SchemaModel.ForeignKey> fks = new ArrayList<>();
    List<Class<?>> hierarchy = new ArrayList<>();
    for (Class<?> c = e; c != null && c != Object.class; c = c.getSuperclass()) {
      if (isEntity(c) || isMappedSuperclass(c)) hierarchy.add(c);
    }
    Collections.reverse(hierarchy);
    for (Class<?> c : hierarchy) collectBasic(c, "", cols, pk, idx, ctx);
    collectRelationships(e, fks, cols, idx, ctx);
    addTableIndexes(e, idx, ctx);
    SchemaModel.Table t = new SchemaModel.Table(
        ctx.tableNames().get(e), sorted(cols),
        pk.isEmpty() ? null : new SchemaModel.PrimaryKey(pk),
        dedupeIndexes(idx), dedupeFks(fks));
    ctx.built().put(e, t);
    return t;
  }

  private void mergeSubclassColumns(Class<?> root, Class<?> sub, ExtractionContext ctx) {
    SchemaModel.Table base = buildEntityTable(root, ctx);
    List<SchemaModel.Column> cols = new ArrayList<>(base.columns());
    List<SchemaModel.Index> idx = new ArrayList<>(base.indexes());
    List<SchemaModel.ForeignKey> fks = new ArrayList<>(base.foreignKeys());
    Set<String> baseColumns = new HashSet<>();
    base.columns().forEach(c -> baseColumns.add(c.name()));
    collectBasic(sub, "", cols, new ArrayList<>(), idx, ctx);
    collectRelationships(sub, fks, cols, idx, ctx);
    addTableIndexes(sub, idx, ctx);
    // Rows of other subclasses share the table, so Hibernate makes subclass columns nullable.
    cols.replaceAll(c -> baseColumns.contains(c.name()) || c.nullable() ? c
        : new SchemaModel.Column(c.name(), c.sqlType(), true, c.length(), c.precision(),
            c.scale(), c.defaultValue(), c.unique(), c.identity(), c.sequenceName(),
            c.logicalType()));
    ctx.built().put(root, new SchemaModel.Table(
        base.name(), sorted(cols), base.primaryKey(), dedupeIndexes(idx), dedupeFks(fks)));
  }

  private void collectBasic(
      Class<?> e,
      String prefix,
      List<SchemaModel.Column> cols,
      List<String> pk,
      List<SchemaModel.Index> indexes,
      ExtractionContext ctx) {
    collectBasic(e, e, prefix, cols, pk, indexes, new HashSet<>(), ctx);
  }

  /**
   * Collects basic and embedded columns of {@code e} and its mapped superclasses.
   *
   * @param owner the entity being mapped; it names implicit id sequences
   */
  private void collectBasic(
      Class<?> owner,
      Class<?> e,
      String prefix,
      List<SchemaModel.Column> cols,
      List<String> pk,
      List<SchemaModel.Index> indexes,
      Set<Class<?>> guard,
      ExtractionContext ctx) {
    if (e == null || e == Object.class || (!isEntity(e) && !isMappedSuperclass(e))) return;
    collectBasic(owner, e.getSuperclass(), prefix, cols, pk, indexes, guard, ctx);
    for (Field f : e.getDeclaredFields()) {
      int m = f.getModifiers();
      if (Modifier.isStatic(m) || Modifier.isTransient(m) || ann(f, "Transient") != null) continue;
      if (isRelationship(f)) continue;
      // Element collections live in their own table; see addElementCollectionTables.
      if (ann(f, "ElementCollection") != null) continue;
      if (ann(f, "Embedded") != null || ann(f, "EmbeddedId") != null) {
        if (guard.add(f.getType())) {
          collectEmbedded(owner, f, attributeOverrides(f), cols, pk, indexes, guard, ctx);
          guard.remove(f.getType());
        }
        continue;
      }
      AnnotationView ca = ann(f, "Column");
      String name = columnName(f, ca, prefix);
      String logical = logicalType(f);
      String sql = explicitColumnDefinition(ca);
      if (sql == null) sql = logical;
      boolean nullable = ca == null || ca.get("nullable") == null || (Boolean) ca.get("nullable");
      // Hibernate maps primitive fields as NOT NULL: they cannot hold null.
      if (f.getType().isPrimitive()) nullable = false;
      if (ann(f, "Id") != null || ann(f, "Version") != null) nullable = false;
      boolean unique = ca != null && Boolean.TRUE.equals(ca.get("unique"));
      boolean identity = isIdentity(f);
      String seq = sequenceName(owner, f, ctx.sequences());
      Integer len = ca == null ? null : sizedLength(logical, positiveInt(ca.get("length")));
      Integer prec = ca == null ? null : positiveInt(ca.get("precision"));
      Integer scale = ca == null ? null : positiveInt(ca.get("scale"));
      // Like Hibernate, apply Bean Validation constraints to DDL when a validator is present.
      if (beanValidationApplies()) {
        if (constraint(f, "NotNull") != null) nullable = false;
        Annotation size = constraint(f, "Size");
        if (size != null && len == null
            && new AnnotationView(size).get("max") instanceof Integer max
            && max < Integer.MAX_VALUE) {
          len = sizedLength(logical, max);
        }
        Annotation digits = constraint(f, "Digits");
        if (digits != null
            && new AnnotationView(digits).get("integer") instanceof Integer integer
            && new AnnotationView(digits).get("fraction") instanceof Integer fraction) {
          prec = integer + fraction;
          scale = fraction;
        }
      }
      String defaultValue = columnDefault(f);
      cols.removeIf(c -> c.name().equals(name));
      cols.add(new SchemaModel.Column(
          name, sql, nullable, len, prec, scale, defaultValue, unique, identity, seq, logical));
      if (ann(f, "Id") != null) pk.add(name);
    }
  }

  /**
   * Adds the columns of an @Embedded or @EmbeddedId field. Like Hibernate's default naming,
   * embedded columns are not prefixed; @AttributeOverride renames them.
   */
  private void collectEmbedded(
      Class<?> owner,
      Field f,
      Map<String, String> overrides,
      List<SchemaModel.Column> cols,
      List<String> pk,
      List<SchemaModel.Index> idx,
      Set<Class<?>> guard,
      ExtractionContext ctx) {
    for (Field ef : allFields(f.getType())) {
      if (Modifier.isStatic(ef.getModifiers()) || Modifier.isTransient(ef.getModifiers()) || ann(ef, "Transient") != null) continue;
      if (isRelationship(ef)) continue;
      AnnotationView ca = ann(ef, "Column");
      String override = overrides.get(ef.getName());
      String name = override != null ? naming.physical(override) : columnName(ef, ca, "");
      String logical = logicalType(ef);
      String sql = explicitColumnDefinition(ca);
      if (sql == null) sql = logical;
      boolean embeddedId = ann(f, "EmbeddedId") != null;
      boolean nullable = !embeddedId && !ef.getType().isPrimitive()
          && (ca == null || !Boolean.FALSE.equals(ca.get("nullable")));
      boolean unique = ca != null && Boolean.TRUE.equals(ca.get("unique"));
      cols.add(new SchemaModel.Column(
          name, sql, nullable,
          ca == null ? null : sizedLength(logical, positiveInt(ca.get("length"))),
          ca == null ? null : positiveInt(ca.get("precision")),
          ca == null ? null : positiveInt(ca.get("scale")),
          null, unique, isIdentity(ef), sequenceName(owner, ef, ctx.sequences()), logical));
      if (embeddedId || ann(ef, "Id") != null) pk.add(name);
    }
  }
  private void collectRelationships(Class<?> e,List<SchemaModel.ForeignKey> fks,List<SchemaModel.Column> cols,List<SchemaModel.Index> indexes,ExtractionContext ctx){
    for (Field f : allFields(e)) {
      if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) continue;
      AnnotationView rel = relationship(f);
      if (rel == null) continue;
      String simple = rel.a.annotationType().getSimpleName();
      if ("ManyToOne".equals(simple) || "OneToOne".equals(simple)) {
        addToOne(f, fks, cols, indexes, ctx);
      } else if ("OneToMany".equals(simple) && ann(f, "JoinColumn") != null) {
        Class<?> target = collectionTarget(f);
        if (target == null
            && rel.get("targetEntity") instanceof Class<?> explicit
            && explicit != void.class) {
          target = explicit;
        }
        if (target == null) {
          throw new IllegalArgumentException(
              "Could not resolve @OneToMany target type for field '" + f.getName() + "'.");
        }
        ctx.deferredOneToMany().add(new DeferredOneToMany(f, target));
      }
    }
  }

  private void addToOne(
      Field f, List<SchemaModel.ForeignKey> fks,
      List<SchemaModel.Column> cols, List<SchemaModel.Index> indexes, ExtractionContext ctx) {
    Class<?> target = targetEntity(f);
    if (target == null) return;
    requireSingleColumnId(target);
    AnnotationView jc = ann(f, "JoinColumn");
    String ref = jc == null
        ? targetIdColumn(target)
        : naming.physical(jc.str("referencedColumnName", targetIdColumn(target)));
    // JPA default: <attribute>_<referenced primary key column>, e.g. customer_id.
    String col = naming.physical(jc == null
        ? f.getName() + "_" + ref
        : jc.str("name", f.getName() + "_" + ref));
    AnnotationView relation = relationship(f);
    boolean optional = relation == null || !Boolean.FALSE.equals(relation.get("optional"));
    boolean nullable = optional && (jc == null || !Boolean.FALSE.equals(jc.get("nullable")));
    cols.add(new SchemaModel.Column(
        col, logicalTypeFor(targetIdField(target)), nullable,
        null, null, null, null,
        jc != null && Boolean.TRUE.equals(jc.get("unique")),
        false, null, logicalTypeFor(targetIdField(target))));
    fks.add(new SchemaModel.ForeignKey(
        fkName(f, col, target),
        List.of(col),
        ctx.tableNames().computeIfAbsent(target, this::entityTableName),
        List.of(ref)));
  }
  private record DeferredOneToMany(Field field, Class<?> target) {}

  private void addOneToManyJoinColumns(List<SchemaModel.Table> tables, ExtractionContext ctx) {
    for (DeferredOneToMany relation : ctx.deferredOneToMany()) {
      if (relation.target == null) continue;
      Field field = relation.field;
      Class<?> owner = field.getDeclaringClass();
      requireSingleColumnId(owner);
      String ownerTable = ctx.tableNames().computeIfAbsent(owner, this::entityTableName);
      String targetTable = ctx.tableNames().get(relation.target);
      if (targetTable == null) {
        throw new IllegalArgumentException(
            "Could not resolve @OneToMany target entity table: " + relation.target.getName());
      }
      int index = -1;
      for (int i = 0; i < tables.size(); i++) {
        if (tables.get(i).name().equals(targetTable)) {
          index = i;
          break;
        }
      }
      if (index < 0) {
        throw new IllegalArgumentException(
            "Could not find physical table for @OneToMany target entity: "
                + relation.target.getName());
      }
      SchemaModel.Table table = tables.get(index);
      AnnotationView jc = ann(field, "JoinColumn");
      String referenced = naming.physical(jc.str("referencedColumnName", targetIdColumn(owner)));
      String name = naming.physical(jc.str("name", entityName(owner) + "_" + referenced));
      Field ownerId = targetIdField(owner);
      String logicalType = logicalTypeFor(ownerId);
      List<SchemaModel.Column> columns = new ArrayList<>(table.columns());
      boolean nullable = jc.get("nullable") == null || Boolean.TRUE.equals(jc.get("nullable"));
      if (table.column(name) == null) {
        columns.add(new SchemaModel.Column(
            name, logicalType, nullable, null, null, null, null, false, false, null, logicalType));
      }
      List<SchemaModel.ForeignKey> foreignKeys = new ArrayList<>(table.foreignKeys());
      String foreignKeyName = "fk_" + targetTable + "_" + name + "_" + ownerTable;
      if (foreignKeys.stream().noneMatch(key -> key.name().equals(foreignKeyName))) {
        foreignKeys.add(
            new SchemaModel.ForeignKey(foreignKeyName, List.of(name), ownerTable, List.of(referenced)));
      }
      tables.set(index, new SchemaModel.Table(
          table.name(), sorted(columns), table.primaryKey(), table.indexes(), dedupeFks(foreignKeys)));
    }
  }

  private void addSyntheticJoinTables(
      List<SchemaModel.Table> tables, List<Class<?>> entities, ExtractionContext ctx) {
    Set<String> existing = new HashSet<>();
    for (var t : tables) existing.add(t.name());
    for (Class<?> owner : entities) {
      for (Field f : allFields(owner)) {
        AnnotationView rel = relationship(f);
        if (rel == null) continue;
        String rn = rel.a.annotationType().getSimpleName();
        if (!"ManyToMany".equals(rn) && !"OneToMany".equals(rn)) continue;
        if ("OneToMany".equals(rn) && ann(f, "JoinColumn") != null) continue;
        String mapped = String.valueOf(rel.get("mappedBy"));
        if (mapped != null && !mapped.isBlank() && !"null".equals(mapped)) continue;
        AnnotationView jt = ann(f, "JoinTable");
        Class<?> target = collectionTarget(f);
        if (target == null) target = (Class<?>) rel.get("targetEntity");
        if (target == null) continue;
        requireSingleColumnId(owner);
        requireSingleColumnId(target);
        String ownerTable = ctx.tableNames().computeIfAbsent(owner, this::entityTableName);
        String targetTable = ctx.tableNames().computeIfAbsent(target, this::entityTableName);
        // Spring: <owner table>_<attribute>; JPA: <owner table>_<target table>.
        String implicitName = naming.springJoinTables()
            ? ownerTable + "_" + f.getName()
            : ownerTable + "_" + targetTable;
        String tableName = naming.physical(jt == null ? implicitName : jt.str("name", implicitName));
        if (existing.contains(tableName)) continue;
        // JPA default join columns: the owner side is named after the inverse attribute that
        // maps it (or the owner entity), the target side after this attribute.
        String inverseAttribute = inverseAttributeName(target, f);
        List<JoinCol> joins = joinColumns(jt, "joinColumns", owner,
            inverseAttribute != null ? inverseAttribute : entityName(owner));
        List<JoinCol> inv = joinColumns(jt, "inverseJoinColumns", target, f.getName());
        List<SchemaModel.Column> cols = new ArrayList<>();
        List<SchemaModel.ForeignKey> fks = new ArrayList<>();
        List<String> pk = new ArrayList<>();
        for (JoinCol jc : joins) {
          String typ = logicalTypeFor(targetIdField(owner));
          cols.add(new SchemaModel.Column(
              jc.name, typ, false, null, null, null, null, false, false, null, typ));
          pk.add(jc.name);
          fks.add(new SchemaModel.ForeignKey(
              "fk_" + tableName + "_" + jc.name,
              List.of(jc.name),
              ctx.tableNames().get(owner),
              List.of(jc.ref == null ? targetIdColumn(owner) : jc.ref)));
        }
        for (JoinCol jc : inv) {
          String typ = logicalTypeFor(targetIdField(target));
          cols.add(new SchemaModel.Column(
              jc.name, typ, false, null, null, null, null, false, false, null, typ));
          pk.add(jc.name);
          fks.add(new SchemaModel.ForeignKey(
              "fk_" + tableName + "_" + jc.name,
              List.of(jc.name),
              ctx.tableNames().get(target),
              List.of(jc.ref == null ? targetIdColumn(target) : jc.ref)));
        }
        tables.add(new SchemaModel.Table(
            tableName, sorted(cols), new SchemaModel.PrimaryKey(pk), List.of(), dedupeFks(fks)));
        existing.add(tableName);
      }
    }
  }
  /**
   * Adds the collection table of every {@code @ElementCollection}, like Hibernate: named by
   * {@code @CollectionTable} or {@code <Entity>_<attribute>}, a join column back to the owner
   * ({@code <Entity>_<id>} by default) with a foreign key, then the element column (basic
   * types, named by {@code @Column} or the attribute) or the columns of an {@code @Embeddable}.
   * A Set gets a primary key over all columns when none is nullable, a List with
   * {@code @OrderColumn} one over the join and order columns, other lists (bags) none.
   */
  private void addElementCollectionTables(
      List<SchemaModel.Table> tables, List<Class<?>> entities, ExtractionContext ctx) {
    Set<String> existing = new HashSet<>();
    for (var t : tables) existing.add(t.name());
    for (Class<?> owner : entities) {
      for (Field f : ownFields(owner)) {
        AnnotationView ec = ann(f, "ElementCollection");
        if (ec == null || Modifier.isStatic(f.getModifiers())) continue;
        if (Map.class.isAssignableFrom(f.getType())) {
          io.migrax.util.Log.warn("{}.{}: Map element collections are not read by the "
              + "annotation scanner; use Hibernate 6+ (--extractor hibernate) or write that "
              + "table's migration by hand.", owner.getSimpleName(), f.getName());
          continue;
        }
        Object target = ec.get("targetClass");
        Class<?> element = target instanceof Class<?> c && c != void.class ? c
            : collectionTarget(f);
        if (element == null) continue;
        requireSingleColumnId(owner);
        AnnotationView ct = ann(f, "CollectionTable");
        String tableName = naming.physical(ct == null
            ? entityName(owner) + "_" + f.getName()
            : ct.str("name", entityName(owner) + "_" + f.getName()));
        if (!existing.add(tableName)) continue;

        List<SchemaModel.Column> cols = new ArrayList<>();
        List<String> keyColumns = new ArrayList<>();
        List<SchemaModel.ForeignKey> fks = new ArrayList<>();
        String ownerTable = ctx.tableNames().get(owner);
        for (JoinCol jc : joinColumns(ct, "joinColumns", owner, entityName(owner))) {
          String typ = logicalTypeFor(targetIdField(owner));
          cols.add(new SchemaModel.Column(
              jc.name, typ, false, null, null, null, null, false, false, null, typ));
          keyColumns.add(jc.name);
          fks.add(new SchemaModel.ForeignKey("fk_" + tableName + "_" + jc.name,
              List.of(jc.name), ownerTable,
              List.of(jc.ref == null ? targetIdColumn(owner) : jc.ref)));
        }

        List<SchemaModel.Column> elementColumns = new ArrayList<>();
        if (ann(element, "Embeddable") != null) {
          Map<String, String> overrides = attributeOverrides(f);
          for (Field ef : allFields(element)) {
            int m = ef.getModifiers();
            if (Modifier.isStatic(m) || Modifier.isTransient(m) || ann(ef, "Transient") != null
                || isRelationship(ef)) continue;
            AnnotationView ca = ann(ef, "Column");
            String override = overrides.get(ef.getName());
            String name = override != null ? naming.physical(override) : columnName(ef, ca, "");
            elementColumns.add(elementColumn(name, logicalType(ef), ef.getType().isPrimitive(),
                ca));
          }
        } else {
          AnnotationView ca = ann(f, "Column");
          String name = naming.physical(ca == null ? f.getName() : ca.str("name", f.getName()));
          elementColumns.add(elementColumn(name, elementLogicalType(f, element),
              element.isPrimitive(), ca));
        }
        cols.addAll(elementColumns);

        SchemaModel.PrimaryKey primaryKey = null;
        AnnotationView order = ann(f, "OrderColumn");
        if (order != null && List.class.isAssignableFrom(f.getType())) {
          String orderName = naming.physical(order.str("name", f.getName() + "_ORDER"));
          cols.add(new SchemaModel.Column(
              orderName, "integer", false, null, null, null, null, false, false, null, "integer"));
          List<String> pk = new ArrayList<>(keyColumns);
          pk.add(orderName);
          primaryKey = new SchemaModel.PrimaryKey(pk);
        } else if (Set.class.isAssignableFrom(f.getType())
            && elementColumns.stream().noneMatch(SchemaModel.Column::nullable)) {
          List<String> pk = new ArrayList<>(keyColumns);
          elementColumns.forEach(c -> pk.add(c.name()));
          primaryKey = new SchemaModel.PrimaryKey(pk);
        }
        tables.add(new SchemaModel.Table(
            tableName, sorted(cols), primaryKey, List.of(), dedupeFks(fks)));
      }
    }
  }

  /** Fields declared on the entity and its mapped superclasses, not on parent entities. */
  private List<Field> ownFields(Class<?> entity) {
    List<Field> fields = new ArrayList<>(Arrays.asList(entity.getDeclaredFields()));
    for (Class<?> c = entity.getSuperclass(); c != null && isMappedSuperclass(c);
         c = c.getSuperclass()) {
      fields.addAll(Arrays.asList(c.getDeclaredFields()));
    }
    return fields;
  }

  /** Logical type of a basic collection element: the field's annotations, the element type. */
  private String elementLogicalType(Field f, Class<?> element) {
    String hinted = hibernateTypeOverride(f);
    if (hinted != null) return hinted;
    if (ann(f, "Lob") != null) {
      return element == byte[].class || element == Byte[].class ? "blob" : "clob";
    }
    AnnotationView e = ann(f, "Enumerated");
    if (e != null) {
      return "STRING".equals(String.valueOf(e.get("value"))) ? "varchar" : "integer";
    }
    AnnotationView t = ann(f, "Temporal");
    if (t != null) return String.valueOf(t.get("value")).toLowerCase(Locale.ROOT);
    return logicalTypeFor(element);
  }

  private SchemaModel.Column elementColumn(String name, String logical, boolean primitive,
                                           AnnotationView ca) {
    String sql = explicitColumnDefinition(ca);
    boolean nullable = !primitive && (ca == null || !Boolean.FALSE.equals(ca.get("nullable")));
    return new SchemaModel.Column(name, sql == null ? logical : sql, nullable,
        ca == null ? null : sizedLength(logical, positiveInt(ca.get("length"))),
        ca == null ? null : positiveInt(ca.get("precision")),
        ca == null ? null : positiveInt(ca.get("scale")),
        null, ca != null && Boolean.TRUE.equals(ca.get("unique")), false, null, logical);
  }

  private record JoinCol(String name, String ref) {}

  private List<JoinCol> joinColumns(
      AnnotationView jt, String method, Class<?> referenced, String defaultPrefix) {
    List<JoinCol> out = new ArrayList<>();
    Object x = jt == null ? null : jt.get(method);
    String referencedId = targetIdColumn(referenced);
    if (x instanceof Object[] arr) {
      for (Object o : arr) {
        AnnotationView a = new AnnotationView((Annotation) o);
        String r = naming.physical(a.str("referencedColumnName", referencedId));
        String n = naming.physical(a.str("name", defaultPrefix + "_" + r));
        out.add(new JoinCol(n, r));
      }
    }
    if (out.isEmpty()) {
      out.add(new JoinCol(naming.physical(defaultPrefix + "_" + referencedId), referencedId));
    }
    return out;
  }

  /** Name of the attribute in {@code target} declared with mappedBy pointing at {@code field}. */
  private String inverseAttributeName(Class<?> target, Field field) {
    for (Field candidate : allFields(target)) {
      AnnotationView relation = relationship(candidate);
      if (relation != null && field.getName().equals(relation.get("mappedBy"))
          && field.getDeclaringClass().isAssignableFrom(collectionTargetOrType(candidate))) {
        return candidate.getName();
      }
    }
    return null;
  }

  private Class<?> collectionTargetOrType(Field field) {
    Class<?> target = collectionTarget(field);
    return target != null ? target : field.getType();
  }

  private void addTableIndexes(
      Class<?> e, List<SchemaModel.Index> indexes, ExtractionContext ctx) {
    AnnotationView t = ann(e, "Table");
    if (t == null) return;
    Object arr = t.get("indexes");
    if (arr instanceof Object[]) {
      for (Object x : (Object[]) arr) {
        AnnotationView a = new AnnotationView((Annotation) x);
        String n = a.str("name", "");
        String colList = a.str("columnList", "");
        List<String> cs = new ArrayList<>();
        for (String c : colList.split(",")) {
          // columnList may carry ASC/DESC; keep the column name only.
          String column = c.trim().split("\\s+")[0];
          if (!column.isBlank()) cs.add(naming.physical(column));
        }
        if (!cs.isEmpty()) {
          indexes.add(new SchemaModel.Index(
              n.isBlank()
                  ? "idx_" + ctx.tableNames().get(e) + "_" + String.join("_", cs)
                  : n,
              cs,
              a.get("unique") instanceof Boolean && Boolean.TRUE.equals(a.get("unique"))));
        }
      }
    }
    Object uq = t.get("uniqueConstraints");
    if (uq instanceof Object[]) {
      for (Object x : (Object[]) uq) {
        AnnotationView a = new AnnotationView((Annotation) x);
        Object names = a.get("columnNames");
        if (names instanceof String[]) {
          List<String> cs = Arrays.stream((String[]) names).map(naming::physical).toList();
          if (!cs.isEmpty()) {
            indexes.add(new SchemaModel.Index(
                a.str("name", "uk_" + ctx.tableNames().get(e) + "_" + String.join("_", cs)),
                cs, true));
          }
        }
      }
    }
  }

  private List<Field> allFields(Class<?> c) {
    List<Field> r = new ArrayList<>();
    List<Class<?>> hs = new ArrayList<>();
    for (; c != null && c != Object.class; c = c.getSuperclass()) {
      hs.add(c);
    }
    Collections.reverse(hs);
    for (Class<?> x : hs) {
      r.addAll(Arrays.asList(x.getDeclaredFields()));
    }
    return r;
  }

  private List<SchemaModel.Column> sorted(List<SchemaModel.Column> c) {
    c.sort(Comparator.comparing(SchemaModel.Column::name));
    return c;
  }

  private List<SchemaModel.Index> dedupeIndexes(List<SchemaModel.Index> x) {
    Map<String, SchemaModel.Index> m = new LinkedHashMap<>();
    for (var i : x) m.put(i.name(), i);
    return new ArrayList<>(m.values());
  }

  private List<SchemaModel.ForeignKey> dedupeFks(List<SchemaModel.ForeignKey> x) {
    Map<String, SchemaModel.ForeignKey> m = new LinkedHashMap<>();
    for (var i : x) m.put(i.name(), i);
    return new ArrayList<>(m.values());
  }

  private List<SchemaModel.Sequence> dedupeSequences(List<SchemaModel.Sequence> sequences) {
    Map<String, SchemaModel.Sequence> m = new LinkedHashMap<>();
    for (var s : sequences) m.put(s.name(), s);
    return new ArrayList<>(m.values());
  }

  private AnnotationView relationship(Field f) {
    for (String n : List.of("ManyToOne", "OneToOne", "OneToMany", "ManyToMany")) {
      AnnotationView a = ann(f, n);
      if (a != null) return a;
    }
    return null;
  }

  private boolean isRelationship(Field f) {
    return relationship(f) != null;
  }

  /** Hibernate's @ColumnDefault("...") SQL default, or null. */
  private String columnDefault(Field f) {
    AnnotationView columnDefault = ann(f, "ColumnDefault");
    return columnDefault == null ? null : columnDefault.str("value", null);
  }

  /** A jakarta/javax Bean Validation constraint annotation on the field, or null. */
  private static Annotation constraint(Field f, String simpleName) {
    for (Annotation annotation : f.getAnnotations()) {
      String type = annotation.annotationType().getName();
      if ((type.startsWith("jakarta.validation.constraints.")
          || type.startsWith("javax.validation.constraints."))
          && annotation.annotationType().getSimpleName().equals(simpleName)) {
        return annotation;
      }
    }
    return null;
  }

  /** Hibernate applies Bean Validation constraints to DDL only when a validator is present. */
  private static boolean beanValidationApplies() {
    try {
      Class.forName("org.hibernate.validator.HibernateValidator", false,
          Thread.currentThread().getContextClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /** Physical column name: @Column(name) or the field name, through the naming strategy. */
  private String columnName(Field f, AnnotationView ca, String prefix) {
    String name = ca == null ? f.getName() : ca.str("name", f.getName());
    return prefix + naming.physical(name);
  }

  /** Reads @AttributeOverride / @AttributeOverrides on an embedded field: attribute to column. */
  private Map<String, String> attributeOverrides(Field f) {
    Map<String, String> result = new HashMap<>();
    List<AnnotationView> overrides = new ArrayList<>();
    AnnotationView single = ann(f, "AttributeOverride");
    if (single != null) {
      overrides.add(single);
    }
    AnnotationView many = ann(f, "AttributeOverrides");
    if (many != null && many.get("value") instanceof Object[] values) {
      for (Object value : values) {
        overrides.add(new AnnotationView((Annotation) value));
      }
    }
    for (AnnotationView override : overrides) {
      String attribute = override.str("name", null);
      Object column = override.get("column");
      if (attribute != null && column instanceof Annotation annotation) {
        String name = new AnnotationView(annotation).str("name", null);
        if (name != null) {
          result.put(attribute, name);
        }
      }
    }
    return result;
  }

  private String explicitColumnDefinition(AnnotationView ca) {
    return ca == null ? null : blankToNull((String) ca.get("columnDefinition"));
  }

  private String blankToNull(String x) {
    return x == null || x.isBlank() ? null : x;
  }

  /** Length matters only for sized types; 255 is the JPA default and is left implicit. */
  private static Integer sizedLength(String logical, Integer length) {
    boolean sized = "varchar".equals(logical) || "nvarchar".equals(logical)
        || "varbinary".equals(logical);
    return sized && length != null && length != 255 ? length : null;
  }

  private Integer positiveInt(Object x) {
    return x instanceof Number && ((Number) x).intValue() > 0 ? ((Number) x).intValue() : null;
  }

  /**
   * Database-neutral type of a basic field. Each dialect maps these logical types to SQL:
   * varchar, nvarchar, text, clob, integer, bigint, boolean, decimal, double, float, uuid,
   * date, time, timestamp, timestamptz, varbinary, blob and json.
   */
  private String logicalType(Field f) {
    String hinted = hibernateTypeOverride(f);
    if (hinted != null) {
      return hinted;
    }
    if (ann(f, "Lob") != null) {
      return f.getType() == byte[].class || f.getType() == Byte[].class ? "blob" : "clob";
    }
    AnnotationView e = ann(f, "Enumerated");
    if (e != null) {
      return "STRING".equals(String.valueOf(e.get("value"))) ? "varchar" : "integer";
    }
    AnnotationView t = ann(f, "Temporal");
    if (t != null) {
      return String.valueOf(t.get("value")).toLowerCase(Locale.ROOT);
    }
    return logicalTypeFor(f);
  }

  private String logicalTypeFor(Field f) {
    return f == null ? "text" : logicalTypeFor(f.getType());
  }

  private String logicalTypeFor(Class<?> c) {
    if (c == String.class || c == Character.class || c == char.class) return "varchar";
    if (c == Integer.class || c == int.class || c == Short.class || c == short.class
        || c == Byte.class || c == byte.class) return "integer";
    if (c == Long.class || c == long.class) return "bigint";
    if (c == Boolean.class || c == boolean.class) return "boolean";
    if (c == java.math.BigDecimal.class || c == java.math.BigInteger.class) return "decimal";
    if (c == Double.class || c == double.class) return "double";
    if (c == Float.class || c == float.class) return "float";
    if (c == UUID.class) return "uuid";
    if (c == java.time.LocalDate.class || c == java.sql.Date.class) return "date";
    if (c == java.time.LocalDateTime.class || c == java.util.Date.class
        || c == java.util.Calendar.class || c == java.sql.Timestamp.class) return "timestamp";
    // Hibernate 6 stores these instants with a time zone where the database supports it.
    if (c == java.time.Instant.class || c == java.time.OffsetDateTime.class
        || c == java.time.ZonedDateTime.class) return "timestamptz";
    if (c == java.time.LocalTime.class || c == java.sql.Time.class) return "time";
    // Without @Lob, Hibernate maps byte[] to a sized binary column (255 by default).
    if (c == byte[].class || c == Byte[].class) return "varbinary";
    // Enums without @Enumerated are stored by ordinal.
    if (c.isEnum()) return "integer";
    return "text";
  }

  /** Logical type from Hibernate's @JdbcTypeCode (SqlTypes constants) or @Nationalized. */
  private String hibernateTypeOverride(Field f) {
    AnnotationView a = ann(f, "JdbcTypeCode");
    if (a != null && a.get("value") instanceof Number code) {
      String logical = switch (code.intValue()) {
        case 3000 -> "uuid";
        case 3001, 3018 -> "json";
        case 2004, 3004 -> "blob";
        case -2, -3, -4, 4003 -> "varbinary";
        case 2005, 3005, 2011, 3006 -> "clob";
        case -1, -16, 4001, 4002 -> "text";
        case -9, -15 -> "nvarchar";
        case 12, 1 -> "varchar";
        case 4, 5, -6 -> "integer";
        case -5 -> "bigint";
        case 16, -7 -> "boolean";
        case 2, 3 -> "decimal";
        case 8 -> "double";
        case 6, 7 -> "float";
        case 91 -> "date";
        case 92 -> "time";
        case 93 -> "timestamp";
        case 2014, 3003 -> "timestamptz";
        default -> null;
      };
      if (logical != null) {
        return logical;
      }
      if (explicitColumnDefinition(ann(f, "Column")) == null) {
        throw new IllegalArgumentException("Field '" + f.getDeclaringClass().getSimpleName()
            + "." + f.getName() + "' uses @JdbcTypeCode(" + code + "), which Migrax cannot map. "
            + "Add @Column(columnDefinition = \"<sql type>\") to it.");
      }
    }
    if (ann(f, "Nationalized") != null) return "nvarchar";
    return null;
  }

  private boolean isIdentity(Field f) {
    AnnotationView g = ann(f, "GeneratedValue");
    if (g == null) return false;
    Object s = g.get("strategy");
    return s != null && "IDENTITY".equals(s.toString());
  }

  /**
   * Returns the sequence backing a generated id, registering it, or null for other ids.
   *
   * <p>Follows Hibernate 6: SEQUENCE and AUTO (for numeric ids) use the matching
   * {@code @SequenceGenerator} (on the field or the entity hierarchy); without one, a named
   * generator is the sequence name, and otherwise the sequence is {@code <entity name>_SEQ}
   * ({@code hibernate_sequence} with Micronaut naming) with allocation size 50.
   */
  private String sequenceName(Class<?> owner, Field f, List<SchemaModel.Sequence> sequences) {
    AnnotationView g = ann(f, "GeneratedValue");
    if (g == null) return null;
    Object s = g.get("strategy");
    String strategy = s == null ? "AUTO" : s.toString();
    boolean numeric = isNumericId(f.getType());
    if (!"SEQUENCE".equals(strategy) && !("AUTO".equals(strategy) && numeric)) return null;
    String generator = g.str("generator", null);
    if (generator != null) {
      AnnotationView sg = findSequenceGenerator(owner, f, generator);
      if (sg != null) {
        String seq = naming.physical(sg.str("sequenceName", generator));
        sequences.add(new SchemaModel.Sequence(
            seq, sg.lng("initialValue", 1), sg.lng("allocationSize", 50)));
        return seq;
      }
      if ("AUTO".equals(strategy) && !generator.isBlank()) {
        // AUTO with a named custom generator (@GenericGenerator and friends): no sequence.
        return null;
      }
    }
    // Micronaut Data sets Hibernate's legacy id naming: one shared hibernate_sequence.
    String implicit = naming.micronaut() ? "hibernate_sequence"
        : entityName(rootEntity(owner)) + "_SEQ";
    String seq = naming.physical(generator != null ? generator : implicit);
    sequences.add(new SchemaModel.Sequence(seq, 1L, 50L));
    return seq;
  }

  private static boolean isNumericId(Class<?> type) {
    return type == Long.class || type == long.class || type == Integer.class || type == int.class
        || type == Short.class || type == short.class || type == java.math.BigInteger.class
        || type == java.math.BigDecimal.class;
  }

  /** Finds @SequenceGenerator(name) on the field, then on the entity and its superclasses. */
  private AnnotationView findSequenceGenerator(Class<?> owner, Field f, String name) {
    List<AnnotatedElement> places = new ArrayList<>();
    places.add(f);
    for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
      places.add(c);
    }
    for (AnnotatedElement place : places) {
      AnnotationView single = ann(place, "SequenceGenerator");
      if (single != null && name.equals(single.str("name", ""))) {
        return single;
      }
      AnnotationView many = ann(place, "SequenceGenerators");
      if (many != null && many.get("value") instanceof Object[] values) {
        for (Object value : values) {
          AnnotationView candidate = new AnnotationView((Annotation) value);
          if (name.equals(candidate.str("name", ""))) {
            return candidate;
          }
        }
      }
    }
    return null;
  }

  /** Topmost @Entity in the hierarchy; it owns the id generator. */
  private Class<?> rootEntity(Class<?> e) {
    Class<?> root = e;
    for (Class<?> c = e.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
      if (isEntity(c)) {
        root = c;
      }
    }
    return root;
  }

  private Class<?> targetEntity(Field f) {
    AnnotationView r = relationship(f);
    if (r == null) return null;
    Object x = r.get("targetEntity");
    if (x instanceof Class && x != void.class) return (Class<?>) x;
    return collectionTarget(f) != null ? collectionTarget(f) : f.getType();
  }

  private Class<?> collectionTarget(Field f) {
    java.lang.reflect.Type t = f.getGenericType();
    if (t instanceof ParameterizedType p) {
      for (Type a : p.getActualTypeArguments()) {
        if (a instanceof Class) return (Class<?>) a;
        if (a instanceof ParameterizedType pp && pp.getRawType() instanceof Class) return (Class<?>) pp.getRawType();
      }
    }
    return null;
  }

  private Field targetIdField(Class<?> target) {
    for (Field f : allFields(target)) {
      if (ann(f, "Id") != null) return f;
    }
    return null;
  }

  private void requireSingleColumnId(Class<?> target) {
    List<Field> ids = allFields(target).stream().filter(f -> ann(f, "Id") != null).toList();
    boolean embedded = allFields(target).stream().anyMatch(f -> ann(f, "EmbeddedId") != null);
    if (ids.size() != 1 || embedded) {
      throw new IllegalArgumentException(
          "Relationships to entity '" + target.getName() + "' require one simple @Id field; "
              + "composite and @EmbeddedId relationship keys are not supported yet.");
    }
  }

  private String targetIdColumn(Class<?> target) {
    Field f = targetIdField(target);
    return f == null ? "id" : columnName(f, ann(f, "Column"), "");
  }

  private String fkName(Field f, String col, Class<?> target) {
    return "fk_" + defaultName(f.getDeclaringClass().getSimpleName()) + "_" + col + "_" + defaultName(target.getSimpleName());
  }

  private static String defaultName(String s) {
    return s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
  }
}
