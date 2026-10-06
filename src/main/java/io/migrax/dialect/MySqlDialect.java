package io.migrax.dialect;

public class MySqlDialect extends AbstractDialect {
    @Override public String id() { return "mysql"; }

    @Override protected String renderType(io.migrax.model.SchemaModel.Column c) { if(c.sqlType()!=null&&!c.sqlType().equalsIgnoreCase(c.logicalType())) return c.sqlType(); String t=c.logicalType(); if("varchar".equals(t))return "varchar("+(c.length()==null?255:c.length())+")"; if("bigint".equals(t))return "bigint"; if("integer".equals(t))return "int"; if("decimal".equals(t))return c.precision()!=null?"decimal("+c.precision()+","+(c.scale()==null?0:c.scale())+")":"decimal"; if("double".equals(t))return "double"; if("float".equals(t))return "float"; if("timestamp".equals(t))return "datetime"; if("boolean".equals(t))return "boolean"; if("uuid".equals(t))return "char(36)"; if("blob".equals(t))return "blob"; return c.sqlType(); }
    @Override protected String identityType(io.migrax.model.SchemaModel.Column c){return renderType(c)+" AUTO_INCREMENT";}

    @Override public String quote(String identifier) { return "`" + identifier.replace("`", "``") + "`"; }
    @Override protected String createSequence(io.migrax.model.SchemaModel.Sequence s) { throw new IllegalStateException("MySQL does not support JPA sequence DDL; use IDENTITY/AUTO generation for this database"); }
    @Override protected String alterColumn(String table, io.migrax.model.SchemaModel.Column before, io.migrax.model.SchemaModel.Column after) {
        return "ALTER TABLE " + q(table) + " MODIFY COLUMN " + column(after);
    }
    @Override protected String dropPrimaryKey(String table, String constraintName) { return "ALTER TABLE " + q(table) + " DROP PRIMARY KEY"; }
    @Override protected String dropIndex(String table, String name) { return "DROP INDEX " + q(name) + " ON " + q(table); }
    @Override protected String dropForeignKey(String table, String name) {
        return "ALTER TABLE " + q(table) + " DROP FOREIGN KEY " + q(name);
    }
}
