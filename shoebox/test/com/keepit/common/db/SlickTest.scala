package com.keepit.common.db

import com.keepit.test._
import com.keepit.TestAkkaSystem
import com.keepit.inject._
import com.keepit.common.db.Session._
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

@RunWith(classOf[JUnitRunner])
class SlickTest extends SpecificationWithJUnit {

  "Slick" should {
    "run in session" in {
      running(new ShoeboxApplication().withFakeHealthcheck()) {

        object Test extends Table[(String)]("TEST") {
          def name = column[String]("NAME", O.PrimaryKey)
          def * = name
        }

        inject[ReadWriteConnection].run { implicit session =>
          Test.ddl.create
          Test.insertAll(("test 1"), ("test 2"))
        }

        inject[ReadOnlyConnection].run { implicit session =>
          Query(Test.count).first === 2
        }
      }
    }
  }
}
