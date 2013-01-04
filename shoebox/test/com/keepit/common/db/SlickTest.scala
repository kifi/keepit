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

        //could be easily mocked up
        trait FooRepo {
          def save(foo: Foo): Foo
          def count: Int
        }

        //we can abstract out much of the standard repo and have it injected/mocked out
        class FooRepoImpl extends FooRepo {
          implicit val db = inject[DataBaseComponent]
          implicit object FooIdTypeMapper extends BaseTypeMapper[Id[Foo]] {
            def apply(profile: BasicProfile) = new IdMapperDelegate[Foo]
          }

          private val dao = new FooDAO
          def createTableForTesting() = db.readWrite {implicit s => dao.table.ddl.create}

          def save(foo: Foo): Foo = db.readWrite {implicit session =>
            // here you would do the insert/save logic and update the 'updatedAt' field
            dao.table.insert(foo)
            foo.copy(id = Some(Id(Query(db.sequenceID).first)))
          }

          def count = db.readWrite {implicit s => Query(dao.table.count).first }

          private class FooDAO(implicit val db: DataBaseComponent) {
            import db.Driver.Implicit._ // here's the driver, abstracted away
            import org.scalaquery.ql._
            import org.scalaquery.ql.extended.ExtendedTable

            val table = new ExtendedTable[Foo]("foo") {
              def id =       column[Id[Foo]]("id", O.PrimaryKey, O.AutoInc)
              def name =     column[String]("name")
              def * = id.? ~ name <> (Foo, Foo.unapply _)
            }
          }
        }

        val repo: FooRepo = new FooRepoImpl //to be injected with guice

        //just for testing you know...
        repo.asInstanceOf[FooRepoImpl].createTableForTesting() //only in test mode we should know about the implementation

        val fooA = repo.save(Foo(name = "A"))
        fooA.id.get.id === 1

        val fooB = repo.save(Foo(name = "B"))
        fooB.id.get.id === 2

        repo.count === 2

      }
    }

  }
}
