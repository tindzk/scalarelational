package org.scalarelational.h2

import javax.sql.DataSource

import org.h2.jdbcx.JdbcConnectionPool
import org.powerscala.event.processor.UnitProcessor
import org.powerscala.log.Logging
import org.powerscala.property.Property
import org.scalarelational.h2.trigger.{TriggerEvent, TriggerType}
import org.scalarelational.model._

/**
 * @author Matt Hicks <matt@outr.com>
 */
abstract class H2Datastore private() extends SQLDatastore with Logging {
  protected def this(mode: H2ConnectionMode = H2Memory(org.powerscala.Unique()),
                     dbUser: String = "sa",
                     dbPassword: String = "sa") = {
    this()
    username := dbUser
    password := dbPassword
    modeProperty := mode
  }

  protected def this(dataSource: DataSource) = {
    this()
    dataSourceProperty := dataSource
  }

  Class.forName("org.h2.Driver")

  val modeProperty = Property[H2ConnectionMode]()
  val username = Property[String](default = Some("sa"))
  val password = Property[String](default = Some("sa"))
  val trigger = new UnitProcessor[TriggerEvent]("trigger")

  private var functions = Set.empty[H2Function]

  init()

  protected def init() = {
    modeProperty.change.on {
      case evt => updateDataSource()      // Update the data source if the mode changes
    }
  }

  def updateDataSource() = {
    dispose()     // Make sure to shut down the previous DataSource if possible
    dataSourceProperty := JdbcConnectionPool.create(modeProperty().url, username(), password())
  }

  def function[F](obj: AnyRef, methodName: String, functionName: Option[String] = None) = synchronized {
    val f = H2Function(this, obj, methodName, functionName)
    functions += f
    f
  }

  override def createTableExtras(table: Table, b: StringBuilder) = {
    super.createTableExtras(table, b)
    createTableTriggers(table, b)
  }

  private def createTableTriggers(table: Table, b: StringBuilder) = if (table.has(Triggers.name)) {
    val triggers = table.get[Triggers](Triggers.name).get
    if (triggers.has(TriggerType.Insert)) {
      b.append(s"""CREATE TRIGGER IF NOT EXISTS ${table.tableName}_INSERT_TRIGGER AFTER INSERT ON ${table.tableName} FOR EACH ROW CALL "org.scalarelational.h2.trigger.TriggerInstance";\r\n\r\n""")
    }
    if (triggers.has(TriggerType.Update)) {
      b.append(s"""CREATE TRIGGER IF NOT EXISTS ${table.tableName}_UPDATE_TRIGGER AFTER UPDATE ON ${table.tableName} FOR EACH ROW CALL "org.scalarelational.h2.trigger.TriggerInstance";\r\n\r\n""")
    }
    if (triggers.has(TriggerType.Delete)) {
      b.append(s"""CREATE TRIGGER IF NOT EXISTS ${table.tableName}_DELETE_TRIGGER AFTER DELETE ON ${table.tableName} FOR EACH ROW CALL "org.scalarelational.h2.trigger.TriggerInstance";\r\n\r\n""")
    }
    if (triggers.has(TriggerType.Select)) {
      b.append(s"""CREATE TRIGGER IF NOT EXISTS ${table.tableName}_SELECT_TRIGGER BEFORE SELECT ON ${table.tableName} CALL "org.scalarelational.h2.trigger.TriggerInstance";\r\n\r\n""")
    }
  }

  override def createExtras(b: StringBuilder) = {
    super.createExtras(b)

    createFunctions(b)
  }

  private def createFunctions(b: StringBuilder) = {
    functions.foreach {
      case f => b.append(s"""CREATE ALIAS IF NOT EXISTS ${f.name} FOR "${f.obj.getClass.getName.replaceAll("[$]", "")}.${f.methodName}";\r\n\r\n""")
    }
  }

  override def dispose() = {
    super.dispose()

    dataSourceProperty.get match {
      case Some(ds) => ds match {
        case pool: JdbcConnectionPool => pool.dispose()
        case _ => // Not a JdbcConnectionPool
      }
      case None => // No previous dataSource
    }
  }
}