package org.scalarelational.model

import java.io.File
import javax.sql.DataSource

import org.powerscala.event.processor.UnitProcessor
import org.powerscala.property.Property
import org.scalarelational.{TableAlias, SelectExpression}
import org.scalarelational.column.property._
import org.scalarelational.datatype.DataType
import org.scalarelational.fun.SimpleFunction
import org.scalarelational.instruction._
import org.scalarelational.model.table.property.Index
import org.scalarelational.op._

import scala.collection.mutable.ListBuffer

/**
 * @author Matt Hicks <matt@outr.com>
 */
abstract class SQLDatastore protected() extends Datastore {
  protected def this(dataSource: DataSource) = {
    this()
    dataSourceProperty := dataSource
  }

  val dataSourceProperty = Property[DataSource]()

  def dataSource = dataSourceProperty()

  val querying = new UnitProcessor[Query[_, _]]("querying")
  val inserting = new UnitProcessor[Insert]("inserting")
  val merging = new UnitProcessor[Merge]("merging")
  val updating = new UnitProcessor[Update]("updating")
  val deleting = new UnitProcessor[Delete]("deleting")

  def createTableSQL(table: Table) = {
    val b = new StringBuilder

    b.append("CREATE TABLE IF NOT EXISTS ")
    b.append(table.tableName)
    b.append('(')
    b.append(table.columns.map(c => column2SQL(c)).mkString(", "))

    if (table.primaryKeys.nonEmpty) {
      b.append(s", PRIMARY KEY(${table.primaryKeys.map(c => c.name).mkString(", ")})")
    }

    b.append(");")

    b.toString()
  }

  def createTableExtras(table: Table, b: StringBuilder) = {
    createTableReferences(table, b)
    createTableIndexes(table, b)
  }

  def createTableReferences(table: Table, b: StringBuilder) = {
    table.foreignKeys.foreach {
      case c => {
        val foreignKey = ForeignKey(c).foreignColumn
        b.append(s"ALTER TABLE ${table.tableName}\r\n")
        b.append(s"\tADD FOREIGN KEY(${c.name})\r\n")
        b.append(s"\tREFERENCES ${foreignKey.table.tableName} (${foreignKey.name});\r\n\r\n")
      }
    }
  }

  def createTableIndexes(table: Table, b: StringBuilder) = {
    table.columns.foreach {
      case c => c.get[Indexed](Indexed.name) match {
        case Some(index) => {
          b.append(s"CREATE INDEX IF NOT EXISTS ${index.indexName} ON ${table.tableName}(${c.name});\r\n\r\n")
        }
        case None => // No index on this column
      }
    }

    table.properties.foreach {
      case index: Index => b.append(s"CREATE ${if (index.unique) "UNIQUE " else ""}INDEX IF NOT EXISTS ${index.indexName} ON ${table.tableName}(${index.columns.map(c => c.name).mkString(", ")});\r\n\r\n")
      case _ => // Ignore other table properties
    }
  }

  override def createExtras(b: StringBuilder) = {}

  def column2SQL(column: Column[_]) = {
    val b = new StringBuilder
    b.append(column.name)
    b.append(' ')
    b.append(column.sqlType)
    if (column.has(NotNull)) {
      b.append(" NOT NULL")
    }
    if (column.has(AutoIncrement)) {
      b.append(" AUTO_INCREMENT")
    }
    if (column.has(Unique)) {
      b.append(" UNIQUE")
    }
    b.toString()
  }

  private def expression2SQL(expression: SelectExpression[_]) = expression match {
    case c: ColumnLike[_] => c.longName
    case f: SimpleFunction[_] => f.alias match {
      case Some(alias) => s"${f.functionType.name.toUpperCase}(${f.column.longName}) AS $alias"
      case None => s"${f.functionType.name.toUpperCase}(${f.column.longName})"
    }
  }

