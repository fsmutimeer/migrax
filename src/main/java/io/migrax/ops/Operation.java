package io.migrax.ops;
public sealed interface Operation permits CreateTable, DropTable, AddColumn, DropColumn, AlterColumn, RenameColumn, AddPrimaryKey, DropPrimaryKey, AddIndex, DropIndex, AddForeignKey, DropForeignKey, RunSql, CreateSequence, DropSequence {
  String kind();
  default boolean destructive(){return this instanceof DropTable || this instanceof DropColumn || this instanceof DropPrimaryKey || this instanceof DropIndex || this instanceof DropForeignKey || this instanceof DropSequence;}
}
