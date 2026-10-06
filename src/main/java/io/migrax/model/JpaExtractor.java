package io.migrax.model;

import java.io.*;
import java.lang.reflect.*;
import java.lang.annotation.Annotation;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/**
 * Framework-neutral JPA extractor. It deliberately uses reflection against annotation names,
 * so the core does not compile against either javax.persistence or jakarta.persistence.
 * The application being inspected supplies its own JPA/Hibernate annotations at runtime.
 */
public final class JpaExtractor {
  private final Map<Class<?>, String> tableNames = new HashMap<>();
  private final Map<Class<?>, SchemaModel.Table> built = new HashMap<>();
  private final List<SchemaModel.Sequence> sequences = new ArrayList<>();
  private final List<DeferredOneToMany> deferredOneToMany = new ArrayList<>();

  public SchemaModel extract(String basePackage) throws Exception {
    tableNames.clear();
    built.clear();
    sequences.clear();
    deferredOneToMany.clear();
    List<Class<?>> entities = scan(basePackage);
    entities.sort(Comparator.comparing(Class::getName));
    for (Class<?> e : entities) tableNames.put(e, entityTableName(e));
    List<SchemaModel.Table> tables = new ArrayList<>();
    for (Class<?> e : entities) {
      InheritanceStrategy strategy = inheritanceStrategy(e);
      if (strategy == InheritanceStrategy.JOINED && hasEntityParent(e)) {
        tables.add(buildJoinedTable(e));
      } else if (strategy == InheritanceStrategy.TABLE_PER_CLASS && hasEntityParent(e)) {
        tables.add(buildTablePerClass(e));
      } else if (strategy == InheritanceStrategy.SINGLE_TABLE && hasEntityParent(e)) {
        // Root owns the physical table; subclasses contribute their fields below.
      } else {
        tables.add(buildEntityTable(e));
      }
    }
    // SINGLE_TABLE: fold descendant fields into the root table.
    for (Class<?> e : entities) {
      if (inheritanceStrategy(e) == InheritanceStrategy.SINGLE_TABLE && hasEntityParent(e)) {
        Class<?> root = inheritanceRoot(e);
        if (root != e) mergeSubclassColumns(root, e);
      }
    }
    // Rebuild after SINGLE_TABLE merges.
    tables = new ArrayList<>(built.values());
    tables.removeIf(t -> !entities.stream().anyMatch(e -> tableNames.get(e).equals(t.name()) && shouldHavePhysicalTable(e)));
    addOneToManyJoinColumns(tables);
    addSyntheticJoinTables(tables, entities);
    tables.sort(Comparator.comparing(SchemaModel.Table::name));
    return new SchemaModel(tables, dedupeSequences());
  }

