package org.scalarelational.h2

import java.sql.{Blob, Timestamp}
import javax.sql.rowset.serial.SerialBlob

import org.h2.jdbc.JdbcSQLException
import org.powerscala.IO
import org.scalarelational
import org.scalarelational.column.property._
import org.scalarelational.datatype.{DataType, ObjectSerializationConverter, StringDataType}
import org.scalarelational.h2.trigger.TriggerType
import org.scalarelational.model.{HikariSupport, Table, ColumnLike}
import org.scalarelational.model.table.property.Index
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * @author Matt Hicks <matt@outr.com>
 */
class TableSpec extends WordSpec with Matchers {
  var acmeId: Int = _
  var superiorId: Int = _
  var highGroundId: Int = _

  "test" should {
    import TestDatastore._

    "have two columns" in {
      test.columns.size should equal(3)
    }
    "verify the create table String is correct" in {
      val sql = TestDatastore.createTableSQL(test)
      sql should equal("CREATE TABLE IF NOT EXISTS test_table(id INTEGER AUTO_INCREMENT, name VARCHAR(2147483647) UNIQUE, date TIMESTAMP, PRIMARY KEY(id));")
    }
    "verify that there are no tables currently created" in {
      session {
        TestDatastore.jdbcTables should equal(Set.empty)
        TestDatastore.empty should equal(true)
      }
    }
    "create the table" in {
      session {
        create(test, suppliers, coffees, names, fruitColors)
      }
    }
    "verify that tables exist" in {
      session {
        TestDatastore.jdbcTables shouldNot equal(Set.empty)
        TestDatastore.empty should equal(false)
      }
    }
    "insert a record" in {
      session {
        val id = insert(test.name("John Doe")).result
        id should equal(1)
      }
    }
    "create a simple query" in {
      session {
        val q = select(test.id, test.name) from test
        q.expressions should equal((test.id, test.name))
      }
    }
    "query the record back out" in {
      session {
        val query = select(test.id, test.name) from test
        val results = query.result.toList
        results.size should equal(1)
        val john = results.head
        john(test.id) should equal(1)
        john(test.name) should equal("John Doe")
      }
    }
    "query the record back out as a Tuple2" in {
      session {
        val query = select(test.id, test.name) from test
        val results = query.result
        results.next()() should equal((1, "John Doe"))
      }
    }
    "query a record back via 'LIKE'" in {
      session {
        val query = select(test.id, test.name) from test where test.name % "John%"
        val results = query.result.toList
        results.size should equal(1)
        val john = results.head
        john(test.id) should equal(1)
        john(test.name) should equal("John Doe")
      }
    }
    "insert another record" in {
      session {
        insert(test.name("Jane Doe")).result
      }
    }
    "query the record back by name" in {
      session {
        val query = select(test.id, test.name) from test where test.name === "Jane Doe"
        val results = query.result.toList
        results.size should equal(1)
        val jane = results.head
        jane(test.id) should equal(2)
        jane(test.name) should equal("Jane Doe")
      }
    }
    "query with multiple where clauses" in {
      session {
        val query = select(test.id, test.name) from test where (test.name === "Jane Doe" or test.name === "John Doe") and test.id > 0
        val results = query.result.toList
        results.size should equal(2)
      }
    }
    "query two records back via regular expression" in {
      session {
        val query = select(test.id, test.name) from test where test.name * ".*Doe".r
        val results = query.result.toList
        results.size should equal(2)
        val john = results.head
        john(test.id) should equal(1)
        john(test.name) should equal("John Doe")
        val jane = results.tail.head
        jane(test.id) should equal(2)
        jane(test.name) should equal("Jane Doe")
      }
    }
    "update 'John Doe' to 'Joe Doe'" in {
      session {
        val updated = exec(update(test.name("Joe Doe")) where (test.name === "John Doe"))
        updated should equal(1)
      }
    }
    "verify that 'John Doe' no longer exists" in {
      session {
        val query = select(test.name) from test where test.name === "John Doe"
        val results = query.result.toList
        results.size should equal(0)
      }
    }
    "verify that 'Joe Doe' does exist" in {
      session {
        val query = select(test.name) from test where test.name === "Joe Doe"
        val results = query.result.toList
        results.size should equal(1)
      }
    }
    "verify that 'Jane Doe' wasn't modified" in {
      session {
        val query = select(test.name) from test where test.name === "Jane Doe"
        val results = query.result.toList
        results.size should equal(1)
      }
    }
    "delete 'Joe Doe' from the database" in {
      session {
        val deleted = exec(delete(test) where test.name === "Joe Doe")
        deleted should equal(1)
      }
    }
    "verify there is just one record left in the database" in {
      session {
        val query = select(test.id, test.name) from test
        val results = query.result.toList
        results.size should equal(1)
        val jane = results.head
        jane(test.id) should equal(2)
        jane(test.name) should equal("Jane Doe")
      }
    }
    "delete everything from the database" in {
      session {
        val deleted = exec(delete(test))
        deleted should equal(1)
      }
    }
    "verify there are no records left in the database" in {
      session {
        val query = select(test.id, test.name) from test
        val results = query.result.toList
        results.size should equal(0)
      }
    }
    "insert several new records asynchronously" in {
      import test._

      session {
        val future = insert(name("Adam")).
                        and(name("Ben")).
                        and(name("Chris")).
                        and(name("Doug")).
                        and(name("Evan")).
                        and(name("Frank")).
                        and(name("Greg")).
                        and(name("Hank")).
                        and(name("Ivan")).
                        and(name("James")).
                        and(name("Kevin")).
                        and(name("Liam")).
                        and(name("Matt")).
                        and(name("Nathan")).
                        and(name("Orville")).
                        and(name("Phillip")).
                        and(name("Quincy")).
                        and(name("Rand")).
                        and(name("Samuel")).
                        and(name("Tom")).
                        and(name("Uri")).
                        and(name("Vladamir")).
                        and(name("Walter")).
                        and(name("Xavier")).
                        and(name("Yasser")).
                        and(name("Zach")).async
        (select(*) from test).result.toList.length should equal(0)
        Await.result(future, 10.seconds) should equal(List(28))       // Unfortunately a feature-limitation of H2 is batch inserts only returns the last id
        (select(*) from test).result.toList.length should equal(26)
      }
    }
  }
  "suppliers" should {
    import TestDatastore._
    import suppliers._
    "insert three suppliers" in {
      session {
        acmeId = insert(name("Acme, Inc."), street("99 Market Street"), city("Groundsville"), state("CA"), zip("95199")).result
        superiorId = insert(name("Superior Coffee"), street("1 Party Place"), city("Mendocino"), state("CA"), zip("95460")).result
        highGroundId = insert(name("The High Ground"), street("100 Coffee Lane"), city("Meadows"), state("CA"), zip("93966")).result
        acmeId shouldNot equal(0)
        superiorId shouldNot equal(0)
        highGroundId shouldNot equal(0)
      }
    }
  }
  "coffees" should {
    import TestDatastore._
    import coffees._
    "insert five coffees" in {
      session {
        insert(name("Colombian"), supID(acmeId), price(7.99), sales(0), total(0)).
           and(name("French Roast"), supID(superiorId), price(8.99), sales(0), total(0)).
           and(name("Espresso"), supID(highGroundId), price(9.99), sales(0), total(0)).
           and(name("Colombian Decaf"), supID(acmeId), price(8.99), sales(0), total(0)).
           and(name("French Roast Decaf"), supID(superiorId), price(9.99), sales(0), total(0)).result
      }
    }
    "query five coffees back out" in {
      session {
        val results = (select(*) from coffees).result.toList
        results.size should equal(5)
      }
    }
    "query joining suppliers" in {
      session {
        val query = select(name, supID, price, sales, total, suppliers.name) from coffees innerJoin suppliers on suppliers.id === supID
        val results = query.result.toList
        results.size should equal(5)
        val first = results.head
        first.values.size should equal(6)
        first(suppliers.name) should equal("Acme, Inc.")
      }
    }
    "query the minimum price" in {
      session {
        val query = select(price.min) from coffees
        val results = query.result.toList
        results.size should equal(1)
        val values = results.head
        values.values.size should equal(1)
        val minimumPrice = values(price.min)
        minimumPrice should equal(7.99)
      }
    }
    "query the count of coffees for Superior Coffee" in {
      session {
        val query = select(name.count) from coffees innerJoin suppliers on supID === suppliers.id where suppliers.name === "Superior Coffee"
        val results = query.result.toList
        results.size should equal(1)
        val values = results.head
        values.values.size should equal(1)
        val count = values(name.count)
        count should equal(2)
      }
    }
    "query with an inner join aliased" in {
      session {
        val s = suppliers as "s"
        val query = select(name, s(suppliers.name)) from coffees innerJoin s on supID === s(suppliers.id)
        val results = query.result.toList
        results.size should equal(5)
      }
    }
    "query coffees ordered by name and limited to the second result" in {
      session {
        val query = select(name) from coffees orderBy (name asc) limit 1 offset 1
        val results = query.result.toList
        results.size should equal(1)
        val values = results.head
        values.values.size should equal(1)
        val coffeeName = values(name)
        coffeeName should equal("Colombian Decaf")
      }
    }
    "query coffees grouped by price" in {
      session {
        val query = select(price) from coffees groupBy price orderBy (price asc)
        val results = query.result.toVector
        results.size should equal(3)
        results.head(price) should equal(7.99)
        results(1)(price) should equal(8.99)
        results(2)(price) should equal(9.99)
      }
    }
  }
  "names" should {
    import TestDatastore._
    import names._

    val queryAll = select(*) from names orderBy(name asc)

    "have no records in the table" in {
      session {
        val results = queryAll.result.toList
        results.size should equal(0)
      }
    }
    "merge 'John Doe' for an inserted record" in {
      session {
        merge(name, name("John Doe"), age(21)).result
        val results = queryAll.result.toList
        results.size should equal(1)
        val result = results.head
        result(name) should equal("John Doe")
        result(age) should equal(21)
      }
    }
    "merge 'John Doe' for an updated record" in {
      session {
        merge(name, name("John Doe"), age(25)).result
        val results = queryAll.result.toList
        results.size should equal(1)
        val result = results.head
        result(name) should equal("John Doe")
        result(age) should equal(25)
      }
    }
    "merge 'Jane Doe' for an inserted record" in {
      session {
        merge(name, name("Jane Doe"), age(22)).result
        val results = queryAll.result.toList
        results.size should equal(2)
        val jane = results.head
        jane(name) should equal("Jane Doe")
        jane(age) should equal(22)
        val john = results.tail.head
        john(name) should equal("John Doe")
        john(age) should equal(25)
      }
    }
  }
  "fruit colors" should {
    import TestDatastore._
    import fruitColors._

    "insert an Orange" in {
      session {
        insert(color("Orange"), fruit(Fruit("Orange"))).result
      }
    }
    "query the Orange back" in {
      session {
        val results = (select(*) from fruitColors).result.toList
        results.size should equal(1)
        val orange = results.head
        orange(color) should equal("Orange")
        orange(fruit) should equal(Fruit("Orange"))
      }
    }
  }
  "TestCrossReferenceDatastore" should {
    import TestCrossReferenceDatastore._
    "create the tables successfully" in {
      session {
        TestCrossReferenceDatastore.create(first, second)
      }
    }
  }
  "TestSpecialTypesDatastore" should {
    import SpecialTypesDatastore._

    var listId: Int = -1
    var dataId: Int = -1
    var inserted = 0
    var updated = 0
    var deleted = 0
    var selected = 0

    "create the tables successfully" in {
      session {
        create(lists, data, combinedUnique, triggerTest)
      }
    }
    "insert a List[String] entry" in {
      session {
        val idOption = insert(lists.strings(List("One", "Two", "Three")))
        idOption shouldNot equal(None)
        listId = idOption.result
        listId should equal(1)
      }
    }
    "query a List[String] entry" in {
      session {
        val query = select(lists.id, lists.strings) from lists
        val results = query.result.toList
        results.size should equal(1)
        val result = results.head
        result(lists.id) should equal(listId)
        result(lists.strings) should equal(List("One", "Two", "Three"))
      }
    }
    "insert a Blob entry" in {
      session {
        dataId = insert(data.content(new SerialBlob("test using blob".getBytes("UTF-8")))).result
        dataId should equal(1)
      }
    }
    "query a Blob entry" in {
      session {
        val query = select(data.id, data.content) from data
        val results = query.result.toList
        results.size should equal(1)
        val result = results.head
        result(data.id) should equal(dataId)
        val content = result(data.content)
        val s = IO.copy(content.getBinaryStream)
        s should equal("test using blob")
      }
    }
    "insert John Doe into combinedUnique" in {
      session {
        insert(combinedUnique.firstName("John"), combinedUnique.lastName("Doe")).result should equal(1)
      }
    }
    "insert Jane Doe into combinedUnique" in {
      session {
        insert(combinedUnique.firstName("Jane"), combinedUnique.lastName("Doe")).result should equal(2)
      }
    }
    "attempting to insert John Doe again throws a constraint violation" in {
      session {
        intercept[JdbcSQLException] {
          insert(combinedUnique.firstName("John"), combinedUnique.lastName("Doe")).result
          fail()
        }
      }
    }
    "add a trigger" in {
      trigger.on {
        case evt => evt.triggerType match {
          case TriggerType.Insert => inserted += 1
          case TriggerType.Update => updated += 1
          case TriggerType.Delete => deleted += 1
          case TriggerType.Select => selected += 1
        }
      } shouldNot equal(null)
    }
    "validate no trigger has been invoked" in {
      inserted should equal(0)
      updated should equal(0)
      deleted should equal(0)
      selected should equal(0)
    }
    "insert a record to fire a trigger" in {
      session {
        insert(triggerTest.name("Test1")).result should equal(1)
      }
    }
    "validate that one insert was triggered" in {
      inserted should equal(1)
      updated should equal(0)
      deleted should equal(0)
      selected should equal(0)
    }
    "update a record to fire a trigger" in {
      session {
        exec(update(triggerTest.name("Test2")) where triggerTest.id === 1) should equal(1)
      }
    }
    "validate that one update was triggered" in {
      inserted should equal(1)
      updated should equal(1)
      deleted should equal(0)
      selected should equal(0)
    }
    "select a record to fire a select trigger" in {
      session {
        val results = (select(triggerTest.*) from triggerTest).result.toList
        results.size should equal(1)
      }
    }
    "validate that another update was triggered" in {
      inserted should equal(1)
      updated should equal(1)
      deleted should equal(0)
      selected should equal(1)
    }
    "delete a record to fire a trigger" in {
      session {
        exec(delete(triggerTest) where triggerTest.id === 1) should equal(1)
      }
    }
    "validate that one delete was triggered" in {
      inserted should equal(1)
      updated should equal(1)
      deleted should equal(1)
      selected should equal(1)
    }
  }
}

