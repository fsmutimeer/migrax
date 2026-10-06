package io.migrax.dialect;

public final class SqlServerDialect extends AbstractDialect {
    @Override public String id() { return "sqlserver"; }

    @Override protected String sequenceDefault(String name){return "NEXT VALUE FOR "+q(name);}
    @Override protected String renderType(io.migrax.model.SchemaModel.Column c) { if(c.sqlType()!=null&&!c.sqlType().equalsIgnoreCase(c.logicalType())) return c.sqlType(); String t=c.logicalType(); if("varchar".equals(t))return "varchar("+(c.length()==null?255:c.length())+")"; if("bigint".equals(t))return "bigint"; if("integer".equals(t))return "int"; if("decimal".equals(t))return c.precision()!=null?"decimal("+c.precision()+","+(c.scale()==null?0:c.scale())+")":"decimal(38,10)"; if("double".equals(t))return "float(53)"; if("float".equals(t))return "real"; if("timestamp".equals(t))return "datetime2"; if("boolean".equals(t))return "bit"; if("uuid".equals(t))return "uniqueidentifier"; if("blob".equals(t))return "varbinary(max)"; return c.sqlType(); }
    @Override protected String identityType(io.migrax.model.SchemaModel.Column c){return renderType(c)+" IDENTITY(1,1)";}

    @Override public String quote(String identifier) { return "[" + identifier.replace("]", "]]" ) + "]"; }
    @Override protected String addColumn(String table, io.migrax.model.SchemaModel.Column column) {
        return "ALTER TABLE " + q(table) + " ADD " + column(column);
    }
    @Override protected String alterColumn(String table, io.migrax.model.SchemaModel.Column before, io.migrax.model.SchemaModel.Column after) {
        return "ALTER TABLE " + q(table) + " ALTER COLUMN " + q(after.name()) + " " + renderType(after) + (after.nullable() ? " NULL" : " NOT NULL");
    }
    @Override protected String dropPrimaryKey(String table, String constraintName) {
        if (constraintName == null || constraintName.isBlank()) {
            throw new IllegalStateException(
                "Cannot drop the primary key on '" + table
                    + "' because its constraint name is unavailable in the schema snapshot.");
        }
        return "ALTER TABLE " + q(table) + " DROP CONSTRAINT " + q(constraintName);
    }
    @Override protected String dropIndex(String table, String name) { return "DROP INDEX " + q(name) + " ON " + q(table); }
    @Override protected String renameColumn(String table, String from, String to) { return "EXEC sp_rename '" + table.replace("'", "''") + "." + from.replace("'", "''") + "', '" + to.replace("'", "''") + "', 'COLUMN'"; }
}
