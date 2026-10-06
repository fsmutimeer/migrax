package io.migrax.dialect;

public final class H2Dialect extends AbstractDialect {
    @Override public String id() { return "h2"; }

    @Override protected String sequenceDefault(String name){return "NEXT VALUE FOR "+q(name);}
    @Override protected String renderType(io.migrax.model.SchemaModel.Column c) { if(c.sqlType()!=null&&!c.sqlType().equalsIgnoreCase(c.logicalType())) return c.sqlType(); String t=c.logicalType(); if("varchar".equals(t))return "varchar("+(c.length()==null?255:c.length())+")"; if("bigint".equals(t))return "bigint"; if("integer".equals(t))return "integer"; if("decimal".equals(t))return c.precision()!=null?"numeric("+c.precision()+","+(c.scale()==null?0:c.scale())+")":"numeric"; if("double".equals(t))return "double"; if("float".equals(t))return "real"; if("timestamp".equals(t))return "timestamp"; if("boolean".equals(t))return "boolean"; if("uuid".equals(t))return "uuid"; if("blob".equals(t))return "blob"; return c.sqlType(); }

    @Override public String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
    @Override protected String dropIndex(String table, String name) { return "DROP INDEX " + q(name); }
}