object TestDatastore extends H2Datastore(mode = H2Memory("tablespec")) with HikariSupport {
  object test extends Table("test_table") {
    val id = column[Int]("id", PrimaryKey, AutoIncrement)
    val name = column[String]("name", Unique)
    val date = column[Timestamp]("date")
  }
  object suppliers extends Table("SUPPLIER") {
    val id = column[Int]("SUP_ID", PrimaryKey, AutoIncrement)
    val name = column[String]("SUP_NAME")
    val street = column[String]("STREET")
    val city = column[String]("CITY")
    val state = column[String]("STATE")
    val zip = column[String]("ZIP")
  }
  object coffees extends Table("COFFEE") {
    val name = column[String]("COF_NAME", PrimaryKey)
    val supID = column[Int]("SUP_ID", new ForeignKey(TestDatastore.suppliers.id))
    val price = column[Double]("PRICE")
    val sales = column[Int]("SALES")
    val total = column[Int]("TOTAL")
  }
  object names extends Table("names") {
    val name = column[String]("name", PrimaryKey, Unique, NotNull)
    val age = column[Int]("age", NotNull, Indexed("idxage"))
  }
  object fruitColors extends Table("fruit_colors") {
    val color = column[String]("color", NotNull)
    val fruit = column[Fruit]("fruit", new ObjectSerializationConverter[Fruit], NotNull)
  }
}

