package io.migrax.ops;

/**
 * One schema change. Destructive operations can lose data and need explicit approval.
 *
 * @since 0.1.0
 */
public sealed interface Operation permits CreateTable, DropTable, AddColumn, DropColumn,
    AlterColumn, RenameColumn, RenameTable, AddPrimaryKey, DropPrimaryKey, AddIndex, DropIndex, AddForeignKey,
    DropForeignKey, AddUnique, DropUnique, RunSql, CreateSequence, DropSequence {

  String kind();

  default boolean destructive() {
    return this instanceof DropTable || this instanceof DropColumn
        || this instanceof DropPrimaryKey || this instanceof DropIndex
        || this instanceof DropForeignKey || this instanceof DropSequence;
  }
}
