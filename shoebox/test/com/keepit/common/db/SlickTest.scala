package com.keepit.common.db

import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.test._
import com.keepit.TestAkkaSystem
import com.keepit.inject._
import org.scalaquery.ql._
import org.scalaquery.ql.TypeMapper._
import org.scalaquery.ql.extended.H2Driver.Implicit._
import org.scalaquery.ql.extended.ExtendedTable
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.runner.JUnitRunner
import org.scalaquery.ql.basic.BasicProfile
import com.keepit.common.db.slick._
import org.joda.time.DateTime

@RunWith(classOf[JUnitRunner])
class SlickTest extends SpecificationWithJUnit {

  "Slick" should {

    "using driver abstraction" in {
      running(new ShoeboxApplication()) {

        case class Foo(
          id: Option[Id[Foo]] = None,
          name: String
        ) extends Model[Foo] {
          def withId(id: Id[Foo]): Foo = this.copy(id = Some(id))
          def updateTime(now: DateTime) = this
        }

        //could be easily mocked up
        trait FooRepo extends Repo[Foo] {
          //here you may have model specific queries...
          def getByName(name: String)(implicit session: ROSession): Seq[Foo]
        }

        //we can abstract out much of the standard repo and have it injected/mocked out
        class FooRepoImpl extends FooRepo with DbRepo[Foo] {
          import db.Driver.Implicit._ // here's the driver, abstracted away

          implicit object FooIdTypeMapper extends BaseTypeMapper[Id[Foo]] {
            def apply(profile: BasicProfile) = new IdMapperDelegate[Foo]
          }

          override val table = new RepoTable[Foo]("foo") {
            def name =     column[String]("name")
            def * = id.? ~ name <> (Foo, Foo.unapply _)
          }

          def getByName(name: String)(implicit session: ROSession): Seq[Foo] = {
            val q = for ( f <- table if f.name is name ) yield (f)
            q.list
          }

          //only for testing
          def createTableForTesting()(implicit session: RWSession) = table.ddl.create
        }

        val repo: FooRepo = new FooRepoImpl //to be injected with guice

        //just for testing you know...
        inject[DBConnection].readWrite{ implicit session =>
          repo.asInstanceOf[FooRepoImpl].createTableForTesting() //only in test mode we should know about the implementation
          val fooA = repo.save(Foo(name = "A"))
          fooA.id.get.id === 1
          val fooB = repo.save(Foo(name = "B"))
          fooB.id.get.id === 2
        }

        inject[DBConnection].readOnly{ implicit session =>
          repo.count(session) === 2
          val a = repo.getByName("A")
          a.size === 1
          a.head.name === "A"
        }
      }
    }

  }
}