  def describe[E, R](query: Query[E, R]) = {
    val columns = query.asVector.map(expression2SQL).mkString(", ")

    var args = List.empty[Any]

    // Generate SQL
    val (joins, joinArgs) = joins2SQL(query.joins)
    args = args ::: joinArgs
    val (where, whereArgs) = where2SQL(query.whereCondition)
    args = args ::: whereArgs
    val groupBy = if (query.grouping.nonEmpty) {
      s" GROUP BY ${query.grouping.map(expression2SQL).mkString(", ")}"
    } else {
      ""
    }
    val orderBy = if (query.ordering.nonEmpty) {
      s" ORDER BY ${query.ordering.map(ob => s"${expression2SQL(ob.expression)} ${ob.direction.sql}").mkString(", ")}"
    } else {
      ""
    }
    val limit = if (query.resultLimit != -1) {
      s" LIMIT ${query.resultLimit}"
    } else {
      ""
    }
    val offset = if (query.resultOffset != -1) {
      s" OFFSET ${query.resultOffset}"
    } else {
      ""
    }
    s"SELECT $columns FROM ${query.table.tableName}$joins$where$groupBy$orderBy$limit$offset" -> args
  }

  def exportTable(table: Table, file: File, drop: Boolean = true) = {
    val command = new StringBuilder("SCRIPT ")
    if (drop) {
      command.append("DROP ")
    }
    command.append("TO '")
    command.append(file.getCanonicalPath)
    command.append("' TABLE ")
    command.append(table.tableName)

    //    val command = s"SCRIPT TO '${file.getCanonicalPath}' TABLE ${table.tableName}"
    session.execute(command.toString())
  }

  def importScript(file: File) = {
    val command = s"RUNSCRIPT FROM '${file.getCanonicalPath}'"
    session.execute(command)
  }

  protected[scalarelational] def exec[E, R](query: Query[E, R]) = {
    val (sql, args) = describe(query)

    querying.fire(query)
    session.executeQuery(sql, args)
  }

  def exec(insert: InsertSingle) = {
    if (insert.values.isEmpty) throw new IndexOutOfBoundsException(s"Attempting an insert query with no values: $insert")
    val table = insert.values.head.column.table
    val columnNames = insert.values.map(cv => cv.column.name).mkString(", ")
    val columnValues = insert.values.map(cv => cv.toSQL)
    val placeholder = columnValues.map(v => "?").mkString(", ")
    val insertString = s"INSERT INTO ${table.tableName} ($columnNames) VALUES($placeholder)"
    inserting.fire(insert)
    val resultSet = session.executeInsert(insertString, columnValues)
    try {
      if (resultSet.next()) {
        resultSet.getInt(1)
      } else {
        -1
      }
    } finally {
      resultSet.close()
    }
  }

  def exec(insert: InsertMultiple) = {
    if (insert.rows.isEmpty) throw new IndexOutOfBoundsException(s"Attempting a multi-insert with no values: $insert")
    if (!insert.rows.map(_.length).sliding(2).forall { case Seq(first, second) => first == second }) throw new IndexOutOfBoundsException(s"All rows must have the exact same length.")
    val table = insert.rows.head.head.column.table
    val columnNames = insert.rows.head.map(cv => cv.column.name).mkString(", ")
    val columnValues = insert.rows.map(r => r.map(cv => cv.toSQL))
    val placeholder = insert.rows.head.map(v => "?").mkString(", ")
    val insertString = s"INSERT INTO ${table.tableName} ($columnNames) VALUES($placeholder)"
    inserting.fire(insert)
    val resultSet = session.executeInsertMultiple(insertString, columnValues)
    try {
      val indexes = ListBuffer.empty[Int]
      while (resultSet.next()) {
        indexes += resultSet.getInt(1)
      }
      indexes.toList
    } finally {
      resultSet.close()
    }
  }

  def exec(merge: Merge) = {
    val table = merge.key.table
    val columnNames = merge.values.map(cv => cv.column.name).mkString(", ")
    val columnValues = merge.values.map(cv => cv.toSQL)
    val placeholder = columnValues.map(v => "?").mkString(", ")
    val mergeString = s"MERGE INTO ${table.tableName} ($columnNames) KEY(${merge.key.name}) VALUES($placeholder)"
    merging.fire(merge)
    session.executeUpdate(mergeString, columnValues)
  }