object TestCrossReferenceDatastore extends H2Datastore(mode = H2Memory("cross_reference")) {
  object first extends Table("first") {
    val id = column[Int]("id", PrimaryKey, AutoIncrement)
    val name = column[String]("name")
    val secondId = column[Int]("secondId")
  }

  object second extends Table("second") {
    val id = column[Int]("id", PrimaryKey, AutoIncrement)
    val value = column[Int]("value")
    val firstId = column[Int]("firstId", new ForeignKey(first.id))
  }

  first.secondId.props(new ForeignKey(second.id))
}

object SpecialTypesDatastore extends H2Datastore(mode = H2Memory("special_types")) {
  object lists extends Table("lists") {
    implicit val listStringConverter = new DataType[List[String]] {
      def sqlType(column: ColumnLike[List[String]]) = StringDataType.VarcharType

      def toSQLType(column: ColumnLike[List[String]], value: List[String]) = value.mkString("|")

      def fromSQLType(column: ColumnLike[List[String]], value: Any) = value match {
        case null => Nil
        case s: String => s.split('|').toList
      }
    }

    val id = column[Int]("id", PrimaryKey, AutoIncrement)
    val strings = column[List[String]]("strings")
  }

  object data extends Table("data") {
    val id = column[Int]("id", PrimaryKey, AutoIncrement)
    val content = column[Blob]("content")
  }

  object combinedUnique extends Table("combined_unique") {
    val id = column[Int]("id", PrimaryKey, AutoIncrement)
    val firstName = column[String]("firstName", NotNull)
    val lastName = column[String]("lastName", NotNull)

    props(Index.unique("IDXNAME", firstName, lastName))
  }

  object triggerTest extends Table("trigger_test", Triggers.All) {
    val id = column[Int]("id", PrimaryKey, AutoIncrement)
    val name = column[String]("name", NotNull)
  }
}

case class Fruit(name: String)