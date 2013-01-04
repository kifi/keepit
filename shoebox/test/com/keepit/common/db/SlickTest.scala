package com.keepit.common.db

import com.keepit.test._
import com.keepit.TestAkkaSystem
import com.keepit.inject._
import org.scalaquery.ql._
import org.scalaquery.ql.TypeMapper._
import org.scalaquery.ql.extended.H2Driver.Implicit._
import org.scalaquery.ql.extended.{ExtendedTable => Table}
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.runner.JUnitRunner
import com.keepit.common.db.slick.DataBaseComponent
import org.scalaquery.ql.basic.BasicProfile
import com.keepit.common.db.slick.IdMapperDelegate

@RunWith(classOf[JUnitRunner])
class SlickTest extends SpecificationWithJUnit {

  "Slick" should {

    "using driver abstraction" in {
      running(new ShoeboxApplication()) {

        case class Foo (
          id: Option[Id[Foo]] = None,
          name: String
        )

        implicit object FooIdTypeMapper extends BaseTypeMapper[Id[Foo]] {
          def apply(profile: BasicProfile) = new IdMapperDelegate[Foo]
        }

        class FooDAO(implicit val db: DataBaseComponent) {
          import db.Driver.Implicit._ // here's the driver, abstracted away
          import org.scalaquery.ql._
          import org.scalaquery.ql.extended.ExtendedTable

          val table = new ExtendedTable[Foo]("foo") {
            def id =       column[Id[Foo]]("id", O.PrimaryKey, O.AutoInc)
            def name =     column[String]("name")
            def * = id.? ~ name <> (Foo, Foo.unapply _)
          }
        }

        //we can abstract out much of the standard repo and have it injected/mocked out
        class FooRepo {
          implicit val db = inject[DataBaseComponent]

          val dao = new FooDAO

          def insert(foo: Foo): Foo = db.readWrite {implicit session =>
            dao.table.insert(foo)
            foo.copy(id = Some(Id(Query(db.sequenceID).first)))
          }

          def count = db.readWrite {implicit s => Query(dao.table.count).first }
        }

        val repo = new FooRepo

        //just for testing you know...
        repo.db.readWrite {implicit s => repo.dao.table.ddl.create }

        val fooA = repo.insert(Foo(name = "A"))
        fooA.id.get.id === 1

        val fooB = repo.insert(Foo(name = "B"))
        fooB.id.get.id === 2

        repo.count === 2

      }
    }

  }
}
