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
import com.keepit.common.db.slick._

@RunWith(classOf[JUnitRunner])
class SlickTest extends SpecificationWithJUnit {

  "Slick" should {
    "run in session" in {
      running(new ShoeboxApplication()) {

        object Test extends Table[(String)]("TEST") {
          def name = column[String]("NAME", O.PrimaryKey)
          def * = name
        }

        inject[DbConnection] readWrite { implicit session =>
          Test.ddl.create
          Test.insertAll(("test 1"), ("test 2"))
        }

        inject[DbConnection].readOnly { implicit session =>
          Query(Test.count).first === 2
        }
      }
    }

    "using driver abstraction" in {
      running(new ShoeboxApplication()) {
        class UserRepo {
          implicit lazy val db = inject[DataBaseComponent]
          val foo              = new FooDAO
        }

        case class foo (
          id: Option[Id[foo]] = None,
          name: String
        )

        class FooDAO(implicit val db: DatabaseComponent) {
          import db.Driver.Implicit._ // here's the driver, abstracted away
          import org.scalaquery.ql._

          object FollowEntity extends ExtendedTable[Follow]("follow") {
            def id =        column[Id[Follow]]("id", O.PrimaryKey, O.AutoInc)
            def name =     column[String]("name")

            def * = id.? ~ name <> (Foo, Foo.unapply _)
          }

        }

      }
    }

  }
}
