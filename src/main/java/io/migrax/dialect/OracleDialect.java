package io.migrax.dialect;

public final class OracleDialect extends AbstractDialect {
    @Override public String id() { return "oracle"; }

    @Override protected String sequenceDefault(String name){return q(name)+".NEXTVAL";}
    @Override protected String renderType(io.migrax.model.SchemaModel.Column c) { if(c.sqlType()!=null&&!c.sqlType().equalsIgnoreCase(c.logicalType())) return c.sqlType(); String t=c.logicalType(); if("varchar".equals(t))return "varchar2("+(c.length()==null?255:c.length())+")"; if("bigint".equals(t)||"integer".equals(t))return "number(19)"; if("decimal".equals(t))return c.precision()!=null?"number("+c.precision()+","+(c.scale()==null?0:c.scale())+")":"number"; if("double".equals(t)||"float".equals(t))return "binary_double"; if("timestamp".equals(t))return "timestamp"; if("boolean".equals(t))return "number(1)"; if("uuid".equals(t))return "varchar2(36)"; if("blob".equals(t))return "blob"; return c.sqlType(); }

    @Override public String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
    @Override protected String addColumn(String table, io.migrax.model.SchemaModel.Column column) {
        return "ALTER TABLE " + q(table) + " ADD (" + column(column) + ")";
    }
    @Override protected String alterColumn(String table, io.migrax.model.SchemaModel.Column before, io.migrax.model.SchemaModel.Column after) {
        return "ALTER TABLE " + q(table) + " MODIFY (" + q(after.name()) + " " + renderType(after) + (after.nullable() ? " NULL" : " NOT NULL") + ")";
    }
    @Override protected String dropPrimaryKey(String table, String constraintName) { return "ALTER TABLE " + q(table) + " DROP PRIMARY KEY"; }
    @Override protected String dropIndex(String table, String name) { return "DROP INDEX " + q(name); }
}