  def exec(update: Update) = {
    var args = List.empty[Any]
    val sets = update.values.map(cv => s"${cv.column.longName}=?").mkString(", ")
    val setArgs = update.values.map(cv => cv.toSQL)
    args = args ::: setArgs

    val (where, whereArgs) = where2SQL(update.whereCondition)
    args = args ::: whereArgs
    val sql = s"UPDATE ${update.table.tableName} SET $sets$where"
    updating.fire(update)
    session.executeUpdate(sql, args)
  }

  def exec(delete: Delete) = {
    var args = List.empty[Any]

    val (where, whereArgs) = where2SQL(delete.whereCondition)
    args = args ::: whereArgs
    val sql = s"DELETE FROM ${delete.table.tableName}$where"
    deleting.fire(delete)
    session.executeUpdate(sql, args)
  }

  def condition2String(condition: Condition, args: ListBuffer[Any]): String = condition match {
    case c: ColumnCondition[_] => {
      s"${c.column.longName} ${c.operator.symbol} ${c.other.longName}"
    }
    case c: NullCondition[_] => {
      s"${c.column.longName} ${c.operator.symbol} NULL"
    }
    case c: DirectCondition[_] => {
      args += c.column.converter.asInstanceOf[DataType[Any]].toSQLType(c.column.asInstanceOf[ColumnLike[Any]], c.value)
      s"${c.column.longName} ${c.operator.symbol} ?"
    }
    case c: LikeCondition[_] => {
      args += c.pattern
      s"${c.column.longName} ${if (c.not) "NOT " else ""}LIKE ?"
    }
    case c: RegexCondition[_] => {
      args += c.regex.toString()
      s"${c.column.longName} ${if (c.not) "NOT " else ""}REGEXP ?"
    }
    case c: RangeCondition[_] => {
      c.values.foreach {
        case v => args += c.column.converter.asInstanceOf[DataType[Any]].toSQLType(c.column.asInstanceOf[ColumnLike[Any]], v)
      }
      val entries = c.operator match {
        case Operator.Between => c.values.map(v => "?").mkString(" AND ")
        case _ => c.values.map(v => "?").mkString("(", ", ", ")")
      }
      s"${c.column.longName} ${c.operator.symbol}$entries"
    }
    case c: Conditions => {
      val sql = c.list.map(condition => condition2String(condition, args)).mkString(s" ${c.connectType.name.toUpperCase} ")
      s"($sql)"
    }
  }

  private def joins2SQL(joins: List[Join]): (String, List[Any]) = {
    val args = ListBuffer.empty[Any]

    val b = new StringBuilder
    joins.foreach {
      case join => {
        val pre = join.joinType match {
          case JoinType.Inner => " INNER JOIN "
          case JoinType.Join => " JOIN "
          case JoinType.Left => " LEFT JOIN "
          case JoinType.LeftOuter => " LEFT OUTER JOIN "
          case JoinType.Outer => " OUTER JOIN "
        }
        b.append(pre)
        join.joinable match {
          case t: Table => b.append(t.tableName)
          case t: TableAlias => b.append(s"${t.table.tableName} AS ${t.tableAlias}")
          case q: Query[_, _] => {
            val (sql, queryArgs) = describe(q)
            b.append("(")
            b.append(sql)
            b.append(")")
            q.alias match {
              case Some(alias) => b.append(s" AS $alias")
              case None => // No alias assigned to the Query
            }
            args ++= queryArgs
          }
          case j => throw new RuntimeException(s"Unsupported Joinable: $j")
        }
        b.append(" ON ")
        b.append(condition2String(join.condition, args))
      }
    }

    (b.toString(), args.toList)
  }

  private def where2SQL(condition: Condition): (String, List[Any]) = if (condition != null) {
    val args = ListBuffer.empty[Any]
    val sql = condition2String(condition, args)
    if (sql != null && sql.nonEmpty) {
      s" WHERE $sql" -> args.toList
    } else {
      "" -> Nil
    }
  } else {
    "" -> Nil
  }

  override def dispose() = {
  }
}