  private List<Class<?>> scan(String basePackage) throws Exception {
    String path=basePackage.replace('.','/'); ClassLoader cl=Thread.currentThread().getContextClassLoader();
    Set<Class<?>> found=new LinkedHashSet<>(); Enumeration<URL> urls=cl.getResources(path);
    while(urls.hasMoreElements()) { URL u=urls.nextElement();
      if ("file".equals(u.getProtocol())) scanDirectory(basePackage, Paths.get(u.toURI()), cl, found);
      else if ("jar".equals(u.getProtocol())) scanJar(u, path, cl, found);
    }
    return new ArrayList<>(found);
  }
  private void scanDirectory(String pkg, Path root, ClassLoader cl, Set<Class<?>> found)
      throws Exception {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path classFile : paths.filter(path -> path.toString().endsWith(".class")).toList()) {
        String relative = root.relativize(classFile).toString().replace(File.separatorChar, '.');
        String className = pkg + "." + relative.substring(0, relative.length() - 6);
        addIfEntity(className, classFile.toString(), cl, found);
      }
    }
  }
  private void scanJar(URL url, String path, ClassLoader cl, Set<Class<?>> found)
      throws Exception {
    java.net.JarURLConnection connection = (java.net.JarURLConnection) url.openConnection();
    connection.setUseCaches(false);
    try (var jar = connection.getJarFile()) {
      var entries = jar.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (!entry.isDirectory() && entry.getName().startsWith(path + "/")
            && entry.getName().endsWith(".class")) {
          String className = entry.getName().replace('/', '.');
          className = className.substring(0, className.length() - 6);
          addIfEntity(className, url + "!/" + entry.getName(), cl, found);
        }
      }
    }
  }

  private void addIfEntity(
      String className, String source, ClassLoader loader, Set<Class<?>> found) {
    try {
      Class<?> candidate = Class.forName(className, false, loader);
      if (isEntity(candidate)) {
        found.add(candidate);
      }
    } catch (ClassNotFoundException | LinkageError failure) {
      throw new IllegalStateException(
          "Could not load class '" + className + "' while scanning " + source + ".", failure);
    }
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
  private String entityTableName(Class<?> e){ AnnotationView a=ann(e,"Table"); return a==null?defaultName(e.getSimpleName()):a.str("name",defaultName(e.getSimpleName())); }
  private enum InheritanceStrategy { SINGLE_TABLE, JOINED, TABLE_PER_CLASS }
  private InheritanceStrategy inheritanceStrategy(Class<?> e){
    Class<?> r=inheritanceRoot(e); AnnotationView a=ann(r,"Inheritance"); if(a==null)return InheritanceStrategy.SINGLE_TABLE;
    Object x=a.get("strategy"); return x==null?InheritanceStrategy.SINGLE_TABLE:InheritanceStrategy.valueOf(x.toString());
  }
  private Class<?> inheritanceRoot(Class<?> e){Class<?> c=e; while(c.getSuperclass()!=null&&c.getSuperclass()!=Object.class&& (isEntity(c.getSuperclass())||isMappedSuperclass(c.getSuperclass()))) c=c.getSuperclass(); return c;}
  private boolean hasEntityParent(Class<?> e){Class<?> p=e.getSuperclass(); return p!=null&&p!=Object.class&&(isEntity(p)||isMappedSuperclass(p));}
  private boolean shouldHavePhysicalTable(Class<?> e){ return inheritanceStrategy(e)!=InheritanceStrategy.SINGLE_TABLE || inheritanceRoot(e)==e; }

  private SchemaModel.Table buildEntityTable(Class<?> e){
    if(built.containsKey(e)) return built.get(e);
    String name=tableNames.get(e); List<SchemaModel.Column> cols=new ArrayList<>(); List<String> pk=new ArrayList<>(); List<SchemaModel.Index> indexes=new ArrayList<>(); List<SchemaModel.ForeignKey> fks=new ArrayList<>();
    collectBasic(e,"",cols,pk,indexes); collectRelationships(e,fks,cols,indexes);
    addTableIndexes(e,indexes); SchemaModel.Table t=new SchemaModel.Table(name,sorted(cols),pk.isEmpty()?null:new SchemaModel.PrimaryKey(pk),dedupeIndexes(indexes),dedupeFks(fks)); built.put(e,t); return t;
  }
  private SchemaModel.Table buildJoinedTable(Class<?> e){
    List<SchemaModel.Column> cols=new ArrayList<>(); List<String> pk=new ArrayList<>(); List<SchemaModel.Index> idx=new ArrayList<>(); List<SchemaModel.ForeignKey> fks=new ArrayList<>();
    collectBasic(e,"",cols,pk,idx); collectRelationships(e,fks,cols,idx); addTableIndexes(e,idx);
    Class<?> parent=e.getSuperclass(); String idName=pk.isEmpty()?null:pk.get(0); if(idName==null){SchemaModel.Table pt=buildEntityTable(parent); idName=pt.primaryKey()==null?null:pt.primaryKey().columns().get(0); if(idName!=null) {cols.add(pt.column(idName));pk.add(idName);}}
    if(idName!=null) fks.add(new SchemaModel.ForeignKey("fk_"+tableNames.get(e)+"_"+tableNames.get(parent),List.of(idName),tableNames.get(parent),List.of(idName)));
    SchemaModel.Table t=new SchemaModel.Table(tableNames.get(e),sorted(cols),pk.isEmpty()?null:new SchemaModel.PrimaryKey(pk),dedupeIndexes(idx),dedupeFks(fks)); built.put(e,t); return t;
  }
  private SchemaModel.Table buildTablePerClass(Class<?> e){
    List<SchemaModel.Column> cols=new ArrayList<>();List<String> pk=new ArrayList<>();List<SchemaModel.Index> idx=new ArrayList<>();List<SchemaModel.ForeignKey> fks=new ArrayList<>();
    List<Class<?>> hierarchy=new ArrayList<>();for(Class<?> c=e;c!=null&&c!=Object.class;c=c.getSuperclass())if(isEntity(c)||isMappedSuperclass(c))hierarchy.add(c);Collections.reverse(hierarchy);
    for(Class<?> c:hierarchy)collectBasic(c,"",cols,pk,idx);collectRelationships(e,fks,cols,idx);addTableIndexes(e,idx);
    SchemaModel.Table t=new SchemaModel.Table(tableNames.get(e),sorted(cols),pk.isEmpty()?null:new SchemaModel.PrimaryKey(pk),dedupeIndexes(idx),dedupeFks(fks));built.put(e,t);return t;
  }
  private void mergeSubclassColumns(Class<?> root,Class<?> sub){
    SchemaModel.Table base=buildEntityTable(root); List<SchemaModel.Column> cols=new ArrayList<>(base.columns());List<SchemaModel.Index> idx=new ArrayList<>(base.indexes());List<SchemaModel.ForeignKey> fks=new ArrayList<>(base.foreignKeys());List<String> pk=base.primaryKey()==null?List.of():base.primaryKey().columns();
    collectBasic(sub,"",cols,new ArrayList<>(),idx); collectRelationships(sub,fks,cols,idx); addTableIndexes(sub,idx);
    built.put(root,new SchemaModel.Table(base.name(),sorted(cols),base.primaryKey(),dedupeIndexes(idx),dedupeFks(fks)));
  }

  private void collectBasic(Class<?> e,String prefix,List<SchemaModel.Column> cols,List<String> pk,List<SchemaModel.Index> indexes){collectBasic(e,prefix,cols,pk,indexes,new HashSet<>());}
  private void collectBasic(Class<?> e,String prefix,List<SchemaModel.Column> cols,List<String> pk,List<SchemaModel.Index> indexes,Set<Class<?>> guard){
    if(e==null||e==Object.class||(!isEntity(e)&&!isMappedSuperclass(e)))return;
    collectBasic(e.getSuperclass(),prefix,cols,pk,indexes,guard);
    for(Field f:e.getDeclaredFields()){
      int m=f.getModifiers();if(Modifier.isStatic(m)||Modifier.isTransient(m)||ann(f,"Transient")!=null)continue;
      if(isRelationship(f))continue;
      if(ann(f,"Embedded")!=null||ann(f,"EmbeddedId")!=null){if(guard.add(f.getType())){collectEmbedded(f,prefix+columnPrefix(f),cols,pk,indexes,guard);guard.remove(f.getType());}continue;}
      AnnotationView ca=ann(f,"Column");String name=columnName(f,ca,prefix);String logical=logicalType(f);String sql=explicitColumnDefinition(ca); if(sql==null) sql=hibernateTypeOverride(f); if(sql==null)sql=logical;
      boolean nullable=ca==null||ca.get("nullable")==null||(Boolean)ca.get("nullable"); if(ann(f,"Id")!=null||ann(f,"Version")!=null)nullable=false;
      boolean unique=ca!=null&&Boolean.TRUE.equals(ca.get("unique")); boolean identity=isIdentity(f);String seq=sequenceName(f);Integer len=ca==null?null:positiveInt(ca.get("length"));Integer prec=ca==null?null:positiveInt(ca.get("precision"));Integer scale=ca==null?null:positiveInt(ca.get("scale"));String def=ca==null?null:blankToNull((String)ca.get("columnDefinition"));
      cols.removeIf(c->c.name().equals(name));cols.add(new SchemaModel.Column(name,sql,nullable,len,prec,scale,def,unique,identity,seq,logical));if(ann(f,"Id")!=null)pk.add(name);
    }
  }
  private void collectEmbedded(Field f,String prefix,List<SchemaModel.Column> cols,List<String> pk,List<SchemaModel.Index> idx,Set<Class<?>> guard){
    for(Field ef:allFields(f.getType())){if(Modifier.isStatic(ef.getModifiers())||Modifier.isTransient(ef.getModifiers())||ann(ef,"Transient")!=null)continue;if(isRelationship(ef))continue;AnnotationView ca=ann(ef,"Column");String name=columnName(ef,ca,prefix);String logical=logicalType(ef);String sql=explicitColumnDefinition(ca);if(sql==null)sql=logical;boolean nullable=ca==null||!Boolean.FALSE.equals(ca.get("nullable"));boolean unique=ca!=null&&Boolean.TRUE.equals(ca.get("unique"));cols.add(new SchemaModel.Column(name,sql,nullable,ca==null?null:positiveInt(ca.get("length")),ca==null?null:positiveInt(ca.get("precision")),ca==null?null:positiveInt(ca.get("scale")),null,unique,isIdentity(ef),sequenceName(ef),logical));if(ann(ef,"Id")!=null)pk.add(name);}
  }
  private void collectRelationships(Class<?> e,List<SchemaModel.ForeignKey> fks,List<SchemaModel.Column> cols,List<SchemaModel.Index> indexes){
    for(Field f:allFields(e)){ if(Modifier.isStatic(f.getModifiers())||Modifier.isTransient(f.getModifiers()))continue;AnnotationView rel=relationship(f);if(rel==null)continue;String simple=rel.a.annotationType().getSimpleName();
      if("ManyToOne".equals(simple)||"OneToOne".equals(simple)) addToOne(f,fks,cols,indexes);
      else if("OneToMany".equals(simple)&&ann(f,"JoinColumn")!=null){
        Class<?> target=collectionTarget(f);
        if(target==null&&rel.get("targetEntity") instanceof Class<?> explicit&&explicit!=void.class)target=explicit;
        if(target==null)throw new IllegalArgumentException(
            "Could not resolve @OneToMany target type for field '"+f.getName()+"'.");
        deferredOneToMany.add(new DeferredOneToMany(f,target));
      }
    }
  }
  private void addToOne(Field f,List<SchemaModel.ForeignKey> fks,List<SchemaModel.Column> cols,List<SchemaModel.Index> indexes){
    Class<?> target=targetEntity(f);if(target==null)return;requireSingleColumnId(target);AnnotationView jc=ann(f,"JoinColumn");String col=jc==null?defaultName(f.getName())+"_id":jc.str("name",defaultName(f.getName())+"_id");String ref=jc==null?targetIdColumn(target):jc.str("referencedColumnName",targetIdColumn(target));boolean nullable=jc==null||!Boolean.FALSE.equals(jc.get("nullable"));cols.add(new SchemaModel.Column(col,logicalTypeFor(targetIdField(target)),nullable,null,null,null,null,jc!=null&&Boolean.TRUE.equals(jc.get("unique")),false,null,logicalTypeFor(targetIdField(target))));fks.add(new SchemaModel.ForeignKey(fkName(f,col,target),List.of(col),tableNames.computeIfAbsent(target,this::entityTableName),List.of(ref))); }
  private record DeferredOneToMany(Field field,Class<?> target) {}
  private void addOneToManyJoinColumns(List<SchemaModel.Table> tables){
    for(DeferredOneToMany relation:deferredOneToMany){
      if(relation.target==null)continue;
      Field field=relation.field;Class<?> owner=field.getDeclaringClass();requireSingleColumnId(owner);
      String ownerTable=tableNames.computeIfAbsent(owner,this::entityTableName);
      String targetTable=tableNames.get(relation.target);
      if(targetTable==null)throw new IllegalArgumentException("Could not resolve @OneToMany target entity table: "+relation.target.getName());
      int index=-1;for(int i=0;i<tables.size();i++)if(tables.get(i).name().equals(targetTable)){index=i;break;}
      if(index<0)throw new IllegalArgumentException("Could not find physical table for @OneToMany target entity: "+relation.target.getName());
      SchemaModel.Table table=tables.get(index);AnnotationView jc=ann(field,"JoinColumn");
      String referenced=jc.str("referencedColumnName",targetIdColumn(owner));
      String name=jc.str("name",defaultName(owner.getSimpleName())+"_"+referenced);
      Field ownerId=targetIdField(owner);String logicalType=logicalTypeFor(ownerId);
      List<SchemaModel.Column> columns=new ArrayList<>(table.columns());
      boolean nullable=jc.get("nullable")==null||Boolean.TRUE.equals(jc.get("nullable"));
      if(table.column(name)==null)columns.add(new SchemaModel.Column(name,logicalType,nullable,null,null,null,null,false,false,null,logicalType));
      List<SchemaModel.ForeignKey> foreignKeys=new ArrayList<>(table.foreignKeys());
      String foreignKeyName="fk_"+targetTable+"_"+name+"_"+ownerTable;
      if(foreignKeys.stream().noneMatch(key->key.name().equals(foreignKeyName))){
        foreignKeys.add(new SchemaModel.ForeignKey(foreignKeyName,List.of(name),ownerTable,List.of(referenced)));
      }
      tables.set(index,new SchemaModel.Table(table.name(),sorted(columns),table.primaryKey(),table.indexes(),dedupeFks(foreignKeys)));
    }
  }

  private void addSyntheticJoinTables(List<SchemaModel.Table> tables,List<Class<?>> entities){
    Set<String> existing=new HashSet<>();for(var t:tables)existing.add(t.name());
    for(Class<?> owner:entities) for(Field f:allFields(owner)){
      AnnotationView rel=relationship(f);if(rel==null)continue;String rn=rel.a.annotationType().getSimpleName();
      if(!"ManyToMany".equals(rn)&&!"OneToMany".equals(rn))continue;
      if("OneToMany".equals(rn)&&ann(f,"JoinColumn")!=null)continue;
      String mapped=String.valueOf(rel.get("mappedBy"));if(mapped!=null&&!mapped.isBlank()&&!"null".equals(mapped))continue;
      AnnotationView jt=ann(f,"JoinTable");
      Class<?> target=collectionTarget(f);if(target==null)target=(Class<?>)rel.get("targetEntity");if(target==null)continue;
      requireSingleColumnId(owner);requireSingleColumnId(target);
      String tableName=jt==null?defaultName(owner.getSimpleName()+"_"+target.getSimpleName()):jt.str("name",defaultName(owner.getSimpleName()+"_"+target.getSimpleName()));
      if(existing.contains(tableName))continue;
      List<JoinCol> joins=joinColumns(jt,"joinColumns",owner);List<JoinCol> inv=joinColumns(jt,"inverseJoinColumns",target);
      List<SchemaModel.Column> cols=new ArrayList<>();List<SchemaModel.ForeignKey> fks=new ArrayList<>();List<String> pk=new ArrayList<>();
      for(JoinCol jc:joins){Field id=targetIdField(owner);String typ=logicalTypeFor(id);cols.add(new SchemaModel.Column(jc.name,typ,false,null,null,null,null,false,false,null,typ));pk.add(jc.name);fks.add(new SchemaModel.ForeignKey("fk_"+tableName+"_"+jc.name,List.of(jc.name),tableNames.get(owner),List.of(jc.ref==null?targetIdColumn(owner):jc.ref)));}
      for(JoinCol jc:inv){Field id=targetIdField(target);String typ=logicalTypeFor(id);cols.add(new SchemaModel.Column(jc.name,typ,false,null,null,null,null,false,false,null,typ));pk.add(jc.name);fks.add(new SchemaModel.ForeignKey("fk_"+tableName+"_"+jc.name,List.of(jc.name),tableNames.get(target),List.of(jc.ref==null?targetIdColumn(target):jc.ref)));}
      tables.add(new SchemaModel.Table(tableName,sorted(cols),new SchemaModel.PrimaryKey(pk),List.of(),dedupeFks(fks)));existing.add(tableName);
    }
  }
  private record JoinCol(String name,String ref){}
  private List<JoinCol> joinColumns(AnnotationView jt,String method,Class<?> owner){
    List<JoinCol> out=new ArrayList<>();Object x=jt==null?null:jt.get(method);if(x instanceof Object[] arr){for(Object o:arr){AnnotationView a=new AnnotationView((Annotation)o);String n=a.str("name",defaultName(owner.getSimpleName())+"_id");String r=a.str("referencedColumnName",targetIdColumn(owner));out.add(new JoinCol(n,r));}}
    if(out.isEmpty())out.add(new JoinCol(defaultName(owner.getSimpleName())+"_id",targetIdColumn(owner)));return out;
  }

  private void addTableIndexes(Class<?> e,List<SchemaModel.Index> indexes){AnnotationView t=ann(e,"Table");if(t==null)return;Object arr=t.get("indexes");if(arr instanceof Object[]){for(Object x:(Object[])arr){AnnotationView a=new AnnotationView((Annotation)x);String n=a.str("name","");String colList=a.str("columnList","");List<String> cs=new ArrayList<>();for(String c:colList.split(",")){if(!c.isBlank())cs.add(c.trim());}if(!cs.isEmpty())indexes.add(new SchemaModel.Index(n.isBlank()?"idx_"+tableNames.get(e)+"_"+String.join("_",cs):n,cs,a.get("unique") instanceof Boolean&&Boolean.TRUE.equals(a.get("unique"))));}}Object uq=t.get("uniqueConstraints");if(uq instanceof Object[]){for(Object x:(Object[])uq){AnnotationView a=new AnnotationView((Annotation)x);Object names=a.get("columnNames");if(names instanceof String[]){List<String> cs=List.of((String[])names);if(!cs.isEmpty())indexes.add(new SchemaModel.Index(a.str("name", "uk_"+tableNames.get(e)+"_"+String.join("_",cs)),cs,true));}}}}

  private List<Field> allFields(Class<?> c){List<Field> r=new ArrayList<>();List<Class<?>> hs=new ArrayList<>();for(;c!=null&&c!=Object.class;c=c.getSuperclass())hs.add(c);Collections.reverse(hs);for(Class<?> x:hs)r.addAll(Arrays.asList(x.getDeclaredFields()));return r;}
  private List<SchemaModel.Column> sorted(List<SchemaModel.Column> c){c.sort(Comparator.comparing(SchemaModel.Column::name));return c;}
  private List<SchemaModel.Index> dedupeIndexes(List<SchemaModel.Index> x){Map<String,SchemaModel.Index>m=new LinkedHashMap<>();for(var i:x)m.put(i.name(),i);return new ArrayList<>(m.values());}
  private List<SchemaModel.ForeignKey> dedupeFks(List<SchemaModel.ForeignKey> x){Map<String,SchemaModel.ForeignKey>m=new LinkedHashMap<>();for(var i:x)m.put(i.name(),i);return new ArrayList<>(m.values());}
  private List<SchemaModel.Sequence> dedupeSequences(){Map<String,SchemaModel.Sequence>m=new LinkedHashMap<>();for(var s:sequences)m.put(s.name(),s);return new ArrayList<>(m.values());}
  private AnnotationView relationship(Field f){for(String n:List.of("ManyToOne","OneToOne","OneToMany","ManyToMany")){AnnotationView a=ann(f,n);if(a!=null)return a;}return null;}
  private boolean isRelationship(Field f){return relationship(f)!=null;}
  private String columnName(Field f,AnnotationView ca,String prefix){return prefix+(ca==null?defaultName(f.getName()):ca.str("name",defaultName(f.getName())));}
  private String columnPrefix(Field f){AnnotationView a=ann(f,"AttributeOverrides");return defaultName(f.getName())+"_";}
  private String explicitColumnDefinition(AnnotationView ca){return ca==null?null:blankToNull((String)ca.get("columnDefinition"));}
  private String blankToNull(String x){return x==null||x.isBlank()?null:x;}
  private Integer positiveInt(Object x){return x instanceof Number&&((Number)x).intValue()>0?((Number)x).intValue():null;}
  private String logicalType(Field f){if(ann(f,"Lob")!=null)return f.getType()==byte[].class?"blob":"text";AnnotationView e=ann(f,"Enumerated");if(e!=null)return "STRING".equals(String.valueOf(e.get("value")))?"varchar":"integer";AnnotationView t=ann(f,"Temporal");if(t!=null)return String.valueOf(t.get("value"));return logicalTypeFor(f);}
  private String logicalTypeFor(Field f){if(f==null)return "text";Class<?> c=f.getType();if(c==String.class||c==Character.class||c==char.class)return "varchar";if(c==Integer.class||c==int.class||c==Short.class||c==short.class||c==Byte.class||c==byte.class)return "integer";if(c==Long.class||c==long.class)return "bigint";if(c==Boolean.class||c==boolean.class)return "boolean";if(c==java.math.BigDecimal.class)return "decimal";if(c==Double.class||c==double.class)return "double";if(c==Float.class||c==float.class)return "float";if(c==UUID.class)return "uuid";if(c==java.time.LocalDate.class)return "date";if(c==java.time.LocalDateTime.class||c==java.time.OffsetDateTime.class||c==java.time.Instant.class)return "timestamp";if(c==java.time.LocalTime.class)return "time";if(c==byte[].class)return "blob";return "text";}
  private String hibernateTypeOverride(Field f){
    AnnotationView a=ann(f,"JdbcTypeCode");if(a!=null){Object v=a.get("value");if(v!=null){String n=String.valueOf(v);if("3000".equals(n))return "json";if("2016".equals(n))return "json";if("2004".equals(n))return "blob";if("1111".equals(n))return "other";}}
    if(ann(f,"Nationalized")!=null)return "nvarchar";return null;
  }
  private boolean isIdentity(Field f){AnnotationView g=ann(f,"GeneratedValue");if(g==null)return false;Object s=g.get("strategy");return s!=null&&"IDENTITY".equals(s.toString());}
  private String sequenceName(Field f){AnnotationView g=ann(f,"GeneratedValue");if(g==null)return null;Object s=g.get("strategy");if(s==null||!"SEQUENCE".equals(s.toString()))return null;String name=(String)g.get("generator");if(name!=null&&!name.isBlank()){AnnotationView sg=ann(f,"SequenceGenerator");if(sg!=null&&name.equals(sg.str("name",""))){String seq=sg.str("sequenceName","");if(!seq.isBlank()){sequences.add(new SchemaModel.Sequence(seq,sg.lng("initialValue",1),sg.lng("allocationSize",50)));return seq;}}}return null;}
  private Class<?> targetEntity(Field f){AnnotationView r=relationship(f);if(r==null)return null;Object x=r.get("targetEntity");if(x instanceof Class && x!=void.class)return (Class<?>)x;return collectionTarget(f)!=null?collectionTarget(f):f.getType();}
  private Class<?> collectionTarget(Field f){java.lang.reflect.Type t=f.getGenericType();if(t instanceof ParameterizedType p){for(Type a:p.getActualTypeArguments()){if(a instanceof Class)return (Class<?>)a;if(a instanceof ParameterizedType pp&&pp.getRawType() instanceof Class)return (Class<?>)pp.getRawType();}}return null;}
  private Field targetIdField(Class<?> target){for(Field f:allFields(target))if(ann(f,"Id")!=null)return f;return null;}
  private void requireSingleColumnId(Class<?> target){
    List<Field> ids=allFields(target).stream().filter(f->ann(f,"Id")!=null).toList();
    boolean embedded=allFields(target).stream().anyMatch(f->ann(f,"EmbeddedId")!=null);
    if(ids.size()!=1||embedded)throw new IllegalArgumentException(
        "Relationships to entity '"+target.getName()+"' require one simple @Id field; "
            +"composite and @EmbeddedId relationship keys are not supported yet.");
  }
  private String targetIdColumn(Class<?> target){Field f=targetIdField(target);return f==null?"id":columnName(f,ann(f,"Column"),"");}
  private String fkName(Field f,String col,Class<?> target){return "fk_"+defaultName(f.getDeclaringClass().getSimpleName())+"_"+col+"_"+defaultName(target.getSimpleName());}
  private static String defaultName(String s){return s.replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);}
}
