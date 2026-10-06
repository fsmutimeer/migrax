package io.migrax.dialect;

public final class PostgresDialect extends AbstractDialect {
    @Override public String id() { return "postgresql"; }

    @Override protected String renderType(io.migrax.model.SchemaModel.Column c) { if(c.sqlType()!=null&&!c.sqlType().equalsIgnoreCase(c.logicalType())) return c.sqlType(); String t=c.logicalType(); if ("varchar".equals(t)) return c.length()!=null?"varchar("+c.length()+")":"varchar(255)"; if("bigint".equals(t))return "bigint"; if("integer".equals(t))return "integer"; if("decimal".equals(t))return c.precision()!=null?"numeric("+c.precision()+","+(c.scale()==null?0:c.scale())+")":"numeric"; if("double".equals(t))return "double precision"; if("float".equals(t))return "real"; if("timestamp".equals(t))return "timestamp"; if("boolean".equals(t))return "boolean"; if("uuid".equals(t))return "uuid"; if("blob".equals(t))return "bytea"; return c.sqlType(); }

    @Override public String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
    @Override protected String alterColumn(String table, io.migrax.model.SchemaModel.Column before, io.migrax.model.SchemaModel.Column after) {
        return "ALTER TABLE " + q(table) + " ALTER COLUMN " + q(after.name()) + " TYPE " + renderType(after) + ", ALTER COLUMN " + q(after.name()) + (after.nullable() ? " DROP NOT NULL" : " SET NOT NULL");
    }
}
